package com.bluechat.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketEncoder {
  fun encode(packet: Packet): ByteArray {
    val output = ByteArrayOutputStream()
    output.write(packet.type.value)
    when (packet) {
      is MessagePacket -> {
        writeString(output, packet.messageId)
        writeLong(output, packet.timestamp)
        writeString(output, packet.text)
      }
      is AckPacket -> {
        writeString(output, packet.messageId)
        writeLong(output, packet.timestamp)
      }
    }
    return output.toByteArray()
  }

  private fun writeString(output: ByteArrayOutputStream, value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    output.write((bytes.size ushr 8) and 0xFF)
    output.write(bytes.size and 0xFF)
    output.write(bytes, 0, bytes.size)
  }

  private fun writeLong(output: ByteArrayOutputStream, value: Long) {
    val bytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
    output.write(bytes, 0, bytes.size)
  }
}
