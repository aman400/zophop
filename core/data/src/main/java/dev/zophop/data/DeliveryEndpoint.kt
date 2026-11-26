package dev.zophop.data

import java.util.UUID

data class DeliveryEndpoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Primary Server",
    val serverType: String = ServerType.NTFY,
    val serverUrl: String = "",
    val topic: String = "",
    val token: String = "",
    val isEnabled: Boolean = true,
)
