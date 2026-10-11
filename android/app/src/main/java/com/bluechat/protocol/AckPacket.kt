package com.bluechat.protocol

data class AckPacket(
    val messageId: String,
    val timestamp: Long,
) : Packet {
  override val type: PacketType = PacketType.ACK
}
