package com.bluechat.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketDecoder {
  fun decode(bytes: ByteArray): Packet? {
    if (bytes.isEmpty()) {
      return null
    }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val type = PacketType.fromValue(buffer.get().toInt()) ?: return null
    return try {
      when (type) {
        PacketType.MESSAGE -> {
          val messageId = readString(buffer) ?: return null
          if (buffer.remaining() < 8) {
            return null
          }
          val timestamp = buffer.long
          val text = readString(buffer) ?: return null
          MessagePacket(messageId, timestamp, text)
        }
        PacketType.ACK -> {
          val messageId = readString(buffer) ?: return null
          if (buffer.remaining() < 8) {
            return null
          }
          val timestamp = buffer.long
          AckPacket(messageId, timestamp)
        }
      }
    } catch (exception: Exception) {
      null
    }
  }

  private fun readString(buffer: ByteBuffer): String? {
    if (buffer.remaining() < 2) {
      return null
    }
    val high = buffer.get().toInt() and 0xFF
    val low = buffer.get().toInt() and 0xFF
    val length = (high shl 8) or low
    if (buffer.remaining() < length) {
      return null
    }
    val bytes = ByteArray(length)
    buffer.get(bytes)
    return String(bytes, Charsets.UTF_8)
  }
}
