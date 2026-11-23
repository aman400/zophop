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

object PreconditionFailurePolicy {
    const val FAIL_IMMEDIATELY = "FAIL_IMMEDIATELY"
    const val RETRY = "RETRY"
    const val QUEUE = "QUEUE"
}

object RetryStrategy {
    const val FIXED = "FIXED"
    const val EXPONENTIAL = "EXPONENTIAL"
    const val RANDOMIZED = "RANDOMIZED"
}

/**
 * Calculates the retry delay in seconds based on the selected backoff strategy.
 *
 * - FIXED: Constant delay of baseIntervalSeconds on every attempt.
 * - EXPONENTIAL: baseIntervalSeconds * 2^(attempt - 1), capped at maxCapSeconds.
 * - RANDOMIZED: Full-jitter delay randomly chosen between 1 and the exponential ceiling for that attempt.
 */
fun calculateRetryDelaySeconds(
    strategy: String,
    baseIntervalSeconds: Int,
    attempt: Int,
    maxCapSeconds: Int = 3600,
    randomGenerator: (min: Int, max: Int) -> Int = { min, max ->
        if (min >= max) min else kotlin.random.Random.nextInt(min, max + 1)
    },
): Int {
    val base = baseIntervalSeconds.coerceAtLeast(1)
    return when (strategy.uppercase().trim()) {
        RetryStrategy.EXPONENTIAL -> {
            val shift = (attempt - 1).coerceIn(0, 10)
            (base * (1 shl shift)).coerceIn(1, maxCapSeconds)
        }
        RetryStrategy.RANDOMIZED -> {
            val shift = (attempt - 1).coerceIn(0, 10)
            val ceiling = (base * (1 shl shift)).coerceIn(1, maxCapSeconds)
            randomGenerator(1, ceiling)
        }
        else -> base.coerceIn(1, maxCapSeconds) // FIXED
    }
}

sealed class Precondition(
    val type: PreconditionType,
    open val id: String = "",
    open val isEnabled: Boolean = true,
    open val onFailureAction: String = PreconditionFailurePolicy.FAIL_IMMEDIATELY, // "FAIL_IMMEDIATELY", "RETRY", or "QUEUE"
    open val maxRetries: Int = 3,
    open val retryIntervalSeconds: Int = 30,
    open val retryStrategy: String = RetryStrategy.FIXED, // "FIXED", "EXPONENTIAL", or "RANDOMIZED"
    open val queueTimeoutMinutes: Int = 10,
    open val showNotificationOnFailure: Boolean = false,
) {
    data class InternetCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val retryStrategy: String = RetryStrategy.FIXED,
        override val queueTimeoutMinutes: Int = 10,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.INTERNET_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            retryStrategy,
            queueTimeoutMinutes,
            showNotificationOnFailure,
        )

    data class VpnCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val retryStrategy: String = RetryStrategy.FIXED,
        override val queueTimeoutMinutes: Int = 10,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.VPN_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            retryStrategy,
            queueTimeoutMinutes,
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
        override val retryStrategy: String = RetryStrategy.FIXED,
        override val queueTimeoutMinutes: Int = 10,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.HEALTH_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            retryStrategy,
            queueTimeoutMinutes,
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
        override val retryStrategy: String = RetryStrategy.FIXED,
        override val queueTimeoutMinutes: Int = 10,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.SIM_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            retryStrategy,
            queueTimeoutMinutes,
            showNotificationOnFailure,
        )

    data class GpsCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        val requireGpsEnabled: Boolean = true,
        override val onFailureAction: String = "FAIL_IMMEDIATELY",
        override val maxRetries: Int = 3,
        override val retryIntervalSeconds: Int = 30,
        override val retryStrategy: String = RetryStrategy.FIXED,
        override val queueTimeoutMinutes: Int = 10,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.GPS_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            retryStrategy,
            queueTimeoutMinutes,
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
        override val retryStrategy: String = RetryStrategy.FIXED,
        override val queueTimeoutMinutes: Int = 10,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.BATTERY_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            retryStrategy,
            queueTimeoutMinutes,
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
        override val retryStrategy: String = RetryStrategy.FIXED,
        override val queueTimeoutMinutes: Int = 10,
        override val showNotificationOnFailure: Boolean = false,
    ) : Precondition(
            PreconditionType.CHAINED_CHECK,
            id,
            isEnabled,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            retryStrategy,
            queueTimeoutMinutes,
            showNotificationOnFailure,
        )
}
