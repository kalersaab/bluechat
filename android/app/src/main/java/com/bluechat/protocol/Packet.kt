package com.bluechat.protocol

sealed interface Packet {
  val type: PacketType
}
