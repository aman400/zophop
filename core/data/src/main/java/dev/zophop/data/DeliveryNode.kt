package dev.zophop.data

import java.util.UUID

data class DeliveryNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Primary Target",
    val serverType: String = ServerType.NTFY,
    val serverUrl: String = "",
    val topic: String = "",
    val token: String = "",
    val isEnabled: Boolean = true,
    // Node-level Pre-Checks
    val requireInternet: Boolean = true,
    val requireVpn: Boolean = false,
    val requireHealthcheck: Boolean = false,
    val customHealthcheckUrl: String = "",
    val healthcheckMethod: String = "GET",
    val healthcheckHeaders: String = "",
    val healthcheckExpectedStatus: Int = 200,
)
