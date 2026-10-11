package com.bluechat

import com.android.bluechat.NativeBluetoothScannerSpec
import com.bluechat.bluetooth.BleManager
import com.bluechat.bluetooth.ConnectionState
import com.bluechat.bluetooth.DiscoveredDevice
import com.bluechat.protocol.AckPacket
import com.bluechat.protocol.MessagePacket
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = NativeBluetoothScannerSpec.NAME)
class BluetoothScannerModule(reactContext: ReactApplicationContext) :
    NativeBluetoothScannerSpec(reactContext), BleManager.Listener {

  private val manager = BleManager(reactApplicationContext, this)

  override fun startBluetoothSearch() {
    manager.startSearch()
  }

  override fun stopBluetoothSearch() {
    manager.stopSearch()
  }

  override fun isBluetoothSearching(): Boolean = manager.isSearching()

  override fun connect(deviceId: String) {
    manager.connect(deviceId)
  }

  override fun disconnect() {
    manager.disconnect()
  }

  override fun sendMessage(deviceId: String, messageId: String, text: String, timestamp: Double) {
    manager.sendMessage(deviceId, messageId, text, timestamp.toLong())
  }

  override fun onDeviceFound(device: DiscoveredDevice) {
    val map: WritableMap = Arguments.createMap()
    map.putString("id", device.id)
    map.putString("name", device.name)
    map.putDouble("rssi", device.rssi.toDouble())
    map.putBoolean("paired", device.paired)
    emitOnDeviceFound(map)
  }

  override fun onSearchStateChanged(searching: Boolean) {
    emitOnSearchStateChanged(searching)
  }

  override fun onConnectionStateChanged(state: ConnectionState) {
    val map: WritableMap = Arguments.createMap()
    map.putBoolean("connected", state.connected)
    map.putString("deviceId", state.deviceId)
    emitOnConnectionStateChanged(map)
  }

  override fun onMessageReceived(deviceId: String, packet: MessagePacket) {
    val map: WritableMap = Arguments.createMap()
    map.putString("deviceId", deviceId)
    map.putString("messageId", packet.messageId)
    map.putString("text", packet.text)
    map.putDouble("timestamp", packet.timestamp.toDouble())
    emitOnMessageReceived(map)
  }

  override fun onAckReceived(deviceId: String, packet: AckPacket) {
    val map: WritableMap = Arguments.createMap()
    map.putString("deviceId", deviceId)
    map.putString("messageId", packet.messageId)
    map.putDouble("timestamp", packet.timestamp.toDouble())
    emitOnAckReceived(map)
  }



  override fun sendMeshMessage(src: String, dst: String, text: String, id: String) {
    manager.sendMeshMessage(src, dst, text, id)
  }

  override fun getRoutes(promise: Promise) {
    manager.getRoutes(promise)
  }

  override fun updateRoute(dest: String, nextHop: String, cost: Double) {
    manager.updateRoute(dest, nextHop, cost.toInt())
  }



  override fun invalidate() {
    manager.close()
    super.invalidate()
  }
}
