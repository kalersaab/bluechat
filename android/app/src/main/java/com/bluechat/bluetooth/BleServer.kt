package com.bluechat.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.facebook.react.bridge.ReactApplicationContext

class BleServer(
    private val context: ReactApplicationContext,
    private val listener: Listener,
) {
  interface Listener {
    fun onBytes(deviceId: String, bytes: ByteArray)
    fun onConnectionStateChanged(state: ConnectionState)
  }

  private var gattServer: BluetoothGattServer? = null
  private var txCharacteristic: BluetoothGattCharacteristic? = null
  private var subscribedDevice: BluetoothDevice? = null
  private var advertiser: BluetoothLeAdvertiser? = null

  val subscribedDeviceId: String?
    get() = subscribedDevice?.address

  private val adapter: BluetoothAdapter?
    get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private fun hasConnectPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return true
    }
    return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
  }

  private fun hasAdvertisePermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return true
    }
    return context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ==
        PackageManager.PERMISSION_GRANTED
  }

  fun ensureStarted() {
    if (gattServer != null || !hasConnectPermission()) {
      return
    }
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return

    val rx = BluetoothGattCharacteristic(
        BleManager.UUID_RX,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE)
    val tx = BluetoothGattCharacteristic(
        BleManager.UUID_TX,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ)
    tx.addDescriptor(BluetoothGattDescriptor(
        BleManager.UUID_CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))

    val service = BluetoothGattService(BleManager.UUID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    service.addCharacteristic(rx)
    service.addCharacteristic(tx)

    val server = try {
      manager.openGattServer(context, serverCallback)
    } catch (exception: SecurityException) {
      null
    }
    server?.addService(service)
    txCharacteristic = tx
    gattServer = server
  }

  fun advertise() {
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
        .addServiceUuid(ParcelUuid(BleManager.UUID_SERVICE))
        .build()

    try {
      bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
    } catch (exception: SecurityException) {
    }
  }

  fun notify(deviceId: String, chunk: ByteArray): Boolean {
    val device = subscribedDevice ?: return false
    val characteristic = txCharacteristic ?: return false
    val server = gattServer ?: return false
    if (device.address != deviceId) {
      return false
    }
    characteristic.value = chunk
    return try {
      server.notifyCharacteristicChanged(device, characteristic, false)
    } catch (exception: SecurityException) {
      false
    }
  }

  fun cancelConnection(deviceId: String) {
    val server = gattServer ?: return
    val device = try {
      adapter?.getRemoteDevice(deviceId)
    } catch (exception: IllegalArgumentException) {
      null
    } ?: return
    try {
      server.cancelConnection(device)
    } catch (exception: SecurityException) {
    }
    subscribedDevice = null
  }

  fun close() {
    try {
      advertiser?.stopAdvertising(advertiseCallback)
    } catch (exception: SecurityException) {
    }
    advertiser = null
    try {
      gattServer?.close()
    } catch (exception: SecurityException) {
    }
    gattServer = null
    txCharacteristic = null
    subscribedDevice = null
  }

  private val advertiseCallback = object : AdvertiseCallback() {}

  private val serverCallback = object : BluetoothGattServerCallback() {
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_DISCONNECTED && subscribedDevice?.address == device.address) {
        subscribedDevice = null
        listener.onConnectionStateChanged(ConnectionState(device.address, false))
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
      if (characteristic.uuid == BleManager.UUID_RX) {
        if (responseNeeded) {
          try {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
          } catch (exception: SecurityException) {
          }
        }
        if (value != null && value.isNotEmpty()) {
          listener.onBytes(device.address, value)
        }
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
      if (descriptor.uuid == BleManager.UUID_CCCD &&
          descriptor.characteristic.uuid == BleManager.UUID_TX &&
          value != null) {
        val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        subscribedDevice = if (enabled) device else null
        listener.onConnectionStateChanged(ConnectionState(device.address, enabled))
      }
      if (responseNeeded) {
        try {
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        } catch (exception: SecurityException) {
        }
      }
    }
  }
}
