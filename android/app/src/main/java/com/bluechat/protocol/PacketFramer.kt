package com.bluechat.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PacketFramer {
  private val buffer = ByteArrayOutputStream()

  fun frame(payload: ByteArray): ByteArray =
      ByteBuffer.allocate(4 + payload.size)
          .order(ByteOrder.BIG_ENDIAN)
          .putInt(payload.size)
          .put(payload)
          .array()

  fun chunk(framed: ByteArray, chunkSize: Int): List<ByteArray> {
    if (chunkSize <= 0) {
      return listOf(framed)
    }
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < framed.size) {
      val end = minOf(offset + chunkSize, framed.size)
      chunks.add(framed.copyOfRange(offset, end))
      offset = end
    }
    return chunks
  }

  fun append(bytes: ByteArray): List<ByteArray> {
    if (bytes.isEmpty()) {
      return emptyList()
    }
    val payloads = mutableListOf<ByteArray>()
    synchronized(buffer) {
      buffer.write(bytes, 0, bytes.size)
      val data = buffer.toByteArray()
      var offset = 0
      while (data.size - offset >= 4) {
        val length = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
        if (length < 0 || length > MAX_PACKET_SIZE) {
          buffer.reset()
          return emptyList()
        }
        if (data.size - offset - 4 < length) {
          break
        }
        payloads.add(data.copyOfRange(offset + 4, offset + 4 + length))
        offset += 4 + length
      }
      buffer.reset()
      if (offset < data.size) {
        buffer.write(data, offset, data.size - offset)
      }
    }
    return payloads
  }

  fun reset() {
    synchronized(buffer) { buffer.reset() }
  }

  companion object {
    private const val MAX_PACKET_SIZE = 1_000_000
  }
}
