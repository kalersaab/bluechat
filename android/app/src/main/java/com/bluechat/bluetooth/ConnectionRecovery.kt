package com.bluechat.bluetooth

class ConnectionRecovery {
  private val lastDeviceIds = LinkedHashSet<String>(10)

  fun recordDevice(deviceId: String) {
    synchronized(lastDeviceIds) {
      lastDeviceIds.add(deviceId)
      if (lastDeviceIds.size > 5) {
        val first = lastDeviceIds.firstOrNull()
        if (first != null) {
          lastDeviceIds.remove(first)
        }
      }
    }
  }

  fun getLastDevices(): List<String> = synchronized(lastDeviceIds) { lastDeviceIds.toList() }
}
