package dev.zophop.domain.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "WorkflowEngine"

sealed class WorkflowExecutionResult {
    abstract val steps: List<NodeExecutionStep>

    data class Success(
        val workflowId: String,
        val logs: List<String>,
        override val steps: List<NodeExecutionStep> = emptyList(),
    ) : WorkflowExecutionResult()

    data class PreconditionFailed(
        val workflowId: String,
        val nodeId: String,
        val nodeName: String,
        val reason: String,
        val logs: List<String>,
        override val steps: List<NodeExecutionStep> = emptyList(),
        val shouldQueue: Boolean = false,
        val isQueueTimeout: Boolean = false,
    ) : WorkflowExecutionResult()

    data class Failed(
        val workflowId: String,
        val nodeId: String?,
        val reason: String,
        val logs: List<String>,
        override val steps: List<NodeExecutionStep> = emptyList(),
    ) : WorkflowExecutionResult()

    data class Cancelled(
        val workflowId: String,
        val logs: List<String>,
        override val steps: List<NodeExecutionStep> = emptyList(),
    ) : WorkflowExecutionResult()

    /**
     * The trigger node filtered out this event (e.g. sender/body regex did not match).
     * No history should be stored and no persistent notification should be shown.
     */
    data class Ignored(
        val workflowId: String,
        val reason: String,
        override val steps: List<NodeExecutionStep> = emptyList(),
    ) : WorkflowExecutionResult()
}

@Serializable
enum class NodeStepState {
    PENDING,
    EXECUTING,
    SUCCESS,
    FAILED,
    SKIPPED,
}

@Serializable
data class NodeExecutionStep(
    val nodeId: String,
    val nodeName: String,
    val nodeType: String,
    val state: NodeStepState = NodeStepState.PENDING,
    val logMessage: String = "",
    val errorReason: String? = null,
    val executedAt: Long? = null,
    val durationMs: Long? = null,
    val retriedInExecutionId: String? = null,
    val isQueueWaiting: Boolean = false,
    val queueDeadlineMs: Long? = null,
)

fun List<NodeExecutionStep>.isWaitingInQueue(): Boolean =
    any { (it.isQueueWaiting || it.nodeId.startsWith("queue_waiting")) && it.state == NodeStepState.EXECUTING }

fun List<NodeExecutionStep>.markStepsAsCancelled(
    reason: String = "Execution manually cancelled by user",
): List<NodeExecutionStep> =
    map { step ->
        when (step.state) {
            NodeStepState.EXECUTING -> {
                val duration = step.executedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                step.copy(
                    state = NodeStepState.FAILED,
                    logMessage = reason,
                    errorReason = "Cancelled",
                    durationMs = step.durationMs ?: duration,
                    isQueueWaiting = false,
                )
            }
            NodeStepState.PENDING ->
                step.copy(
                    state = NodeStepState.SKIPPED,
                    logMessage = "Skipped due to cancellation",
                    isQueueWaiting = false,
                )
            else -> step.copy(isQueueWaiting = false)
        }
    }

fun List<NodeExecutionStep>.toJsonString(): String = AppJson.encodeToString(this)

fun String.toNodeExecutionSteps(): List<NodeExecutionStep>? {
    val trimmed = this.trim()
    if (!trimmed.startsWith("[")) return null
    return try {
        AppJson.decodeFromString<List<NodeExecutionStep>>(trimmed)
    } catch (_: Exception) {
        null
    }
}

