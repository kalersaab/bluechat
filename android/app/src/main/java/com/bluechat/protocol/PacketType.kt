package com.bluechat.protocol

enum class PacketType(val value: Int) {
  MESSAGE(1),
  ACK(2);

  companion object {
    fun fromValue(value: Int): PacketType? = entries.firstOrNull { it.value == value }
  }
}
