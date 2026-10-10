package com.bluechat

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.bluechat.NativeBluetoothScannerSpec
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import java.util.concurrent.atomic.AtomicBoolean

@ReactModule(name = NativeBluetoothScannerSpec.NAME)
class BluetoothScannerModule(reactContext: ReactApplicationContext) :
    NativeBluetoothScannerSpec(reactContext) {

  private val searching = AtomicBoolean(false)

  private val adapter: android.bluetooth.BluetoothAdapter?
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

  private fun hasScanPermission(): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Manifest.permission.BLUETOOTH_SCAN
    } else {
      Manifest.permission.ACCESS_FINE_LOCATION
    }
    return reactApplicationContext.checkSelfPermission(permission) ==
        PackageManager.PERMISSION_GRANTED
  }

  override fun invalidate() {
    stopBluetoothSearch()
    super.invalidate()
  }
}
