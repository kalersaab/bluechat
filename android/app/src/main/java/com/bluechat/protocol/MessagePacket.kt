package com.bluechat.protocol

data class MessagePacket(
    val messageId: String,
    val timestamp: Long,
    val text: String,
) : Packet {
  override val type: PacketType = PacketType.MESSAGE
}
