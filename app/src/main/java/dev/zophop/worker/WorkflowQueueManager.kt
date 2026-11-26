package dev.zophop.worker

import android.content.Context
import android.util.Log
import dev.zophop.data.AppDatabase
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.data.RoomCredentialRepository
import dev.zophop.data.RoomWorkflowRepository
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.data.WorkflowRepository
import dev.zophop.domain.engine.ExecutionJobRegistry
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.PreconditionEvaluator
import dev.zophop.domain.engine.WorkflowEngine
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.engine.isWaitingInQueue
import dev.zophop.domain.engine.markStepsAsCancelled
import dev.zophop.domain.engine.toJsonString
import dev.zophop.domain.engine.toNodeExecutionSteps
import dev.zophop.domain.model.generateShortWorkflowId
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "WorkflowQueueManager"

internal class ExecutionStepLogSync(
    private val executionId: String,
    private val workflowId: String,
    private val workflowRepository: WorkflowRepository,
    private val scope: CoroutineScope,
    private val throttleMs: Long = 10_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dbMutex = Mutex()

    @Volatile
    private var isCurrentlyQueued = false

    @Volatile
    private var lastQueuedPersistAtMs = 0L

    @Volatile
    private var isCompleted = false

    fun onStepUpdate(steps: List<NodeExecutionStep>) {
        if (isCompleted) return
        val isWaiting = steps.isWaitingInQueue()
        val hasExecutingStep = steps.any { it.state == NodeStepState.EXECUTING }
        if (isWaiting) {
            val now = System.currentTimeMillis()
            if (!isCurrentlyQueued || now - lastQueuedPersistAtMs >= throttleMs) {
                isCurrentlyQueued = true
                lastQueuedPersistAtMs = now
                val waitingStep =
                    steps.lastOrNull {
                        (it.isQueueWaiting || it.nodeId.startsWith("queue_waiting")) && it.state == NodeStepState.EXECUTING
                    }
                val queuedLog =
                    WorkflowExecutionLogEntity(
                        id = executionId,
                        workflowId = workflowId,
                        status = "QUEUED",
                        timestamp = now,
                        messageId = null,
                        logs = steps.toJsonString(),
                        errorReason = waitingStep?.logMessage ?: "Waiting for preconditions in queue",
                    )
                scope.launch(ioDispatcher) {
                    dbMutex.withLock {
                        if (!isCompleted) {
                            workflowRepository.saveExecutionLog(queuedLog)
                        }
                    }
                }
            }
        } else if (isCurrentlyQueued && hasExecutingStep) {
            isCurrentlyQueued = false
            lastQueuedPersistAtMs = 0L
            val runningLog =
                WorkflowExecutionLogEntity(
                    id = executionId,
                    workflowId = workflowId,
                    status = "RUNNING",
                    timestamp = System.currentTimeMillis(),
                    messageId = null,
                    logs = steps.toJsonString(),
                    errorReason = null,
                )
            scope.launch(ioDispatcher) {
                dbMutex.withLock {
                    if (!isCompleted) {
                        workflowRepository.saveExecutionLog(runningLog)
                    }
                }
            }
        }
    }

    suspend fun finalize(
        finalLog: WorkflowExecutionLogEntity?,
        deleteOnIgnored: Boolean = false,
    ) {
        isCompleted = true
        withContext(NonCancellable) {
            dbMutex.withLock {
                if (deleteOnIgnored) {
                    workflowRepository.deleteExecutionLogById(executionId)
                } else if (finalLog != null) {
                    workflowRepository.saveExecutionLog(finalLog)
                }
            }
        }
    }
}

object WorkflowQueueManager {
    private const val DEFAULT_QUEUE_TIMEOUT_MINUTES = 10
    private const val STUCK_JOB_GRACE_MS = 60_000L
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun reconcileStaleRunningLogs(
        workflowRepository: WorkflowRepository,
        nowMs: Long = System.currentTimeMillis(),
        staleThresholdMs: Long = 2_000L,
    ) {
        val runningLogs = workflowRepository.getRunningWorkflowLogs()
        for (log in runningLogs) {
            if (!ExecutionJobRegistry.isJobRunning(log.workflowId) && nowMs - log.timestamp >= staleThresholdMs) {
                val steps = log.logs.toNodeExecutionSteps()
                val wasQueued =
                    steps?.isWaitingInQueue() == true ||
                        steps?.any { it.logMessage.contains("[⏳ Queued") } == true
                if (wasQueued) {
                    workflowRepository.saveExecutionLog(
                        log.copy(
                            status = "QUEUED",
                            errorReason = log.errorReason ?: "Waiting for preconditions in queue",
                        ),
                    )
                } else {
                    workflowRepository.saveExecutionLog(
                        log.copy(
                            status = "FAILED",
                            errorReason = log.errorReason ?: "Execution interrupted before completion",
                        ),
                    )
                }
            }
        }
        expireTimedOutQueuedLogs(workflowRepository, nowMs)
    }

