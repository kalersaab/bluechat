package com.bluechat.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.bluechat.protocol.AckPacket
import com.bluechat.protocol.MessagePacket
import com.bluechat.protocol.Packet
import com.bluechat.protocol.PacketDecoder
import com.bluechat.protocol.PacketEncoder
import com.bluechat.protocol.PacketFramer
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredDevice(
    val id: String,
    val name: String,
    val rssi: Int,
    val paired: Boolean,
)

class BleManager(
    private val context: ReactApplicationContext,
    private val listener: Listener,
) : BleServer.Listener, BleClient.Listener {

  interface Listener {
    fun onDeviceFound(device: DiscoveredDevice)
    fun onSearchStateChanged(searching: Boolean)
    fun onConnectionStateChanged(state: ConnectionState)
    fun onMessageReceived(deviceId: String, packet: MessagePacket)
    fun onAckReceived(deviceId: String, packet: AckPacket)
    fun onMessageFailed(deviceId: String, messageId: String) {}
  }

  private val searching = AtomicBoolean(false)
  private val handler = Handler(Looper.getMainLooper())
  private val connections = mutableMapOf<String, DeviceConnection>()
  private val framer = PacketFramer()
  private val seenMessages = SeenMessageCache()
  private val pendingSends = java.util.LinkedHashMap<String, PendingSend>()
  private val pendingTimers = java.util.LinkedHashMap<String, Runnable>()
  private val recovery = ConnectionRecovery()

  private fun sendKey(deviceId: String, messageId: String): String = "$deviceId:$messageId"

  private val server = BleServer(context, this)
  private val client = BleClient(context, this)

  private val adapter: BluetoothAdapter?
    get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      emitDevice(result)
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>) {
      results.forEach { emitDevice(it) }
    }

    override fun onScanFailed(errorCode: Int) {
      if (searching.compareAndSet(true, false)) {
        listener.onSearchStateChanged(false)
      }
    }
  }

  fun startSearch() {
    server.ensureStarted()
    server.advertise()

    val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    if (scanner == null || !hasScanPermission()) {
      listener.onSearchStateChanged(false)
      return
    }
    if (!searching.compareAndSet(false, true)) {
      return
    }

    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
    scanner.startScan(null, settings, scanCallback)
    listener.onSearchStateChanged(true)
  }

  fun stopSearch() {
    if (searching.compareAndSet(true, false)) {
      adapter?.bluetoothLeScanner?.stopScan(scanCallback)
      listener.onSearchStateChanged(false)
    }
  }

  fun isSearching(): Boolean = searching.get()

  fun connect(deviceId: String) {
    server.ensureStarted()
    server.advertise()
    client.connect(deviceId)
  }

  fun disconnect() {
    client.disconnect()
    server.subscribedDeviceId?.let { server.cancelConnection(it) }
    synchronized(connections) { connections.clear() }
  }

  fun sendMessage(deviceId: String, messageId: String, text: String, timestamp: Long) {
    val packet = MessagePacket(messageId, timestamp, text)
    val key = sendKey(deviceId, messageId)
    val pending = PendingSend(deviceId, packet, 0)
    synchronized(pendingSends) {
      pendingSends[key] = pending
    }
    scheduleTimeout(key)
    sendPacket(deviceId, packet)
    recovery.recordDevice(deviceId)
  }

  private fun scheduleTimeout(key: String) {
    val runnable = Runnable {
      handleTimeout(key)
    }
    synchronized(pendingTimers) {
      pendingTimers[key] = runnable
    }
    handler.postDelayed(runnable, ACK_TIMEOUT_MS)
  }

  private fun handleTimeout(key: String) {
    val pending = synchronized(pendingSends) {
      pendingSends[key]
    } ?: return
    if (pending.retryCount >= MAX_RETRIES) {
      synchronized(pendingSends) { pendingSends.remove(key) }
      synchronized(pendingTimers) { pendingTimers.remove(key) }
      listener.onMessageFailed(pending.deviceId, pending.packet.messageId)
      return
    }
    val newPending = pending.copy(retryCount = pending.retryCount + 1)
    synchronized(pendingSends) {
      pendingSends[key] = newPending
    }
    sendPacket(pending.deviceId, pending.packet)
    scheduleTimeout(key)
  }



  fun sendMeshMessage(src: String, dst: String, text: String, id: String) {
    // Basic mesh forwarding placeholder - store and relay via existing transport
    // In a full implementation, this would use route table
  }

  fun getRoutes(promise: Promise) {
    promise.resolve(android.os.Bundle().apply { putInt("count", 0) })
  }

  fun updateRoute(dest: String, nextHop: String, cost: Int) {
    // placeholder
  }

  fun close() {
    searching.set(false)
    try {
      adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    } catch (exception: SecurityException) {
    }
    server.close()
    client.disconnect()
    synchronized(connections) { connections.clear() }
  }

  private fun sendPacket(deviceId: String, packet: Packet) {
    val framed = framer.frame(PacketEncoder.encode(packet))
    val chunkSize = if (server.subscribedDeviceId == deviceId) 20 else client.chunkSize
    val connection = connectionFor(deviceId)
    synchronized(connection.outgoing) {
      connection.outgoing.addAll(framer.chunk(framed, chunkSize))
    }
    sendNextChunk(deviceId)
  }

  private fun sendNextChunk(deviceId: String) {
    val connection = connectionFor(deviceId)

    if (server.subscribedDeviceId == deviceId) {
      val chunk = synchronized(connection.outgoing) {
        if (connection.outgoing.isEmpty()) return else connection.outgoing.removeFirst()
      }
      if (!server.notify(deviceId, chunk)) {
        synchronized(connection.outgoing) { connection.outgoing.addFirst(chunk) }
      }
      handler.postDelayed({ sendNextChunk(deviceId) }, 30)
      return
    }

    if (client.connectedDeviceId == deviceId) {
      if (connection.writeInProgress) {
        return
      }
      val chunk = synchronized(connection.outgoing) {
        if (connection.outgoing.isEmpty()) return else connection.outgoing.removeFirst()
      }
      connection.writeInProgress = true
      if (!client.write(deviceId, chunk)) {
        connection.writeInProgress = false
        synchronized(connection.outgoing) { connection.outgoing.addFirst(chunk) }
        handler.postDelayed({ sendNextChunk(deviceId) }, 50)
      }
    }
  }

  private fun connectionFor(deviceId: String): DeviceConnection =
      synchronized(connections) {
        connections.getOrPut(deviceId) { DeviceConnection(deviceId) }
      }

  private fun handleBytes(deviceId: String, bytes: ByteArray) {
    val payloads = connectionFor(deviceId).reassembler.append(bytes)
    for (payload in payloads) {
      val packet = PacketDecoder.decode(payload) ?: continue
      when (packet) {
        is MessagePacket -> {
          val key = "$deviceId:${packet.messageId}"
          if (seenMessages.has(key)) {
            sendPacket(deviceId, AckPacket(packet.messageId, System.currentTimeMillis()))
          } else {
            seenMessages.add(key)
            listener.onMessageReceived(deviceId, packet)
            sendPacket(deviceId, AckPacket(packet.messageId, System.currentTimeMillis()))
          }
        }
        is AckPacket -> {
          val key = sendKey(deviceId, packet.messageId)
          synchronized(pendingSends) {
            pendingSends.remove(key)
          }
          val runnable = synchronized(pendingTimers) {
            pendingTimers.remove(key)
          }
          runnable?.let { handler.removeCallbacks(it) }
          listener.onAckReceived(deviceId, packet)
        }
      }
    }
  }

  private fun emitDevice(result: ScanResult) {
    val device = result.device ?: return
    listener.onDeviceFound(
        DiscoveredDevice(
            id = device.address,
            name = deviceName(device, result),
            rssi = result.rssi,
            paired = device.bondState == BluetoothDevice.BOND_BONDED))
  }

  private fun deviceName(device: BluetoothDevice, result: ScanResult): String {
    val advertisedName = result.scanRecord?.deviceName
    if (!advertisedName.isNullOrEmpty()) {
      return advertisedName
    }
    return try {
      device.name?.takeIf { it.isNotEmpty() } ?: "Unknown device"
    } catch (exception: SecurityException) {
      "Unknown device"
    }
  }

  private fun hasScanPermission(): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Manifest.permission.BLUETOOTH_SCAN
    } else {
      Manifest.permission.ACCESS_FINE_LOCATION
    }
    return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
  }

  override fun onBytes(deviceId: String, bytes: ByteArray) {
    handleBytes(deviceId, bytes)
  }

  override fun onConnectionStateChanged(state: ConnectionState) {
    if (!state.connected) {
      synchronized(connections) { connections.remove(state.deviceId) }
    }
    listener.onConnectionStateChanged(state)
  }

  override fun onWriteComplete(deviceId: String) {
    connectionFor(deviceId).writeInProgress = false
    sendNextChunk(deviceId)
  }

  companion object {
    private const val ACK_TIMEOUT_MS = 3000L
    private const val MAX_RETRIES = 5
    val UUID_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val UUID_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val UUID_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
  }
}
