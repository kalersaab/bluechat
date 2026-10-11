package com.bluechat.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.facebook.react.bridge.ReactApplicationContext

class BleClient(
    private val context: ReactApplicationContext,
    private val listener: Listener,
) {
  interface Listener {
    fun onBytes(deviceId: String, bytes: ByteArray)
    fun onConnectionStateChanged(state: ConnectionState)
    fun onWriteComplete(deviceId: String)
  }

  private var gatt: BluetoothGatt? = null
  private var rxCharacteristic: BluetoothGattCharacteristic? = null
  private var txCharacteristic: BluetoothGattCharacteristic? = null

  @Volatile var chunkSize: Int = 20
    private set

  val connectedDeviceId: String?
    get() = gatt?.device?.address

  private val adapter: BluetoothAdapter?
    get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private fun hasConnectPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      return true
    }
    return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
  }

  fun connect(deviceId: String) {
    val device = try {
      adapter?.getRemoteDevice(deviceId)
    } catch (exception: IllegalArgumentException) {
      null
    } ?: return

    disconnect()
    chunkSize = 20
    gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
  }

  fun disconnect() {
    gatt?.let { client ->
      try {
        client.disconnect()
        client.close()
      } catch (exception: SecurityException) {
      }
    }
    gatt = null
    rxCharacteristic = null
    txCharacteristic = null
  }

  fun write(deviceId: String, chunk: ByteArray): Boolean {
    val client = gatt ?: return false
    val characteristic = rxCharacteristic ?: return false
    if (client.device?.address != deviceId) {
      return false
    }
    characteristic.value = chunk
    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    return try {
      client.writeCharacteristic(characteristic)
    } catch (exception: SecurityException) {
      false
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
        return
      }
      if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        val address = client.device?.address ?: ""
        try {
          client.close()
        } catch (exception: SecurityException) {
        }
        if (gatt === client) {
          gatt = null
          rxCharacteristic = null
          txCharacteristic = null
        }
        listener.onConnectionStateChanged(ConnectionState(address, false))
      }
    }

    override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
      chunkSize = maxOf(20, mtu - 3)
    }

    override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
      val service = client.getService(BleManager.UUID_SERVICE) ?: return
      rxCharacteristic = service.getCharacteristic(BleManager.UUID_RX)
      txCharacteristic = service.getCharacteristic(BleManager.UUID_TX)

      val tx = txCharacteristic
      if (tx != null) {
        try {
          client.setCharacteristicNotification(tx, true)
          val descriptor = tx.getDescriptor(BleManager.UUID_CCCD)
          if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            client.writeDescriptor(descriptor)
          }
        } catch (exception: SecurityException) {
        }
      }
      listener.onConnectionStateChanged(ConnectionState(client.device?.address ?: "", true))
    }

    override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      if (characteristic.uuid == BleManager.UUID_TX) {
        val value = characteristic.value
        if (value != null && value.isNotEmpty()) {
          listener.onBytes(client.device?.address ?: "", value)
        }
      }
    }

    override fun onCharacteristicWrite(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int) {
      listener.onWriteComplete(client.device?.address ?: "")
    }
  }
}
