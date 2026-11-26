package dev.zophop.domain.engine

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.engine.plugin.CoreEngineNodesModule
import dev.zophop.domain.engine.plugin.ExternalPluginRegistry
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.calculateRetryDelaySeconds
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.ExecutionLogFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

sealed class PreconditionResult {
    data class Passed(
        val steps: List<NodeExecutionStep> = emptyList(),
    ) : PreconditionResult()

    data class Failed(
        val reason: String,
        val steps: List<NodeExecutionStep> = emptyList(),
        val shouldQueue: Boolean = false,
        val isQueueTimeout: Boolean = false,
    ) : PreconditionResult()
}

open class PreconditionEvaluator(
    private val context: Context?,
    private val okHttpClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build(),
    private val delayDuration: suspend (kotlin.time.Duration) -> Unit = { kotlinx.coroutines.delay(it) },
    private val randomGenerator: (min: Int, max: Int) -> Int = { min, max ->
        if (min >= max) min else kotlin.random.Random.nextInt(min, max + 1)
    },
    private val credentialRepository: CredentialRepository? = null,
    private val currentTimeMs: () -> Long = { System.currentTimeMillis() },
) {
    open suspend fun evaluateAll(
        preconditions: List<Precondition>,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)? = null,
    ): PreconditionResult = evaluateAllInternal(preconditions, emptySet(), onStepUpdate, queueDeadlineMs = null)

    internal suspend fun evaluateAllWithDeadline(
        preconditions: List<Precondition>,
        queueDeadlineMs: Long?,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)? = null,
    ): PreconditionResult = evaluateAllInternal(preconditions, emptySet(), onStepUpdate, queueDeadlineMs = queueDeadlineMs)

    internal suspend fun evaluateAllInternal(
        preconditions: List<Precondition>,
        callStack: Set<String>,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)? = null,
        queueDeadlineMs: Long? = null,
    ): PreconditionResult =
        withContext(Dispatchers.IO) {
            val accumulatedSteps = mutableListOf<NodeExecutionStep>()
            for (precondition in preconditions) {
                val result =
                    evaluateInternal(precondition, callStack, onStepUpdate = { precSteps ->
                        val liveList = accumulatedSteps + precSteps
                        onStepUpdate?.invoke(liveList)
                    }, queueDeadlineMs = queueDeadlineMs)
                when (result) {
                    is PreconditionResult.Passed -> {
                        accumulatedSteps.addAll(result.steps)
                        onStepUpdate?.invoke(accumulatedSteps.toList())
                    }
                    is PreconditionResult.Failed -> {
                        accumulatedSteps.addAll(result.steps)
                        onStepUpdate?.invoke(accumulatedSteps.toList())
                        return@withContext PreconditionResult.Failed(
                            reason = result.reason,
                            steps = accumulatedSteps.toList(),
                            shouldQueue = result.shouldQueue,
                            isQueueTimeout = result.isQueueTimeout,
                        )
                    }
                }
            }
            PreconditionResult.Passed(steps = accumulatedSteps.toList())
        }

    open suspend fun evaluate(
        precondition: Precondition,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)? = null,
    ): PreconditionResult = evaluateInternal(precondition, emptySet(), onStepUpdate, queueDeadlineMs = null)

    internal suspend fun evaluateInternal(
        precondition: Precondition,
        callStack: Set<String>,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)? = null,
        queueDeadlineMs: Long? = null,
    ): PreconditionResult =
        withContext(Dispatchers.IO) {
            if (!precondition.isEnabled) {
                val skippedStep =
                    NodeExecutionStep(
                        nodeId = precondition.id.ifBlank { "prec_skipped" },
                        nodeName = "Precondition Disabled",
                        nodeType = CoreEngineNodesModule.HTTP_ACTION,
                        state = NodeStepState.SKIPPED,
                        logMessage = "Precondition is disabled, skipping check.",
                    )
                onStepUpdate?.invoke(listOf(skippedStep))
                return@withContext PreconditionResult.Passed(steps = listOf(skippedStep))
            }

            val precKey = if (precondition.id.isNotBlank()) precondition.id else "${precondition.type}_${precondition.hashCode()}"
            if (callStack.contains(precKey) || callStack.size > 20) {
                val loopStep =
                    NodeExecutionStep(
                        nodeId = precondition.id.ifBlank { "circular_loop" },
                        nodeName = "Circular Precondition Loop",
                        nodeType = CoreEngineNodesModule.CONDITION,
                        state = NodeStepState.FAILED,
                        logMessage = "Circular precondition loop detected for '$precKey'",
                        errorReason = "Circular precondition loop detected",
                    )
                onStepUpdate?.invoke(listOf(loopStep))
                return@withContext PreconditionResult.Failed("Circular precondition loop detected", steps = listOf(loopStep))
            }

            val nextStack = callStack + precKey
            var result = evaluateSingleAttempt(precondition, nextStack, onStepUpdate)

            val isQueuePolicy = precondition.onFailureAction.trim().contains("QUEUE", ignoreCase = true)
            val isRetryPolicy = precondition.onFailureAction.trim().contains("RETRY", ignoreCase = true)
            if (result is PreconditionResult.Failed && isRetryPolicy && precondition.maxRetries > 0) {
                result = evaluateRetryPolicy(precondition, nextStack, result, onStepUpdate)
            } else if (result is PreconditionResult.Failed && isQueuePolicy) {
                result = evaluateQueuePolicy(precondition, nextStack, result, onStepUpdate, queueDeadlineMs)
            }

            result
        }

    private suspend fun evaluateRetryPolicy(
        precondition: Precondition,
        callStack: Set<String>,
        initialFailedResult: PreconditionResult.Failed,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)?,
    ): PreconditionResult {
        val maxRetries = precondition.maxRetries
        val baseIntervalSec = precondition.retryIntervalSeconds.coerceAtLeast(1)
        val historySteps = initialFailedResult.steps.toMutableList()
        var lastResult: PreconditionResult = initialFailedResult

        for (attempt in 1..maxRetries) {
            currentCoroutineContext().ensureActive()

            val intervalSec =
                calculateRetryDelaySeconds(
                    strategy = precondition.retryStrategy,
                    baseIntervalSeconds = baseIntervalSec,
                    attempt = attempt,
                    randomGenerator = randomGenerator,
                )

            for (sec in intervalSec downTo 1) {
                currentCoroutineContext().ensureActive()
                val retryNoticeStep =
                    NodeExecutionStep(
                        nodeId = "retry_delay_$attempt",
                        nodeName = "⏳ Retrying ($attempt/$maxRetries)",
                        nodeType = CoreEngineNodesModule.HTTP_ACTION,
                        state = NodeStepState.EXECUTING,
                        logMessage = "Precondition check failed (${precondition.retryStrategy} backoff). Waiting ${sec}s remaining before attempt #${attempt + 1}/${maxRetries + 1}...",
                    )
                val currentProgress = historySteps + retryNoticeStep
                onStepUpdate?.invoke(currentProgress)

                delayDuration(1000L.milliseconds)
            }

            currentCoroutineContext().ensureActive()

            val retryResult =
                evaluateSingleAttempt(precondition, callStack) { attemptSteps ->
                    val currentList = historySteps + attemptSteps
                    onStepUpdate?.invoke(currentList)
                }

            if (retryResult is PreconditionResult.Passed) {
                historySteps.addAll(retryResult.steps)
                lastResult = PreconditionResult.Passed(steps = historySteps.toList())
                onStepUpdate?.invoke(historySteps.toList())
                return lastResult
            } else if (retryResult is PreconditionResult.Failed) {
                val failedReason = retryResult.reason
                historySteps.addAll(retryResult.steps)

                lastResult =
                    PreconditionResult.Failed(
                        reason = if (attempt == maxRetries) "$failedReason (Failed after $maxRetries retries)" else failedReason,
                        steps = historySteps.toList(),
                    )
                onStepUpdate?.invoke(historySteps.toList())
            }
        }
        return lastResult
    }

    private suspend fun evaluateQueuePolicy(
        precondition: Precondition,
        callStack: Set<String>,
        initialFailedResult: PreconditionResult.Failed,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)?,
        queueDeadlineMs: Long? = null,
    ): PreconditionResult {
        val timeoutSeconds = precondition.queueTimeoutMinutes.coerceAtLeast(1) * 60
        val startWallMs = currentTimeMs()
        val deadlineWallMs = queueDeadlineMs ?: (startWallMs + timeoutSeconds * 1000L)
        val effectiveTimeoutSeconds = ((deadlineWallMs - startWallMs + 999L) / 1000L).toInt().coerceAtLeast(0)
        val baseIntervalSec = precondition.retryIntervalSeconds.coerceAtLeast(1)
        val historySteps = initialFailedResult.steps.toMutableList()

        var elapsedSeconds = 0
        var attempt = 1
        var lastFailReason = initialFailedResult.reason
        var passedResult: PreconditionResult.Passed? = null
        var isQueueActive = true

        while (elapsedSeconds < effectiveTimeoutSeconds && currentTimeMs() < deadlineWallMs && isQueueActive) {
            currentCoroutineContext().ensureActive()

            val remainingWallSec = ((deadlineWallMs - currentTimeMs() + 999L) / 1000L).toInt().coerceAtLeast(1)
            val remainingTimeoutSec = minOf(effectiveTimeoutSeconds - elapsedSeconds, remainingWallSec).coerceAtLeast(1)
            val nextDelaySec =
                calculateRetryDelaySeconds(
                    strategy = precondition.retryStrategy,
                    baseIntervalSeconds = baseIntervalSec,
                    attempt = attempt,
                    maxCapSeconds = remainingTimeoutSec,
                    randomGenerator = randomGenerator,
                ).coerceAtMost(remainingTimeoutSec)

            val earlyPass =
                countdownWithProbe(
                    precondition = precondition,
                    callStack = callStack,
                    historySteps = historySteps,
                    nextDelaySec = nextDelaySec,
                    elapsedSeconds = elapsedSeconds,
                    deadlineWallMs = deadlineWallMs,
                    attempt = attempt,
                    lastFailReason = lastFailReason,
                    onStepUpdate = onStepUpdate,
                )
            if (earlyPass != null) {
                passedResult = earlyPass
                isQueueActive = false
            } else if (currentTimeMs() >= deadlineWallMs) {
                elapsedSeconds += nextDelaySec
                attempt++
                isQueueActive = false
            } else {
                elapsedSeconds += nextDelaySec
                attempt++
                currentCoroutineContext().ensureActive()

                val retryResult = evaluateSingleAttempt(precondition, callStack) { attemptSteps ->
                    val currentList = historySteps + attemptSteps
                    onStepUpdate?.invoke(currentList)
                }
                when (retryResult) {
                    is PreconditionResult.Passed -> {
                        historySteps.addAll(retryResult.steps)
                        historySteps.add(
                            NodeExecutionStep(
                                nodeId = "queue_resolved_${precondition.id.ifBlank { precondition.type.name }}",
                                nodeName = "✅ Precondition Satisfied in Queue",
                                nodeType = CoreEngineNodesModule.CONDITION,
                                state = NodeStepState.SUCCESS,
                                logMessage = "Precondition resolved in queue after ${elapsedSeconds}s (attempt #${attempt - 1}). Resuming workflow execution.",
                            ),
                        )
                        onStepUpdate?.invoke(historySteps.toList())
                        passedResult = PreconditionResult.Passed(steps = historySteps.toList())
                        isQueueActive = false
                    }
                    is PreconditionResult.Failed -> {
                        lastFailReason = retryResult.reason
                        historySteps.addAll(retryResult.steps)
                        onStepUpdate?.invoke(historySteps.toList())
                    }
                }
            }
        }

        if (passedResult != null) {
            return passedResult
        }

        val timeoutMsg = "Precondition unsatisfied after ${precondition.queueTimeoutMinutes}m queue timeout: $lastFailReason"
        val timeoutStep =
            NodeExecutionStep(
                nodeId = "queue_timeout_${precondition.id.ifBlank { precondition.type.name }}",
                nodeName = "⚠️ Queue Timeout Exceeded",
                nodeType = CoreEngineNodesModule.CONDITION,
                state = NodeStepState.FAILED,
                logMessage = timeoutMsg,
                errorReason = timeoutMsg,
            )
        historySteps.add(timeoutStep)
        val finalFailed =
            PreconditionResult.Failed(
                reason = timeoutMsg,
                steps = historySteps.toList(),
                shouldQueue = true,
                isQueueTimeout = true,
            )
        onStepUpdate?.invoke(historySteps.toList())
        return finalFailed
    }

    private suspend fun countdownWithProbe(
        precondition: Precondition,
        callStack: Set<String>,
        historySteps: MutableList<NodeExecutionStep>,
        nextDelaySec: Int,
        elapsedSeconds: Int,
        deadlineWallMs: Long,
        attempt: Int,
        lastFailReason: String,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)?,
    ): PreconditionResult.Passed? {
        val totalTimeoutSec = ((deadlineWallMs - currentTimeMs() + 999L) / 1000L).toInt().coerceAtLeast(0) + elapsedSeconds
        var passedEarly: PreconditionResult.Passed? = null
        var sec = nextDelaySec
        while (sec >= 1 && passedEarly == null) {
            currentCoroutineContext().ensureActive()
            val wallRemainingSec = ((deadlineWallMs - currentTimeMs()) / 1000L).toInt()
            if (wallRemainingSec <= 0) break

            val currentQueueRemaining = minOf(
                (totalTimeoutSec - elapsedSeconds - (nextDelaySec - sec)).coerceAtLeast(0),
                wallRemainingSec.coerceAtLeast(0),
            )
            val remainingDesc = if (currentQueueRemaining >= 60) {
                "${currentQueueRemaining / 60}m ${currentQueueRemaining % 60}s"
            } else {
                "${currentQueueRemaining}s"
            }
            val precLabel = precondition.name.ifBlank { precondition.type.name }
            val queueNoticeStep =
                NodeExecutionStep(
                    nodeId = "queue_waiting_$attempt",
                    nodeName = "⏳ Queued (Attempt #$attempt)",
                    nodeType = CoreEngineNodesModule.CONDITION,
                    state = NodeStepState.EXECUTING,
                    logMessage = "Waiting for $precLabel ($lastFailReason). Next check in ${sec}s ($remainingDesc timeout remaining)...",
                )
            onStepUpdate?.invoke(historySteps + queueNoticeStep)

            val actualElapsed = elapsedSeconds + (nextDelaySec - sec) + 1
            val quickResult = checkLocalOsProbe(precondition, callStack, attempt, actualElapsed, historySteps, onStepUpdate)
            if (quickResult != null) {
                passedEarly = quickResult
            } else {
                delayDuration(1000L.milliseconds)
                sec--
            }
        }
        return passedEarly
    }

    private suspend fun checkLocalOsProbe(
        precondition: Precondition,
        callStack: Set<String>,
        attempt: Int,
        actualElapsed: Int,
        historySteps: MutableList<NodeExecutionStep>,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)?,
    ): PreconditionResult.Passed? {
        val hasLocalCapability = when (precondition) {
            is Precondition.InternetCheck -> isInternetAvailable()
            is Precondition.VpnCheck -> isVpnConnected()
            else -> false
        }
        if (hasLocalCapability) {
            val quickProbe = evaluateSingleAttempt(precondition, callStack)
            if (quickProbe is PreconditionResult.Passed) {
                historySteps.addAll(quickProbe.steps)
                val resolvedStep =
                    NodeExecutionStep(
                        nodeId = "queue_resolved_${precondition.id.ifBlank { precondition.type.name }}",
                        nodeName = "✅ Precondition Satisfied in Queue",
                        nodeType = CoreEngineNodesModule.CONDITION,
                        state = NodeStepState.SUCCESS,
                        logMessage = "Precondition resolved in queue after ${actualElapsed}s (attempt #$attempt). Resuming workflow execution.",
                    )
                historySteps.add(resolvedStep)
                val passed = PreconditionResult.Passed(steps = historySteps.toList())
                onStepUpdate?.invoke(historySteps.toList())
                return passed
            }
        }
        return null
    }

    private suspend fun evaluateSingleAttempt(
        precondition: Precondition,
        callStack: Set<String>,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)? = null,
    ): PreconditionResult =
        when (precondition) {
            is Precondition.InternetCheck -> {
                val isOk = !precondition.isEnabled || isInternetAvailable()
                val step =
                    NodeExecutionStep(
                        nodeId = "internet_check",
                        nodeName = "🌐 Network / Internet Check",
                        nodeType = CoreEngineNodesModule.HTTP_ACTION,
                        state = if (isOk) NodeStepState.SUCCESS else NodeStepState.FAILED,
                        logMessage = if (isOk) "Active Internet connection verified" else "Internet connection unavailable",
                        errorReason = if (!isOk) "Internet connection unavailable" else null,
                    )
                onStepUpdate?.invoke(listOf(step))
                if (isOk) {
                    PreconditionResult.Passed(steps = listOf(step))
                } else {
                    PreconditionResult.Failed("Internet connection unavailable", steps = listOf(step))
                }
            }
            is Precondition.VpnCheck -> {
                val isOk = !precondition.isEnabled || isVpnConnected()
                val step =
                    NodeExecutionStep(
                        nodeId = "vpn_check",
                        nodeName = "🔐 VPN Connection Check",
                        nodeType = CoreEngineNodesModule.HTTP_ACTION,
                        state = if (isOk) NodeStepState.SUCCESS else NodeStepState.FAILED,
                        logMessage = if (isOk) "Active VPN connection verified (Tailscale/WireGuard/OpenVPN)" else "VPN connection required (Awaiting Tailscale/WireGuard/VPN)",
                        errorReason = if (!isOk) "VPN connection required (Awaiting Tailscale/WireGuard/VPN)" else null,
                    )
                onStepUpdate?.invoke(listOf(step))
                if (isOk) {
                    PreconditionResult.Passed(steps = listOf(step))
                } else {
                    PreconditionResult.Failed("VPN connection required (Awaiting Tailscale/WireGuard/VPN)", steps = listOf(step))
                }
            }
            is Precondition.HealthCheck -> {
                if (!precondition.isEnabled) {
                    val step =
                        NodeExecutionStep(
                            nodeId = "health_check",
                            nodeName = "🩺 HTTP Healthcheck",
                            nodeType = CoreEngineNodesModule.HTTP_ACTION,
                            state = NodeStepState.SUCCESS,
                            logMessage = "Healthcheck disabled, skipping.",
                        )
                    onStepUpdate?.invoke(listOf(step))
                    PreconditionResult.Passed(steps = listOf(step))
                } else {
                    val res = checkHealth(precondition)
                    val isOk = res is PreconditionResult.Passed
                    val failReason = (res as? PreconditionResult.Failed)?.reason
                    val safeUrl = ExecutionLogFormatter.redactUrl(precondition.targetUrl)
                    val step =
                        NodeExecutionStep(
                            nodeId = "health_check",
                            nodeName = "🩺 HTTP Healthcheck (${precondition.method} $safeUrl)",
                            nodeType = CoreEngineNodesModule.HTTP_ACTION,
                            state = if (isOk) NodeStepState.SUCCESS else NodeStepState.FAILED,
                            logMessage =
                                if (isOk) {
                                    "HTTP ${precondition.method} -> $safeUrl returned expected status ${precondition.expectedStatus}"
                                } else {
                                    (
                                        failReason
                                            ?: "Healthcheck failed"
                                    )
                                },
                            errorReason = failReason,
                        )
                    onStepUpdate?.invoke(listOf(step))
                    if (isOk) {
                        PreconditionResult.Passed(steps = listOf(step))
                    } else {
                        PreconditionResult.Failed(
                            failReason ?: "Healthcheck failed",
                            steps = listOf(step),
                        )
                    }
                }
            }
            is Precondition.SimCheck -> {
                if (!precondition.isEnabled) {
                    val step =
                        NodeExecutionStep(
                            nodeId = "sim_check",
                            nodeName = "📱 SIM Card Active Check",
                            nodeType = CoreEngineNodesModule.CONDITION,
                            state = NodeStepState.SUCCESS,
                            logMessage = "SIM check disabled, skipping.",
                        )
                    onStepUpdate?.invoke(listOf(step))
                    PreconditionResult.Passed(steps = listOf(step))
                } else {
                    val res = checkSim(precondition)
                    val isOk = res is PreconditionResult.Passed
                    val failReason = (res as? PreconditionResult.Failed)?.reason
                    val step =
                        NodeExecutionStep(
                            nodeId = "sim_check",
                            nodeName = "📱 SIM Card Active Check",
                            nodeType = CoreEngineNodesModule.CONDITION,
                            state = if (isOk) NodeStepState.SUCCESS else NodeStepState.FAILED,
                            logMessage =
                                if (isOk) {
                                    "Active SIM card verified${if (precondition.requiredCarrier.isNotBlank()) " (Carrier: ${precondition.requiredCarrier})" else ""}"
                                } else {
                                    (
                                        failReason
                                            ?: "SIM check failed"
                                    )
                                },
                            errorReason = failReason,
                        )
                    onStepUpdate?.invoke(listOf(step))
                    if (isOk) {
                        PreconditionResult.Passed(steps = listOf(step))
                    } else {
                        PreconditionResult.Failed(
                            failReason ?: "SIM check failed",
                            steps = listOf(step),
                        )
                    }
                }
            }
            is Precondition.GpsCheck -> {
                if (!precondition.isEnabled) {
                    val step =
                        NodeExecutionStep(
                            nodeId = "gps_check",
                            nodeName = "📍 GPS / Location Check",
                            nodeType = CoreEngineNodesModule.CONDITION,
                            state = NodeStepState.SUCCESS,
                            logMessage = "GPS check disabled, skipping.",
                        )
                    onStepUpdate?.invoke(listOf(step))
                    PreconditionResult.Passed(steps = listOf(step))
                } else {
                    val res = checkGps(precondition)
                    val isOk = res is PreconditionResult.Passed
                    val failReason = (res as? PreconditionResult.Failed)?.reason
                    val step =
                        NodeExecutionStep(
                            nodeId = "gps_check",
                            nodeName = "📍 GPS / Location Check",
                            nodeType = CoreEngineNodesModule.CONDITION,
                            state = if (isOk) NodeStepState.SUCCESS else NodeStepState.FAILED,
                            logMessage = if (isOk) "Location permissions and GPS hardware enabled" else (failReason ?: "GPS check failed"),
                            errorReason = failReason,
                        )
                    onStepUpdate?.invoke(listOf(step))
                    if (isOk) {
                        PreconditionResult.Passed(steps = listOf(step))
                    } else {
                        PreconditionResult.Failed(
                            failReason ?: "GPS check failed",
                            steps = listOf(step),
                        )
                    }
                }
            }
            is Precondition.BatteryCheck -> {
                if (!precondition.isEnabled) {
                    val step =
                        NodeExecutionStep(
                            nodeId = "battery_check",
                            nodeName = "🔋 Battery Level Check",
                            nodeType = CoreEngineNodesModule.CONDITION,
                            state = NodeStepState.SUCCESS,
                            logMessage = "Battery check disabled, skipping.",
                        )
                    onStepUpdate?.invoke(listOf(step))
                    PreconditionResult.Passed(steps = listOf(step))
                } else {
                    val res = checkBattery(precondition)
                    val isOk = res is PreconditionResult.Passed
                    val failReason = (res as? PreconditionResult.Failed)?.reason
                    val step =
                        NodeExecutionStep(
                            nodeId = "battery_check",
                            nodeName = "🔋 Battery Level Check",
                            nodeType = CoreEngineNodesModule.CONDITION,
                            state = if (isOk) NodeStepState.SUCCESS else NodeStepState.FAILED,
                            logMessage =
                                if (isOk) {
                                    "Battery level sufficient (>= ${precondition.minBatteryPercent}%${if (precondition.requireCharging) ", charging" else ""})"
                                } else {
                                    (
                                        failReason
                                            ?: "Battery check failed"
                                    )
                                },
                            errorReason = failReason,
                        )
                    onStepUpdate?.invoke(listOf(step))
                    if (isOk) {
                        PreconditionResult.Passed(steps = listOf(step))
                    } else {
                        PreconditionResult.Failed(
                            failReason ?: "Battery check failed",
                            steps = listOf(step),
                        )
                    }
                }
            }
            is Precondition.ChainCheck -> {
                if (precondition.isEnabled) {
                    evaluateChainCheck(precondition, callStack, onStepUpdate)
                } else {
                    val step =
                        NodeExecutionStep(
                            nodeId = "chain_check",
                            nodeName = "⛓️ Chained Pipeline",
                            nodeType = CoreEngineNodesModule.CONDITION,
                            state = NodeStepState.SUCCESS,
                            logMessage = "Chained check disabled, skipping.",
                        )
                    onStepUpdate?.invoke(listOf(step))
                    PreconditionResult.Passed(steps = listOf(step))
                }
            }
        }

    private suspend fun evaluateChainCheck(
        check: Precondition.ChainCheck,
        callStack: Set<String>,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)? = null,
    ): PreconditionResult = withContext(Dispatchers.IO) {
        if (check.nodes.isEmpty()) {
            return@withContext PreconditionResult.Failed("Chained pipeline has no nodes")
        }

        val sanitizedNodes = sanitizeChainNodes(check.nodes)
        val workflow = Workflow(
            id = "chain_prec",
            name = "Chained Precondition",
            nodes = sanitizedNodes,
            connections = check.connections,
        )
        val scopedEvaluator = object : PreconditionEvaluator(
            context = context,
            okHttpClient = okHttpClient,
            delayDuration = delayDuration,
            randomGenerator = randomGenerator,
            credentialRepository = credentialRepository,
        ) {
            override suspend fun evaluateAll(
                preconditions: List<Precondition>,
                onStepUpdate: ((List<NodeExecutionStep>) -> Unit)?,
            ): PreconditionResult = evaluateAllInternal(preconditions, callStack, onStepUpdate)

            override suspend fun evaluate(
                precondition: Precondition,
                onStepUpdate: ((List<NodeExecutionStep>) -> Unit)?,
            ): PreconditionResult = evaluateInternal(precondition, callStack, onStepUpdate)
        }
        val engine = WorkflowEngine(
            preconditionEvaluator = scopedEvaluator,
            credentialRepository = credentialRepository,
            executorFactory =
                NodeExecutorFactory(
                    okHttpClient = okHttpClient,
                    credentialRepository = credentialRepository,
                    pluginRegistry = context?.let { ExternalPluginRegistry(it) },
                ),
        )
        var capturedSteps = emptyList<NodeExecutionStep>()

        val triggerNode = findTriggerNode(check.nodes, check.connections)
        val triggerConfig = parseJsonObjectSafe(triggerNode?.configJson ?: "{}")

        val execContext = buildChainTestContext(triggerNode?.type, triggerConfig)
        val result = engine.executeWorkflowWithProgress(
            workflow = workflow,
            context = execContext,
            appContext = context,
            onStepUpdate = { steps ->
                capturedSteps = steps
                onStepUpdate?.invoke(steps)
            },
        )

        handleChainExecutionResult(result, capturedSteps, onStepUpdate)
    }

    private fun sanitizeChainNodes(nodes: List<dev.zophop.domain.model.WorkflowNode>): List<dev.zophop.domain.model.WorkflowNode> =
        nodes.map { node ->
            val isCond = node.type.contains("CONDITION", ignoreCase = true) ||
                node.type.contains("CHECK", ignoreCase = true)
            if (isCond && node.preconditions.isNotEmpty()) {
                node.copy(preconditions = emptyList())
            } else {
                node
            }
        }

    private fun findTriggerNode(
        nodes: List<dev.zophop.domain.model.WorkflowNode>,
        connections: List<dev.zophop.domain.model.WorkflowConnection>,
    ): dev.zophop.domain.model.WorkflowNode? =
        nodes.firstOrNull { node ->
            val manifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(node.type)
            manifest?.category == dev.zophop.plugin.api.NodeCategory.TRIGGER ||
                node.type.contains("TRIGGER", ignoreCase = true) ||
                connections.none { it.toNodeId == node.id }
        }

    private fun buildChainTestContext(
        triggerNodeType: String?,
        triggerConfig: JsonObject,
    ): ExecutionContext {
        val testContextMap = mutableMapOf<String, Any>()
        for (key in triggerConfig.keys) {
            populateTestContextKey(key, triggerConfig, testContextMap)
        }
        applyDefaultTestContextValues(triggerNodeType, triggerConfig, testContextMap)
        return ExecutionContext(testContextMap)
    }

    private fun populateTestContextKey(
        key: String,
        triggerConfig: JsonObject,
        testContextMap: MutableMap<String, Any>,
    ) {
        if (!key.startsWith("test", ignoreCase = true) || key.length <= 4) return
        val valStr = triggerConfig.optString(key, "")
        if (valStr.isBlank()) return

        val contextKey = key.substring(4).replaceFirstChar { it.lowercase() }
        testContextMap[contextKey] = valStr
        when (contextKey) {
            "body", "text" -> {
                testContextMap["body"] = valStr
                testContextMap["text"] = valStr
                testContextMap["message"] = valStr
                testContextMap["http_response"] = valStr
            }
            "sender" -> {
                testContextMap["sender"] = valStr
                testContextMap["header"] = valStr
                testContextMap["address"] = valStr
            }
            "packageName" -> {
                testContextMap["package_name"] = valStr
                testContextMap["package"] = valStr
            }
        }
    }

    private fun applyDefaultTestContextValues(
        triggerNodeType: String?,
        triggerConfig: JsonObject,
        testContextMap: MutableMap<String, Any>,
    ) {
        val isManual = triggerNodeType?.contains("MANUAL_TRIGGER", ignoreCase = true) == true
        if (!testContextMap.containsKey("body") && !testContextMap.containsKey("text")) {
            val defaultPayload = if (isManual) {
                "{\"status\":\"success\",\"message\":\"Manual test execution\"}"
            } else {
                "{\"code\": \"849201\", \"status\": \"active\"}"
            }
            val sampleBody = triggerConfig.optString("testBody", defaultPayload)
            testContextMap["body"] = sampleBody
            testContextMap["text"] = sampleBody
            testContextMap["http_response"] = sampleBody
        }
        if (!isManual && !testContextMap.containsKey("sender")) {
            testContextMap["sender"] = triggerConfig.optString("testSender", "+15551234567")
        }
        val nowStr = System.currentTimeMillis().toString()
        if (!testContextMap.containsKey("timestamp")) {
            testContextMap["timestamp"] = nowStr
        }
        testContextMap["received_at"] = System.currentTimeMillis()
    }

    private fun handleChainExecutionResult(
        result: WorkflowExecutionResult,
        capturedSteps: List<NodeExecutionStep>,
        onStepUpdate: ((List<NodeExecutionStep>) -> Unit)?,
    ): PreconditionResult = when (result) {
        is WorkflowExecutionResult.Success -> {
            val failedConditionStep = capturedSteps.firstOrNull { step ->
                step.nodeType.contains("CONDITION", ignoreCase = true) &&
                    (step.logMessage.contains("evaluated to FALSE", ignoreCase = true) ||
                        step.logMessage.contains("(FALSE)", ignoreCase = true))
            }
            if (failedConditionStep != null) {
                val msg = failedConditionStep.logMessage.ifBlank { "Condition check evaluated to FALSE" }
                val updatedSteps = capturedSteps.map {
                    if (it.nodeId == failedConditionStep.nodeId) {
                        it.copy(state = NodeStepState.FAILED, errorReason = msg)
                    } else {
                        it
                    }
                }
                onStepUpdate?.invoke(updatedSteps)
                PreconditionResult.Failed(
                    reason = "Chained condition check failed: $msg",
                    steps = updatedSteps,
                )
            } else {
                PreconditionResult.Passed(steps = capturedSteps)
            }
        }
        is WorkflowExecutionResult.PreconditionFailed -> PreconditionResult.Failed(
            reason = "Chained check failed at node '${result.nodeName}': ${result.reason}",
            steps = capturedSteps,
            shouldQueue = result.shouldQueue,
            isQueueTimeout = result.isQueueTimeout,
        )
        is WorkflowExecutionResult.Failed -> PreconditionResult.Failed(
            reason = "Chained execution failed: ${result.reason}",
            steps = capturedSteps,
        )
        is WorkflowExecutionResult.Cancelled -> PreconditionResult.Failed(
            reason = "Chained execution manually cancelled by user",
            steps = capturedSteps,
        )
        is WorkflowExecutionResult.Ignored -> PreconditionResult.Failed(
            reason = "Chained execution skipped: ${result.reason}",
            steps = capturedSteps,
        )
    }

    @SuppressLint("MissingPermission")
    open fun isInternetAvailable(): Boolean {
        val ctx = context ?: return true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private val vpnKeywords = listOf("tun", "ppp", "tailscale", "wireguard", "utun")

    @SuppressLint("MissingPermission")
    open fun isVpnConnected(): Boolean {
        val ctx = context
        return (ctx != null && checkNetworkCapabilitiesVpn(ctx)) || checkNetworkInterfacesVpn()
    }

    @SuppressLint("MissingPermission")
    private fun checkNetworkCapabilitiesVpn(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun checkNetworkInterfacesVpn(): Boolean = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        interfaces.any { iface ->
            iface.isUp && vpnKeywords.any { iface.name.lowercase().contains(it) }
        }
    } catch (_: java.net.SocketException) {
        false
    }

    private suspend fun checkHealth(check: Precondition.HealthCheck): PreconditionResult =
        withContext(Dispatchers.IO) {
            val trimmedUrl = check.targetUrl.trim()
            val parsedUrl = trimmedUrl.toHttpUrlOrNull()
            when {
                trimmedUrl.isBlank() -> PreconditionResult.Failed("Healthcheck URL is empty")
                parsedUrl == null -> PreconditionResult.Failed("Invalid healthcheck URL")
                parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty() ->
                    PreconditionResult.Failed("Invalid healthcheck URL: embedded credentials are not allowed")
                else -> executeHealthCheckRequest(parsedUrl, check)
            }
        }

    private suspend fun executeHealthCheckRequest(
        parsedUrl: okhttp3.HttpUrl,
        check: Precondition.HealthCheck,
    ): PreconditionResult {
        val explicitSecrets = collectHealthcheckSecrets(parsedUrl, check.headers)
        return try {
            val request = buildHealthCheckRequest(parsedUrl, check)
            okHttpClient.newCall(request).await().use { response ->
                val code = response.code
                if (code == check.expectedStatus || (check.expectedStatus == 200 && response.isSuccessful)) {
                    PreconditionResult.Passed()
                } else {
                    PreconditionResult.Failed("Healthcheck HTTP $code (expected ${check.expectedStatus})")
                }
            }
        } catch (e: java.io.IOException) {
            val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
            PreconditionResult.Failed("Healthcheck unreachable: $safeMsg")
        } catch (e: IllegalArgumentException) {
            val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
            PreconditionResult.Failed("Healthcheck unreachable: $safeMsg")
        }
    }

    private fun buildHealthCheckRequest(
        parsedUrl: okhttp3.HttpUrl,
        check: Precondition.HealthCheck,
    ): Request {
        val requestBuilder = Request.Builder().url(parsedUrl)
        when (check.method.uppercase()) {
            "HEAD" -> requestBuilder.head()
            "POST" -> requestBuilder.post(ByteArray(0).toRequestBody(null))
            else -> requestBuilder.get()
        }
        if (check.headers.isNotBlank()) {
            HttpHeaderUtils.parseHeaders(check.headers).forEach { (k, v) ->
                val safeVal = HttpHeaderUtils.sanitizeHeaderValue(v)
                if (HttpHeaderUtils.isValidHeaderName(k)) {
                    requestBuilder.addHeader(k, safeVal)
                }
            }
        }
        return requestBuilder.build()
    }

    private fun collectHealthcheckSecrets(
        parsedUrl: okhttp3.HttpUrl,
        headers: String,
    ): List<String> {
        val secrets = mutableListOf<String>()
        for (name in parsedUrl.queryParameterNames) {
            parsedUrl.queryParameterValues(name).filterNotNull().forEach { v ->
                if (v.length >= 4) secrets.add(v)
            }
        }
        if (headers.isNotBlank()) {
            HttpHeaderUtils.parseHeaders(headers).forEach { (k, v) ->
                if (ExecutionLogFormatter.isSensitiveHeader(k) && v.isNotBlank()) {
                    secrets.add(v)
                }
            }
        }
        return secrets
    }

    private fun checkSim(check: Precondition.SimCheck): PreconditionResult {
        val ctx = context ?: return PreconditionResult.Passed()
        return validateSimState(ctx, check) ?: PreconditionResult.Passed()
    }

    private fun validateSimState(ctx: Context, check: Precondition.SimCheck): PreconditionResult? {
        val hasPhoneState = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.READ_PHONE_STATE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return PreconditionResult.Failed("Missing Phone State Permission (READ_PHONE_STATE)")

        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return when {
            tm == null -> PreconditionResult.Failed("Telephony service unavailable")
            tm.simState != TelephonyManager.SIM_STATE_READY -> PreconditionResult.Failed("SIM card not ready")
            check.requiredCarrier.isNotBlank() && !tm.simOperatorName.orEmpty().contains(check.requiredCarrier, ignoreCase = true) ->
                PreconditionResult.Failed("Carrier '${tm.simOperatorName}' does not match required '${check.requiredCarrier}'")
            else -> null
        }
    }

    private fun checkGps(check: Precondition.GpsCheck): PreconditionResult {
        val ctx = context ?: return PreconditionResult.Passed()
        return validateGpsState(ctx, check) ?: PreconditionResult.Passed()
    }

    private fun validateGpsState(ctx: Context, check: Precondition.GpsCheck): PreconditionResult? {
        val hasFineLoc = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarseLoc = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFineLoc && !hasCoarseLoc) return PreconditionResult.Failed("Missing Location Permission (ACCESS_FINE_LOCATION)")

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsOn = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
            lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        return when {
            lm == null -> PreconditionResult.Failed("Location service unavailable")
            check.requireGpsEnabled && !isGpsOn -> PreconditionResult.Failed("GPS/Location is disabled on device")
            else -> null
        }
    }

    private fun checkBattery(check: Precondition.BatteryCheck): PreconditionResult {
        val ctx = context ?: return PreconditionResult.Passed()
        return validateBatteryState(ctx, check) ?: PreconditionResult.Passed()
    }

    private fun validateBatteryState(ctx: Context, check: Precondition.BatteryCheck): PreconditionResult? {
        val batteryIntent = try {
            ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        } catch (_: SecurityException) {
            null
        } ?: return null

        val level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)

        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 100

        return when {
            percent < check.minBatteryPercent ->
                PreconditionResult.Failed("Battery level is $percent% (requires at least ${check.minBatteryPercent}%)")
            check.requireCharging && !isCharging ->
                PreconditionResult.Failed("Device is not connected to a charger (charging required)")
            else -> null
        }
    }
}

suspend fun okhttp3.Call.await(): okhttp3.Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }
        enqueue(
            object : okhttp3.Callback {
                override fun onResponse(
                    call: okhttp3.Call,
                    response: okhttp3.Response,
                ) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }

                override fun onFailure(
                    call: okhttp3.Call,
                    e: java.io.IOException,
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            },
        )
    }
