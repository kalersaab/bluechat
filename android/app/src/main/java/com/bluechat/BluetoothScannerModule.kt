package com.bluechat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.android.bluechat.NativeBluetoothScannerSpec
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@ReactModule(name = NativeBluetoothScannerSpec.NAME)
class BluetoothScannerModule(reactContext: ReactApplicationContext) :
    NativeBluetoothScannerSpec(reactContext) {

  private val searching = AtomicBoolean(false)
  private val handler = Handler(Looper.getMainLooper())

  private var serverTxCharacteristic: BluetoothGattCharacteristic? = null
  private var subscribedDevice: BluetoothDevice? = null
  private var gattServer: BluetoothGattServer? = null
  private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null

  private var gatt: BluetoothGatt? = null
  private var clientRxCharacteristic: BluetoothGattCharacteristic? = null
  private var clientTxCharacteristic: BluetoothGattCharacteristic? = null
  private var clientChunkSize = 20
  private var writeInProgress = false

  private val outgoingChunks = ArrayDeque<ByteArray>()
  private val incomingBuffer = ByteArrayOutputStream()

  private val adapter: BluetoothAdapter?
    get() = (reactApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      emitDevice(result)
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>) {
      results.forEach { emitDevice(it) }
    }

    override fun onScanFailed(errorCode: Int) {
      if (searching.compareAndSet(true, false)) {
        emitOnSearchStateChanged(false)
      }
    }
  }

  private fun emitDevice(result: ScanResult) {
    val device = result.device ?: return
    val map: WritableMap = Arguments.createMap()
    map.putString("id", device.address)
    map.putString("name", deviceName(device, result))
    map.putDouble("rssi", result.rssi.toDouble())
    map.putBoolean("paired", device.bondState == BluetoothDevice.BOND_BONDED)
    emitOnDeviceFound(map)
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

  override fun startBluetoothSearch() {
    ensureServer()
    startAdvertising()

    val bleAdapter = adapter
    val scanner = bleAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

    if (scanner == null || !hasScanPermission()) {
      emitOnSearchStateChanged(false)
      return
    }
    if (!searching.compareAndSet(false, true)) {
      return
    }

    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
    scanner.startScan(null, settings, scanCallback)
    emitOnSearchStateChanged(true)
  }

  override fun stopBluetoothSearch() {
    if (searching.compareAndSet(true, false)) {
      adapter?.bluetoothLeScanner?.stopScan(scanCallback)
      emitOnSearchStateChanged(false)
    }
  }

  override fun isBluetoothSearching(): Boolean = searching.get()

  override fun connect(deviceId: String) {
    val device = try {
      adapter?.getRemoteDevice(deviceId)
    } catch (exception: IllegalArgumentException) {
      null
    } ?: return

    disconnect()
    ensureServer()
    startAdvertising()

    clientChunkSize = 20
    gatt = device.connectGatt(reactApplicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
  }

  override fun disconnect() {
    gatt?.let { client ->
      try {
        client.disconnect()
        client.close()
      } catch (exception: SecurityException) {
      }
    }
    gatt = null
    clientRxCharacteristic = null
    clientTxCharacteristic = null
    writeInProgress = false
    synchronized(outgoingChunks) { outgoingChunks.clear() }

    subscribedDevice?.let { device ->
      try {
        gattServer?.cancelConnection(device)
      } catch (exception: SecurityException) {
      }
    }
    subscribedDevice = null
  }

  override fun sendMessage(text: String) {
    val payload = text.toByteArray(Charsets.UTF_8)
    val framed = ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()

    val chunkSize = if (subscribedDevice != null) 20 else clientChunkSize
    var offset = 0
    synchronized(outgoingChunks) {
      while (offset < framed.size) {
        val end = minOf(offset + chunkSize, framed.size)
        outgoingChunks.add(framed.copyOfRange(offset, end))
        offset = end
      }
    }
    sendNextChunk()
  }

  private fun sendNextChunk() {
    val notifyDevice = subscribedDevice
    val txCharacteristic = serverTxCharacteristic
    val server = gattServer

    if (notifyDevice != null && txCharacteristic != null && server != null) {
      val chunk = synchronized(outgoingChunks) {
        if (outgoingChunks.isEmpty()) return else outgoingChunks.removeFirst()
      }
      txCharacteristic.value = chunk
      try {
        server.notifyCharacteristicChanged(notifyDevice, txCharacteristic, false)
      } catch (exception: SecurityException) {
      }
      handler.postDelayed({ sendNextChunk() }, 30)
      return
    }

    val client = gatt
    val rxCharacteristic = clientRxCharacteristic
    if (client != null && rxCharacteristic != null) {
      val chunk = synchronized(outgoingChunks) {
        if (writeInProgress || outgoingChunks.isEmpty()) return else outgoingChunks.removeFirst()
      }
      writeInProgress = true
      rxCharacteristic.value = chunk
      rxCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
      val started = try {
        client.writeCharacteristic(rxCharacteristic)
      } catch (exception: SecurityException) {
        false
      }
      if (!started) {
        writeInProgress = false
        handler.postDelayed({ sendNextChunk() }, 50)
      }
    }
  }

  private fun handleIncomingBytes(bytes: ByteArray?, deviceId: String) {
    if (bytes == null || bytes.isEmpty()) {
      return
    }
    synchronized(incomingBuffer) {
      incomingBuffer.write(bytes)
      val data = incomingBuffer.toByteArray()
      var offset = 0
      while (data.size - offset >= 4) {
        val length = ByteBuffer.wrap(data, offset, 4).int
        if (length < 0 || length > 1_000_000) {
          incomingBuffer.reset()
          return
        }
        if (data.size - offset - 4 < length) {
          break
        }
        val text = String(data, offset + 4, length, Charsets.UTF_8)
        offset += 4 + length
        emitMessage(text, deviceId)
      }
      incomingBuffer.reset()
      if (offset < data.size) {
        incomingBuffer.write(data, offset, data.size - offset)
      }
    }
  }

  private fun emitMessage(text: String, deviceId: String) {
    val map: WritableMap = Arguments.createMap()
    map.putString("text", text)
    map.putString("deviceId", deviceId)
    emitOnMessageReceived(map)
  }

  private fun emitConnectionState(connected: Boolean, deviceId: String) {
    val map: WritableMap = Arguments.createMap()
    map.putBoolean("connected", connected)
    map.putString("deviceId", deviceId)
    emitOnConnectionStateChanged(map)
  }

  private fun ensureServer() {
    if (gattServer != null) {
      return
    }
    if (!hasConnectPermission()) {
      return
    }
    val manager = reactApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return

    val rx = BluetoothGattCharacteristic(
        UUID_RX,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE)
    val tx = BluetoothGattCharacteristic(
        UUID_TX,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ)
    tx.addDescriptor(BluetoothGattDescriptor(
        UUID_CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))

    val service = BluetoothGattService(UUID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    service.addCharacteristic(rx)
    service.addCharacteristic(tx)

    val server = try {
      manager.openGattServer(reactApplicationContext, serverCallback)
    } catch (exception: SecurityException) {
      null
    }
    server?.addService(service)
    serverTxCharacteristic = tx
    gattServer = server
  }

  private fun startAdvertising() {
    val bleAdapter = adapter ?: return
    if (!bleAdapter.isEnabled || !hasAdvertisePermission()) {
      return
    }
    val bleAdvertiser = bleAdapter.bluetoothLeAdvertiser ?: return
    advertiser = bleAdvertiser

    val settings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setConnectable(true)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .build()
    val data = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .addServiceUuid(ParcelUuid(UUID_SERVICE))
        .build()

    try {
      bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
    } catch (exception: SecurityException) {
    }
  }

  private val advertiseCallback = object : AdvertiseCallback() {}

  private val serverCallback = object : BluetoothGattServerCallback() {
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        if (subscribedDevice?.address == device.address) {
          subscribedDevice = null
          emitConnectionState(false, device.address)
        }
      }
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?) {
      if (characteristic.uuid == UUID_RX) {
        if (responseNeeded) {
          try {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
          } catch (exception: SecurityException) {
          }
        }
        handleIncomingBytes(value, device.address)
      } else if (responseNeeded) {
        try {
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
        } catch (exception: SecurityException) {
        }
      }
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?) {
      if (descriptor.uuid == UUID_CCCD &&
          descriptor.characteristic.uuid == UUID_TX &&
          value != null) {
        val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        subscribedDevice = if (enabled) device else null
        emitConnectionState(enabled, device.address)
      }
      if (responseNeeded) {
        try {
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        } catch (exception: SecurityException) {
        }
      }
    }
  }

  private val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        try {
          client.requestMtu(512)
          client.discoverServices()
        } catch (exception: SecurityException) {
        }
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        val address = client.device?.address ?: ""
        try {
          client.close()
        } catch (exception: SecurityException) {
        }
        if (gatt === client) {
          gatt = null
          clientRxCharacteristic = null
          clientTxCharacteristic = null
          writeInProgress = false
          synchronized(outgoingChunks) { outgoingChunks.clear() }
        }
        emitConnectionState(false, address)
      }
    }

    override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
      clientChunkSize = maxOf(20, mtu - 3)
    }

    override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
      val service = client.getService(UUID_SERVICE) ?: return
      val rx = service.getCharacteristic(UUID_RX)
      val tx = service.getCharacteristic(UUID_TX)
      clientRxCharacteristic = rx
      clientTxCharacteristic = tx

      if (tx != null) {
        try {
          client.setCharacteristicNotification(tx, true)
          val descriptor = tx.getDescriptor(UUID_CCCD)
          if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            client.writeDescriptor(descriptor)
          }
        } catch (exception: SecurityException) {
        }
      }
      emitConnectionState(true, client.device?.address ?: "")
    }

    override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      if (characteristic.uuid == UUID_TX) {
        handleIncomingBytes(characteristic.value, client.device?.address ?: "")
      }
    }

    override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      writeInProgress = false
      sendNextChunk()
    }
  }

  private fun hasScanPermission(): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Manifest.permission.BLUETOOTH_SCAN
    } else {
      Manifest.permission.ACCESS_FINE_LOCATION
    }
    return reactApplicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
  }

  private fun hasConnectPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return true
    }
    return reactApplicationContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
  }

  private fun hasAdvertisePermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return true
    }
    return reactApplicationContext.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ==
        PackageManager.PERMISSION_GRANTED
  }

  override fun invalidate() {
    searching.set(false)
    try {
      adapter?.bluetoothLeScanner?.stopScan(scanCallback)
      advertiser?.stopAdvertising(advertiseCallback)
    } catch (exception: SecurityException) {
    }
    gatt?.let { client ->
      try {
        client.disconnect()
        client.close()
      } catch (exception: SecurityException) {
      }
    }
    gatt = null
    try {
      gattServer?.close()
    } catch (exception: SecurityException) {
    }
    gattServer = null
    serverTxCharacteristic = null
    subscribedDevice = null
    super.invalidate()
  }

  companion object {
    private val UUID_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val UUID_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val UUID_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
  }
}
