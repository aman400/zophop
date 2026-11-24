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
    const val LINEAR = "LINEAR"
    const val EXPONENTIAL = "EXPONENTIAL"
    const val RANDOMIZED = "RANDOMIZED"
}

/**
 * Calculates the retry delay in seconds based on the selected backoff strategy.
 *
 * - FIXED: Constant delay of baseIntervalSeconds on every attempt.
 * - LINEAR: Linearly increasing delay: baseIntervalSeconds * attempt, capped at maxCapSeconds.
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
    val cap = maxCapSeconds.coerceAtLeast(1)
    val base = baseIntervalSeconds.coerceAtLeast(1)
    return when (strategy.uppercase().trim()) {
        RetryStrategy.EXPONENTIAL -> {
            val shift = (attempt - 1).coerceIn(0, 10)
            (base.toLong() * (1 shl shift)).coerceIn(1L, cap.toLong()).toInt()
        }
        RetryStrategy.LINEAR -> {
            val att = attempt.coerceAtLeast(1)
            (base.toLong() * att).coerceIn(1L, cap.toLong()).toInt()
        }
        RetryStrategy.RANDOMIZED -> {
            val shift = (attempt - 1).coerceIn(0, 10)
            val ceiling = (base.toLong() * (1 shl shift)).coerceIn(1L, cap.toLong()).toInt()
            randomGenerator(1, ceiling).coerceIn(1, cap)
        }
        else -> base.coerceIn(1, cap) // FIXED
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
    open val name: String get() = ""
    open val icon: String get() = ""

    data class InternetCheck(
        override val id: String = "",
        override val isEnabled: Boolean = true,
        override val icon: String = "",
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
        override val icon: String = "",
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
        override val icon: String = "",
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
        override val icon: String = "",
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
        override val icon: String = "",
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
        override val icon: String = "",
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
        override val name: String = "",
        override val isEnabled: Boolean = true,
        override val icon: String = "",
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
