package com.bluechat.mesh

enum class MeshPacketType(val value: Int) {
  DATA(1),
  PING(2),
  PONG(3),
  ROUTE_REQUEST(4),
  ROUTE_REPLY(5),
  BROADCAST(6);

  companion object {
    fun fromValue(value: Int): MeshPacketType? = entries.firstOrNull { it.value == value }
  }
}