    suspend fun expireTimedOutQueuedLogs(
        workflowRepository: WorkflowRepository,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val queuedLogs = workflowRepository.getQueuedWorkflowLogs()
        for (log in queuedLogs) {
            val workflow = workflowRepository.getWorkflowById(log.workflowId)
            val steps = log.logs.toNodeExecutionSteps()
            val timeoutMinutes = resolveEffectiveQueueTimeoutMinutes(workflow, log, steps)
            val deadlineMs = resolveQueueDeadlineMs(log, steps, timeoutMinutes)
            val hasTimedOutStep =
                steps?.any {
                    it.nodeId.startsWith("queue_timeout_") ||
                        it.nodeName.contains("Queue Timeout Exceeded", ignoreCase = true)
                } == true
            val isJobActive = ExecutionJobRegistry.isJobRunning(log.workflowId)
            val isDisabledWorkflow = workflow != null && !workflow.isEnabled
            val isExpired =
                hasTimedOutStep ||
                    isDisabledWorkflow ||
                    (!isJobActive && nowMs >= deadlineMs) ||
                    (isJobActive && nowMs >= deadlineMs + STUCK_JOB_GRACE_MS)

            if (isExpired) {
                if (isJobActive) {
                    ExecutionJobRegistry.cancelJob(log.workflowId)
                }
                val reason =
                    when {
                        isDisabledWorkflow -> "Queued execution stopped: workflow is disabled"
                        hasTimedOutStep && !log.errorReason.isNullOrBlank() -> log.errorReason.orEmpty()
                        else -> "Precondition unsatisfied after ${timeoutMinutes}m queue timeout (expired)"
                    }
                val updatedLogsJson =
                    steps?.let { markStepsAsQueueExpired(it, reason, nowMs).toJsonString() } ?: log.logs
                workflowRepository.saveExecutionLog(
                    log.copy(
                        status = "PRECONDITION_FAILED",
                        logs = updatedLogsJson,
                        errorReason = reason,
                    ),
                )
            }
        }
    }

    internal fun resolveQueueOriginTimestampMs(
        log: WorkflowExecutionLogEntity,
        steps: List<NodeExecutionStep>? = log.logs.toNodeExecutionSteps(),
    ): Long {
        val waitingStepTime =
            steps
                ?.filter {
                    it.isQueueWaiting ||
                        it.state == NodeStepState.EXECUTING ||
                        it.nodeId.startsWith("queue_waiting")
                }?.mapNotNull { it.executedAt }
                ?.minOrNull()
        return waitingStepTime ?: log.timestamp
    }

    internal fun resolveEffectiveQueueTimeoutMinutes(
        workflow: dev.zophop.domain.model.Workflow?,
        log: WorkflowExecutionLogEntity,
        steps: List<NodeExecutionStep>? = log.logs.toNodeExecutionSteps(),
    ): Int {
        if (workflow == null) return DEFAULT_QUEUE_TIMEOUT_MINUTES
        val targetNodeId = resolveRetryStartNodeId(workflow, steps)
        val targetNode = workflow.nodes.find { it.id == targetNodeId }
        val nodeQueueTimeout =
            targetNode
                ?.preconditions
                ?.filter { it.isEnabled && it.onFailureAction.contains("QUEUE", ignoreCase = true) }
                ?.maxOfOrNull { it.queueTimeoutMinutes.coerceAtLeast(1) }
        if (nodeQueueTimeout != null) return nodeQueueTimeout

        return workflow.nodes
            .flatMap { it.preconditions }
            .filter { it.isEnabled && it.onFailureAction.contains("QUEUE", ignoreCase = true) }
            .maxOfOrNull { it.queueTimeoutMinutes.coerceAtLeast(1) }
            ?: DEFAULT_QUEUE_TIMEOUT_MINUTES
    }

    internal fun resolveQueueDeadlineMs(
        log: WorkflowExecutionLogEntity,
        steps: List<NodeExecutionStep>?,
        timeoutMinutes: Int,
    ): Long {
        val explicitDeadline = steps?.mapNotNull { it.queueDeadlineMs }?.minOrNull()
        if (explicitDeadline != null) return explicitDeadline
        val originMs = resolveQueueOriginTimestampMs(log, steps)
        return originMs + timeoutMinutes.coerceAtLeast(1) * 60_000L
    }

