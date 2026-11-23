package dev.zophop.domain.model

enum class PreconditionType {
    INTERNET_CHECK,
    VPN_CHECK,
    HEALTH_CHECK,
    SIM_CHECK,
    GPS_CHECK,
    BATTERY_CHECK,
    CHAINED_CHECK,
}

sealed class Precondition(
    val type: PreconditionType,
    open val id: String = "",
    open val isEnabled: Boolean = true,
    open val onFailureAction: String = "FAIL_IMMEDIATELY", // "FAIL_IMMEDIATELY" or "RETRY"
    open val maxRetries: Int = 3,
    open val retryIntervalSeconds: Int = 30,
    open val showNotificationOnFailure: Boolean = false,
) {
    data class InternetCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.INTERNET_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            showNotificationOnFailure,
        )

    data class VpnCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.VPN_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            showNotificationOnFailure,
        )

    data class HealthCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        val targetUrl: String = "",
        val method: String = "GET",
        val headers: String = "",
        val expectedStatus: Int = 200,
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.HEALTH_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            showNotificationOnFailure,
        )

    data class SimCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        val requiredCarrier: String = "",
        val requiredSlot: Int = -1,
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.SIM_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            showNotificationOnFailure,
        )

    data class GpsCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        val requireGpsEnabled: Boolean = true,
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.GPS_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            showNotificationOnFailure,
        )

    data class BatteryCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        val minBatteryPercent: Int = 15,
        val requireCharging: Boolean = false,
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.BATTERY_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            showNotificationOnFailure,
        )

    data class ChainCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        val nodes: List<WorkflowNode> = emptyList(),
        val connections: List<WorkflowConnection> = emptyList(),
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.CHAINED_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            showNotificationOnFailure,
        )
}
