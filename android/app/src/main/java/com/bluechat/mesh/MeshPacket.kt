package com.bluechat.mesh

data class MeshPacket(
    val type: MeshPacketType,
    val src: String,
    val dst: String,
    val ttl: Int,
    val messageId: String,
    val payload: ByteArray,
)