open class WorkflowEngine(
    private val preconditionEvaluator: PreconditionEvaluator,
    private val credentialRepository: CredentialRepository? = null,
    private val executorFactory: NodeExecutorFactory = NodeExecutorFactory(credentialRepository = credentialRepository),
) {
    private fun getNotificationIcon(): Int =
        dev.zophop.core.engine.R.drawable.ic_notification

    private fun postProgressNotification(
        context: Context?,
        execContext: ExecutionContext?,
        notificationId: Int,
        workflowName: String,
        statusText: String,
        isFinished: Boolean = false,
        isSuccess: Boolean = true,
    ) {
        val showNotif = execContext?.get("show_workflow_notifications") as? Boolean
            ?: run {
                try {
                    context?.let { dev.zophop.data.EncryptedSettingsRepository(it).getSettings().showWorkflowNotifications }
                } catch (_: Exception) {
                    null
                }
            }
            ?: false
        if (!showNotif || context == null) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "zophop_execution_channel"
        ensureNotificationChannel(nm, channelId)

        val workflowId = execContext?.getString("workflow_id", "") ?: ""
        val openAppIntent = Intent().apply {
            setClassName(context, "dev.zophop.ui.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_workflow_id", workflowId)
            putExtra("notification_id", notificationId)
            putExtra("show_active_execution", true)
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            context,
            notificationId + 1000,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        try {
            if (isFinished) {
                showFinishedNotification(
                    context,
                    nm,
                    channelId,
                    notificationId,
                    workflowName,
                    statusText,
                    isSuccess,
                    pendingOpenIntent,
                )
            } else {
                showOngoingNotification(
                    context,
                    nm,
                    channelId,
                    notificationId,
                    workflowName,
                    statusText,
                    workflowId,
                    pendingOpenIntent,
                )
            }
        } catch (_: SecurityException) {
        }
    }

    private fun ensureNotificationChannel(nm: NotificationManager, channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Workflow Execution Progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows live status notifications during workflow execution"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun showFinishedNotification(
        ctx: Context,
        nm: NotificationManager,
        channelId: String,
        notificationId: Int,
        workflowName: String,
        statusText: String,
        isSuccess: Boolean,
        pendingOpenIntent: PendingIntent,
    ) {
        val isCancelled = statusText.contains("Cancelled", ignoreCase = true)
        if (isSuccess || isCancelled) {
            nm.cancel(notificationId)
            return
        }

        val cleanReason = statusText.lineSequence().firstOrNull()?.trim() ?: "Execution failed"
        val content = when {
            cleanReason.startsWith("⏳ Queued") || cleanReason.startsWith("❌ Failed") -> cleanReason
            statusText.contains("queued", ignoreCase = true) -> "⏳ Queued: $cleanReason"
            else -> "❌ Failed: $cleanReason"
        }

        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(getNotificationIcon())
            .setContentTitle(workflowName)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, false)
            .clearActions()
            .setTimeoutAfter(5_000L)
            .build()

        nm.notify(notificationId, notification)
    }

    private fun showOngoingNotification(
        ctx: Context,
        nm: NotificationManager,
        channelId: String,
        notificationId: Int,
        workflowName: String,
        statusText: String,
        workflowId: String,
        pendingOpenIntent: PendingIntent,
    ) {
        val cancelIntent = Intent().apply {
            setClassName(ctx, "dev.zophop.receiver.WorkflowCancelReceiver")
            action = "dev.zophop.ACTION_CANCEL_WORKFLOW"
            putExtra("workflow_id", workflowId)
            putExtra("notification_id", notificationId)
        }
        val pendingCancelIntent = PendingIntent.getBroadcast(
            ctx,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cleanContent = statusText.lineSequence().firstOrNull()?.trim() ?: "Running..."

        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(getNotificationIcon())
            .setContentTitle(workflowName)
            .setContentText(cleanContent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingOpenIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", pendingCancelIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        nm.notify(notificationId, notification)
    }

    open suspend fun executeWorkflow(
        workflow: Workflow,
        context: ExecutionContext,
        appContext: Context?,
        startNodeId: String? = null,
    ): WorkflowExecutionResult =
        executeWorkflowWithProgress(workflow, context, appContext, startNodeId, onStepUpdate = {})

    open suspend fun executeWorkflowWithProgress(
        workflow: Workflow,
        context: ExecutionContext,
        appContext: Context?,
        startNodeId: String? = null,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
    ): WorkflowExecutionResult = withContext(Dispatchers.IO) {
        val notifId = workflow.id.hashCode()
        val steps = workflow.nodes.map {
            NodeExecutionStep(nodeId = it.id, nodeName = it.name, nodeType = it.type, state = NodeStepState.PENDING)
        }.toMutableList()

        notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = true)

        if (!workflow.isEnabled) {
            steps.indices.forEach { steps[it] = steps[it].copy(state = NodeStepState.SKIPPED) }
            val failResult = WorkflowExecutionResult.Failed(
                workflowId = workflow.id,
                nodeId = null,
                reason = "Workflow '${workflow.name}' is disabled",
                logs = listOf("Workflow is disabled"),
                steps = steps.toList(),
            )
            notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = false, result = failResult)
            return@withContext failResult
        }

        val logs = mutableListOf<String>()
        try {
            val startLogPrefix = if (!startNodeId.isNullOrBlank()) "Resuming from node '$startNodeId' in " else "Started executing "
            logs.add("${startLogPrefix}workflow '${workflow.name}' (${workflow.id})")
            context.set("workflow_id", workflow.id)
            context.set("workflow_name", workflow.name)
            if (context.get("timestamp") == null) {
                context.set("timestamp", System.currentTimeMillis().toString())
            }
            postProgressNotification(appContext, context, notifId, workflow.name, "Starting workflow execution...", isFinished = false)

            val triggerNodes = resolveTriggerNodes(workflow, startNodeId)
            if (triggerNodes.isEmpty()) {
                steps.indices.forEach { steps[it] = steps[it].copy(state = NodeStepState.SKIPPED) }
                val failReason = "No trigger or entry nodes found in workflow"
                postProgressNotification(appContext, context, notifId, workflow.name, failReason, isFinished = true, isSuccess = false)
                val failResult = WorkflowExecutionResult.Failed(
                    workflowId = workflow.id,
                    nodeId = null,
                    reason = failReason,
                    logs = logs,
                    steps = steps.toList(),
                )
                notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = false, result = failResult)
                return@withContext failResult
            }

            val visitedNodeIds = mutableSetOf<String>()
            val queue = ArrayDeque<Pair<WorkflowNode, String>>()
            triggerNodes.filter { it.isEnabled }.forEach { trigger ->
                queue.add(trigger to "main")
            }

            while (queue.isNotEmpty()) {
                ensureActive()
                val (currentNode, _) = queue.removeFirst()
                if (visitedNodeIds.contains(currentNode.id)) continue
                visitedNodeIds.add(currentNode.id)

                val earlyExit = processNode(
                    currentNode = currentNode,
                    workflow = workflow,
                    context = context,
                    appContext = appContext,
                    notifId = notifId,
                    logs = logs,
                    steps = steps,
                    onStepUpdate = onStepUpdate,
                    queue = queue,
                )
                if (earlyExit != null) return@withContext earlyExit
            }

            logs.add("Completed workflow '${workflow.name}' successfully.")
            postProgressNotification(
                appContext,
                context,
                notifId,
                workflow.name,
                "Workflow completed successfully",
                isFinished = true,
                isSuccess = true,
            )
            val successResult = WorkflowExecutionResult.Success(workflowId = workflow.id, logs = logs, steps = steps.toList())
            notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = false, result = successResult)
            successResult
        } catch (e: CancellationException) {
            Log.i(TAG, "Workflow execution manually cancelled by user: ${e.message}", e)
            handleCancellation(workflow, logs, steps, notifId, appContext, context, onStepUpdate)
        }
    }

    private fun resolveTriggerNodes(workflow: Workflow, startNodeId: String?): List<WorkflowNode> {
        val startNodes = if (!startNodeId.isNullOrBlank()) workflow.nodes.filter { it.id == startNodeId } else emptyList()
        val triggerNodes = workflow.nodes.filter { it.type.contains("TRIGGER", ignoreCase = true) }
        return when {
            startNodes.isNotEmpty() -> startNodes
            triggerNodes.isNotEmpty() -> triggerNodes
            else -> workflow.nodes.filter { node ->
                workflow.connections.none { conn -> conn.toNodeId == node.id }
            }.ifEmpty { workflow.nodes.take(1) }
        }
    }

    private suspend fun processNode(
        currentNode: WorkflowNode,
        workflow: Workflow,
        context: ExecutionContext,
        appContext: Context?,
        notifId: Int,
        logs: MutableList<String>,
        steps: MutableList<NodeExecutionStep>,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
        queue: ArrayDeque<Pair<WorkflowNode, String>>,
    ): WorkflowExecutionResult? {
        val nodeStartTime = System.currentTimeMillis()
        updateStep(
            workflow = workflow,
            steps = steps,
            nodeId = currentNode.id,
            newState = NodeStepState.EXECUTING,
            message = "Executing node...",
            executedAt = nodeStartTime,
            onStepUpdate = onStepUpdate,
        )
        postProgressNotification(
            appContext,
            context,
            notifId,
            workflow.name,
            "Executing step: '${currentNode.name}'",
            isFinished = false,
        )
        delay(200.milliseconds)

        if (!currentNode.isEnabled) {
            val duration = (System.currentTimeMillis() - nodeStartTime).coerceAtLeast(0L)
            logs.add("Node '${currentNode.name}' is disabled, skipping.")
            updateStep(
                workflow = workflow,
                steps = steps,
                nodeId = currentNode.id,
                newState = NodeStepState.SKIPPED,
                message = "Node disabled",
                durationMs = duration,
                onStepUpdate = onStepUpdate,
            )
            if (!currentNode.type.contains("CONDITION", ignoreCase = true)) {
                enqueueOutgoingNodes(workflow, currentNode.id, "main", queue)
            }
            return null
        }

        val precOutcome = evaluateNodePreconditions(
            currentNode = currentNode,
            workflow = workflow,
            context = context,
            appContext = appContext,
            notifId = notifId,
            logs = logs,
            steps = steps,
            nodeStartTime = nodeStartTime,
            onStepUpdate = onStepUpdate,
        )
        return when (precOutcome) {
            is PreconditionEvalOutcome.Halted -> precOutcome.result
            is PreconditionEvalOutcome.Passed -> {
                currentCoroutineContext().ensureActive()
                executeNodeLogic(
                    currentNode = currentNode,
                    workflow = workflow,
                    context = context,
                    appContext = appContext,
                    notifId = notifId,
                    logs = logs,
                    steps = steps,
                    nodeStartTime = nodeStartTime,
                    preconditionNote = precOutcome.summarySection,
                    onStepUpdate = onStepUpdate,
                    queue = queue,
                )
            }
        }
    }

    private sealed interface PreconditionEvalOutcome {
        data class Passed(
            val summarySection: String? = null,
        ) : PreconditionEvalOutcome

        data class Halted(
            val result: WorkflowExecutionResult,
        ) : PreconditionEvalOutcome
    }

    private suspend fun evaluateNodePreconditions(
        currentNode: WorkflowNode,
        workflow: Workflow,
        context: ExecutionContext,
        appContext: Context?,
        notifId: Int,
        logs: MutableList<String>,
        steps: MutableList<NodeExecutionStep>,
        nodeStartTime: Long,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
    ): PreconditionEvalOutcome {
        var inQueueWait = false
        val passedDeadlineMs = context.get("_queue_deadline_ms") as? Long
        val inferredDeadlineMs =
            passedDeadlineMs
                ?: currentNode.preconditions
                    .filter { it.isEnabled && it.onFailureAction.contains("QUEUE", ignoreCase = true) }
                    .maxOfOrNull { it.queueTimeoutMinutes.coerceAtLeast(1) }
                    ?.let { nodeStartTime + it * 60_000L }
        val stepUpdateCallback: (List<NodeExecutionStep>) -> Unit = { chainSteps ->
            val activeStep = chainSteps.lastOrNull()
            when {
                activeStep?.nodeId?.startsWith("queue_waiting") == true || activeStep?.isQueueWaiting == true -> {
                    inQueueWait = true
                }
                activeStep?.nodeId?.startsWith("queue_resolved") == true -> {
                    inQueueWait = false
                }
            }
            val isWaitingInQueue = inQueueWait && activeStep?.state != NodeStepState.FAILED
            val stepDesc = activeStep?.let { " [${it.nodeName}: ${it.logMessage}]" } ?: ""
            updateStep(
                workflow = workflow,
                steps = steps,
                nodeId = currentNode.id,
                newState = NodeStepState.EXECUTING,
                message = "Evaluating preconditions$stepDesc",
                isQueueWaiting = isWaitingInQueue,
                queueDeadlineMs = if (isWaitingInQueue) inferredDeadlineMs else null,
                onStepUpdate = onStepUpdate,
            )

            if (activeStep != null) {
                postProgressNotification(
                    appContext,
                    context,
                    notifId,
                    workflow.name,
                    formatPreconditionNotificationStatus(activeStep, currentNode.name),
                    isFinished = false,
                )
            }
        }
        val precResult =
            if (passedDeadlineMs != null) {
                preconditionEvaluator.evaluateAllWithDeadline(currentNode.preconditions, passedDeadlineMs, stepUpdateCallback)
            } else {
                preconditionEvaluator.evaluateAll(currentNode.preconditions, stepUpdateCallback)
            }

        if (precResult is PreconditionResult.Failed) {
            val duration = (System.currentTimeMillis() - nodeStartTime).coerceAtLeast(0L)
            val failReason = precResult.reason
            val isActiveQueue = precResult.shouldQueue && !precResult.isQueueTimeout
            val logPrefix = if (isActiveQueue) "⏸️ Precondition Queued" else "⏳ Precondition Failed"
            logs.add("$logPrefix for node '${currentNode.name}': $failReason")
            val summaryLine = if (isActiveQueue) "Precondition queued: $failReason" else "Precondition failed: $failReason"
            val traceBlock = formatPreconditionTrace(precResult.steps)
            val stepMessage =
                if (traceBlock != null) {
                    "$summaryLine\n--- Precondition Checks (${precResult.steps.size}) ---\n$traceBlock"
                } else {
                    summaryLine
                }
            updateStep(
                workflow = workflow,
                steps = steps,
                nodeId = currentNode.id,
                newState = NodeStepState.FAILED,
                error = failReason,
                message = stepMessage,
                durationMs = duration,
                onStepUpdate = onStepUpdate,
            )

            steps.forEachIndexed { i, s ->
                if ((s.state == NodeStepState.PENDING || s.state == NodeStepState.EXECUTING) && s.nodeId != currentNode.id) {
                    steps[i] = steps[i].copy(state = NodeStepState.SKIPPED, logMessage = "Skipped due to upstream failure")
                }
            }
            val notifTitle =
                if (isActiveQueue) {
                    "⏳ Queued: $failReason"
                } else {
                    "❌ Failed: $failReason"
                }
            postProgressNotification(
                appContext,
                context,
                notifId,
                workflow.name,
                notifTitle,
                isFinished = true,
                isSuccess = false,
            )
            val precFailResult = WorkflowExecutionResult.PreconditionFailed(
                workflowId = workflow.id,
                nodeId = currentNode.id,
                nodeName = currentNode.name,
                reason = failReason,
                logs = logs,
                steps = steps.toList(),
                shouldQueue = precResult.shouldQueue,
                isQueueTimeout = precResult.isQueueTimeout,
            )
            notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = false, result = precFailResult)
            return PreconditionEvalOutcome.Halted(precFailResult)
        }
        val passedSteps = (precResult as? PreconditionResult.Passed)?.steps.orEmpty()
        val passedSummary =
            formatPreconditionTrace(passedSteps)?.let { trace ->
                "--- Preconditions Passed (${passedSteps.size}) ---\n$trace"
            }
        return PreconditionEvalOutcome.Passed(passedSummary)
    }

    private fun formatPreconditionTrace(precSteps: List<NodeExecutionStep>): String? {
        if (precSteps.isEmpty()) return null
        return precSteps.joinToString("\n") { s ->
            val firstLine = s.logMessage.lineSequence().firstOrNull()?.trim().orEmpty()
            "• ${s.nodeName} [${s.state}]: ${firstLine.ifBlank { s.errorReason ?: "Checked" }}"
        }
    }

    private fun formatPreconditionNotificationStatus(
        activeStep: NodeExecutionStep,
        nodeName: String,
    ): String =
        when {
            activeStep.nodeId.startsWith("queue_waiting") -> {
                val cleanMsg = activeStep.logMessage.lineSequence().firstOrNull()?.trim()
                if (!cleanMsg.isNullOrBlank()) "⏳ Queued: $cleanMsg" else "⏳ Queued: Waiting for preconditions..."
            }
            activeStep.nodeId.startsWith("retry_delay") -> {
                "🔄 Retrying '$nodeName' (Attempt ${activeStep.nodeName.filter { it.isDigit() || it == '/' }})..."
            }
            activeStep.nodeId.startsWith("queue_resolved") -> {
                "✅ Conditions met. Resuming '$nodeName'..."
            }
            else -> {
                "Checking preconditions for '$nodeName'..."
            }
        }

    private suspend fun executeNodeLogic(
        currentNode: WorkflowNode,
        workflow: Workflow,
        context: ExecutionContext,
        appContext: Context?,
        notifId: Int,
        logs: MutableList<String>,
        steps: MutableList<NodeExecutionStep>,
        nodeStartTime: Long,
        preconditionNote: String?,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
        queue: ArrayDeque<Pair<WorkflowNode, String>>,
    ): WorkflowExecutionResult? {
        val executor = try {
            executorFactory.getExecutor(currentNode.type)
        } catch (e: IllegalArgumentException) {
            val duration = (System.currentTimeMillis() - nodeStartTime).coerceAtLeast(0L)
            logs.add("❌ Node '${currentNode.name}' failed: ${e.message}")
            updateStep(
                workflow = workflow,
                steps = steps,
                nodeId = currentNode.id,
                newState = NodeStepState.FAILED,
                error = e.message,
                message = e.message.orEmpty(),
                durationMs = duration,
                onStepUpdate = onStepUpdate,
            )
            markPendingStepsSkipped(steps, "Skipped due to disabled or unknown node type")
            postProgressNotification(
                appContext,
                context,
                notifId,
                workflow.name,
                "Failed at step '${currentNode.name}': ${e.message}",
                isFinished = true,
                isSuccess = false,
            )
            val failResult = WorkflowExecutionResult.Failed(
                workflowId = workflow.id,
                nodeId = currentNode.id,
                reason = e.message ?: "Unknown node type",
                logs = logs,
                steps = steps.toList(),
            )
            notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = false, result = failResult)
            return failResult
        }

        val result = executor.execute(currentNode, context, appContext)
        val duration = (System.currentTimeMillis() - nodeStartTime).coerceAtLeast(0L)
        return when (result) {
            is NodeExecutionResult.Ignored -> {
                logs.add("ℹ️ Node '${currentNode.name}' ignored: ${result.reason}")
                updateStep(
                    workflow = workflow,
                    steps = steps,
                    nodeId = currentNode.id,
                    newState = NodeStepState.SKIPPED,
                    message = result.reason,
                    durationMs = duration,
                    onStepUpdate = onStepUpdate,
                )
                markPendingStepsSkipped(steps, "Skipped due to filtered-out trigger")
                // Dismiss the ongoing notification immediately — no retry, no history.
                appContext?.let { ctx ->
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(notifId)
                }
                val ignoredResult = WorkflowExecutionResult.Ignored(
                    workflowId = workflow.id,
                    reason = result.reason,
                    steps = steps.toList(),
                )
                notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = false, result = ignoredResult)
                ignoredResult
            }
            is NodeExecutionResult.Failed -> {
                logs.add("❌ Node '${currentNode.name}' failed: ${result.reason}")
                val fullFailLog =
                    if (!preconditionNote.isNullOrBlank()) {
                        "${result.reason}\n$preconditionNote"
                    } else {
                        result.reason
                    }
                updateStep(
                    workflow = workflow,
                    steps = steps,
                    nodeId = currentNode.id,
                    newState = NodeStepState.FAILED,
                    error = result.reason,
                    message = fullFailLog,
                    durationMs = duration,
                    onStepUpdate = onStepUpdate,
                )
                markPendingStepsSkipped(steps, "Skipped due to upstream failure")
                postProgressNotification(
                    appContext,
                    context,
                    notifId,
                    workflow.name,
                    "Failed at step '${currentNode.name}': ${result.reason}",
                    isFinished = true,
                    isSuccess = false,
                )
                val failResult = WorkflowExecutionResult.Failed(
                    workflowId = workflow.id,
                    nodeId = currentNode.id,
                    reason = result.reason,
                    logs = logs,
                    steps = steps.toList(),
                )
                notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = false, result = failResult)
                failResult
            }
            is NodeExecutionResult.Success -> {
                logs.add("✅ Node '${currentNode.name}': ${result.logMessage}")
                val fullSuccessLog =
                    if (!preconditionNote.isNullOrBlank()) {
                        "${result.logMessage}\n$preconditionNote"
                    } else {
                        result.logMessage
                    }
                updateStep(
                    workflow = workflow,
                    steps = steps,
                    nodeId = currentNode.id,
                    newState = NodeStepState.SUCCESS,
                    message = fullSuccessLog,
                    durationMs = duration,
                    onStepUpdate = onStepUpdate,
                )
                enqueueOutgoingNodes(workflow, currentNode.id, result.outputPort, queue)
                null
            }
        }
    }

    private fun enqueueOutgoingNodes(
        workflow: Workflow,
        fromNodeId: String,
        outputPort: String,
        queue: ArrayDeque<Pair<WorkflowNode, String>>,
    ) {
        val outgoing = workflow.connections.filter {
            it.fromNodeId == fromNodeId &&
                (it.outputPort == outputPort || (outputPort == "main" && it.outputPort.isBlank()))
        }
        for (conn in outgoing) {
            val nextNode = workflow.nodes.find { it.id == conn.toNodeId }
            if (nextNode != null) {
                queue.add(nextNode to conn.inputPort)
            }
        }
    }

    private fun markPendingStepsSkipped(steps: MutableList<NodeExecutionStep>, reason: String) {
        steps.forEachIndexed { i, s ->
            if (s.state == NodeStepState.PENDING) {
                steps[i] = steps[i].copy(state = NodeStepState.SKIPPED, logMessage = reason)
            }
        }
    }

    private fun notifyStepUpdate(
        workflow: Workflow,
        steps: List<NodeExecutionStep>,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
        isExecuting: Boolean = true,
        result: WorkflowExecutionResult? = null,
    ) {
        ActiveExecutionTracker.updateExecution(
            workflowId = workflow.id,
            workflowName = workflow.name,
            steps = steps,
            isExecuting = isExecuting,
            result = result,
        )
        onStepUpdate(steps)
    }

    private fun updateStep(
        workflow: Workflow,
        steps: MutableList<NodeExecutionStep>,
        nodeId: String,
        newState: NodeStepState,
        message: String = "",
        error: String? = null,
        executedAt: Long? = null,
        durationMs: Long? = null,
        retriedInExecutionId: String? = null,
        isQueueWaiting: Boolean = false,
        queueDeadlineMs: Long? = null,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
    ) {
        val idx = steps.indexOfFirst { it.nodeId == nodeId }
        if (idx != -1) {
            val existing = steps[idx]
            steps[idx] = existing.copy(
                state = newState,
                logMessage = message,
                errorReason = error ?: existing.errorReason,
                executedAt = executedAt ?: existing.executedAt,
                durationMs = durationMs ?: existing.durationMs,
                retriedInExecutionId = retriedInExecutionId ?: existing.retriedInExecutionId,
                isQueueWaiting = isQueueWaiting,
                queueDeadlineMs = queueDeadlineMs ?: existing.queueDeadlineMs,
            )
            notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = true)
        }
    }

    private fun handleCancellation(
        workflow: Workflow,
        logs: MutableList<String>,
        steps: MutableList<NodeExecutionStep>,
        notifId: Int,
        appContext: Context?,
        context: ExecutionContext,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
    ): WorkflowExecutionResult {
        logs.add("🛑 Workflow execution manually cancelled by user.")
        steps.forEachIndexed { i, s ->
            when (s.state) {
                NodeStepState.EXECUTING -> {
                    val duration = s.executedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                    steps[i] = s.copy(
                        state = NodeStepState.FAILED,
                        logMessage = "Execution manually cancelled by user",
                        errorReason = "Cancelled",
                        durationMs = duration,
                        isQueueWaiting = false,
                    )
                }
                NodeStepState.PENDING -> steps[i] = s.copy(
                    state = NodeStepState.SKIPPED,
                    logMessage = "Skipped due to cancellation",
                )
                else -> {}
            }
        }
        postProgressNotification(
            appContext,
            context,
            notifId,
            workflow.name,
            "Workflow execution manually cancelled",
            isFinished = true,
            isSuccess = false,
        )
        val cancelResult = WorkflowExecutionResult.Cancelled(workflowId = workflow.id, logs = logs, steps = steps.toList())
        notifyStepUpdate(workflow, steps.toList(), onStepUpdate, isExecuting = false, result = cancelResult)
        return cancelResult
    }
}
