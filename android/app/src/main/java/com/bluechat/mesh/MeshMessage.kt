package com.bluechat.mesh

data class MeshMessage(
    val id: String,
    val src: String,
    val dst: String,
    val text: String,
)