    private fun markStepsAsQueueExpired(
        steps: List<NodeExecutionStep>,
        reason: String,
        nowMs: Long,
    ): List<NodeExecutionStep> =
        steps.map { step ->
            when {
                step.state == NodeStepState.EXECUTING || step.isQueueWaiting -> {
                    val duration = step.executedAt?.let { (nowMs - it).coerceAtLeast(0L) }
                    step.copy(
                        state = NodeStepState.FAILED,
                        logMessage = reason,
                        errorReason = reason,
                        durationMs = step.durationMs ?: duration,
                        isQueueWaiting = false,
                    )
                }
                step.state == NodeStepState.PENDING ->
                    step.copy(
                        state = NodeStepState.SKIPPED,
                        logMessage = "Skipped due to queue timeout",
                        isQueueWaiting = false,
                    )
                else -> step.copy(isQueueWaiting = false)
            }
        }

    suspend fun cancelPendingLogsForWorkflow(
        workflowRepository: WorkflowRepository,
        workflowId: String?,
        reason: String = "Execution cancelled by user",
    ) {
        val queuedLogs = workflowRepository.getQueuedWorkflowLogs()
        val runningLogs = workflowRepository.getRunningWorkflowLogs()
        val matchingLogs =
            (queuedLogs + runningLogs).filter { log ->
                workflowId.isNullOrBlank() || log.workflowId == workflowId
            }
        for (log in matchingLogs) {
            val updatedLogsJson =
                log.logs
                    .toNodeExecutionSteps()
                    ?.markStepsAsCancelled(reason)
                    ?.toJsonString()
                    ?: log.logs
            workflowRepository.saveExecutionLog(
                log.copy(
                    status = "CANCELLED",
                    logs = updatedLogsJson,
                    errorReason = reason,
                ),
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun processQueuedWorkflows(context: Context) {
        val appContext = context.applicationContext ?: context
        mutex.withLock {
            try {
                val db = AppDatabase.getInstance(appContext)
                val workflowRepo = RoomWorkflowRepository(db.workflowDao(), db.preconditionDao())
                reconcileStaleRunningLogs(workflowRepo)

                val credentialRepo = RoomCredentialRepository(db.credentialDao())
                val evaluator = PreconditionEvaluator(appContext, credentialRepository = credentialRepo)
                val engine = WorkflowEngine(evaluator, credentialRepo)

                val queuedLogs = db.workflowDao().getQueuedWorkflowLogs()
                if (queuedLogs.isEmpty()) return

                val showNotif = try {
                    EncryptedSettingsRepository(appContext).getSettings().showWorkflowNotifications
                } catch (_: Exception) {
                    false
                }

                queuedLogs.forEach { log ->
                    val workflow = workflowRepo.getWorkflowById(log.workflowId)
                    if (workflow != null && workflow.isEnabled && !ExecutionJobRegistry.isJobRunning(workflow.id)) {
                        retrySingleQueuedWorkflow(workflow, log, engine, workflowRepo, db, showNotif, appContext)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing queued workflows: ${e.message}", e)
            }
        }
    }

    internal fun resolveRetryStartNodeId(
        workflow: dev.zophop.domain.model.Workflow,
        steps: List<NodeExecutionStep>?,
    ): String? {
        val workflowNodeIds = workflow.nodes.map { it.id }.toSet()
        return steps
            ?.lastOrNull { step ->
                step.nodeId in workflowNodeIds &&
                    (step.state == NodeStepState.FAILED || step.isQueueWaiting || step.logMessage.contains("Queued"))
            }?.nodeId
    }

    internal fun buildQueuedRetryContextMap(
        workflow: dev.zophop.domain.model.Workflow,
        showNotif: Boolean,
    ): Map<String, Any> {
        val triggerNode =
            workflow.nodes.firstOrNull { node ->
                val manifest =
                    dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
                        .getBuiltInManifestByTypeId(node.type)
                manifest?.category == dev.zophop.plugin.api.NodeCategory.TRIGGER ||
                    node.type.contains("TRIGGER", ignoreCase = true) ||
                    workflow.connections.none { it.toNodeId == node.id }
            }
        val triggerConfig = parseJsonObjectSafe(triggerNode?.configJson ?: "{}")
        val baseContextMap = mutableMapOf<String, Any>()
        for ((k, v) in triggerConfig) {
            if (k.startsWith("test", ignoreCase = true) && k.length > 4) {
                val contextKey = k.substring(4).replaceFirstChar { it.lowercase() }
                val valStr = v.optString()
                if (valStr.isNotBlank()) {
                    populateTriggerTestContext(baseContextMap, contextKey, valStr)
                }
            }
        }
        baseContextMap["show_workflow_notifications"] = showNotif
        return baseContextMap
    }

    private fun populateTriggerTestContext(
        baseContextMap: MutableMap<String, Any>,
        contextKey: String,
        valStr: String,
    ) {
        baseContextMap[contextKey] = valStr
        when (contextKey) {
            "body", "text" -> {
                baseContextMap["body"] = valStr
                baseContextMap["text"] = valStr
                baseContextMap["message"] = valStr
                baseContextMap["http_response"] = valStr
            }
            "sender" -> {
                baseContextMap["sender"] = valStr
                baseContextMap["header"] = valStr
                baseContextMap["address"] = valStr
            }
            "packageName" -> {
                baseContextMap["package_name"] = valStr
                baseContextMap["package"] = valStr
            }
        }
    }

    private fun retrySingleQueuedWorkflow(
        workflow: dev.zophop.domain.model.Workflow,
        log: WorkflowExecutionLogEntity,
        engine: WorkflowEngine,
        workflowRepo: RoomWorkflowRepository,
        db: AppDatabase,
        showNotif: Boolean,
        appContext: Context,
    ) {
        val steps =
            try {
                log.logs.toNodeExecutionSteps()
            } catch (_: Exception) {
                null
            }
        val timeoutMinutes = resolveEffectiveQueueTimeoutMinutes(workflow, log, steps)
        val deadlineMs = resolveQueueDeadlineMs(log, steps, timeoutMinutes)
        if (System.currentTimeMillis() >= deadlineMs) return

        val failedNodeId = resolveRetryStartNodeId(workflow, steps)
        val execContext = ExecutionContext(buildQueuedRetryContextMap(workflow, showNotif))
        execContext.set("_queue_deadline_ms", deadlineMs)
        val job = scope.launch {
            Log.i(TAG, "Retrying queued workflow '${workflow.name}' (${workflow.id})")
            val newExecutionId = generateShortWorkflowId()
            val initialLog = WorkflowExecutionLogEntity(
                id = newExecutionId,
                workflowId = workflow.id,
                status = "RUNNING",
                timestamp = System.currentTimeMillis(),
                messageId = null,
                logs = emptyList<NodeExecutionStep>().toJsonString(),
                errorReason = null,
            )
            workflowRepo.saveExecutionLog(initialLog)
            db.workflowDao().deleteExecutionLogById(log.id)

            val stepLogSync =
                ExecutionStepLogSync(
                    executionId = newExecutionId,
                    workflowId = workflow.id,
                    workflowRepository = workflowRepo,
                    scope = scope,
                )

            val result = engine.executeWorkflowWithProgress(
                workflow = workflow,
                context = execContext,
                appContext = appContext,
                startNodeId = failedNodeId,
                onStepUpdate = { updatedSteps ->
                    stepLogSync.onStepUpdate(updatedSteps)
                },
            )
            val newLog = WorkflowExecutionLogEntity(
                id = newExecutionId,
                workflowId = workflow.id,
                status = when (result) {
                    is WorkflowExecutionResult.Success -> "SUCCESS"
                    is WorkflowExecutionResult.PreconditionFailed ->
                        if (result.shouldQueue && !result.isQueueTimeout) "QUEUED" else "PRECONDITION_FAILED"
                    is WorkflowExecutionResult.Failed -> "FAILED"
                    is WorkflowExecutionResult.Cancelled -> "CANCELLED"
                    is WorkflowExecutionResult.Ignored -> "IGNORED"
                },
                timestamp = System.currentTimeMillis(),
                messageId = null,
                logs = result.steps.toJsonString(),
                errorReason = when (result) {
                    is WorkflowExecutionResult.Success -> null
                    is WorkflowExecutionResult.PreconditionFailed -> result.reason
                    is WorkflowExecutionResult.Failed -> result.reason
                    is WorkflowExecutionResult.Cancelled -> "Execution cancelled"
                    is WorkflowExecutionResult.Ignored -> result.reason
                },
            )
            // Trigger filtered out the event — discard the RUNNING log, no history needed.
            if (result is WorkflowExecutionResult.Ignored) {
                stepLogSync.finalize(finalLog = null, deleteOnIgnored = true)
            } else {
                stepLogSync.finalize(finalLog = newLog)
            }
        }
        ExecutionJobRegistry.registerJob(workflow.id, job)
    }
}
