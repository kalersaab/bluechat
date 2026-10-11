package com.bluechat.bluetooth

import com.bluechat.protocol.MessagePacket

data class PendingSend(
    val deviceId: String,
    val packet: MessagePacket,
    val retryCount: Int = 0,
)
