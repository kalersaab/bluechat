package com.bluechat.bluetooth

import com.bluechat.protocol.PacketFramer

class DeviceConnection(val deviceId: String) {
  val reassembler = PacketFramer()
  val outgoing = ArrayDeque<ByteArray>()
  @Volatile var writeInProgress = false
}
