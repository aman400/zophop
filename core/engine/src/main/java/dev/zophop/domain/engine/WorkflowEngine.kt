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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
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
}

enum class NodeStepState {
    PENDING,
    EXECUTING,
    SUCCESS,
    FAILED,
    SKIPPED,
}

data class NodeExecutionStep(
    val nodeId: String,
    val nodeName: String,
    val nodeType: String,
    val state: NodeStepState = NodeStepState.PENDING,
    val logMessage: String = "",
    val errorReason: String? = null,
)

fun List<NodeExecutionStep>.toJsonString(): String {
    val arr = JSONArray()
    for (step in this) {
        val obj = JSONObject()
        obj.put("nodeId", step.nodeId)
        obj.put("nodeName", step.nodeName)
        obj.put("nodeType", step.nodeType)
        obj.put("state", step.state.name)
        obj.put("logMessage", step.logMessage)
        if (step.errorReason != null) {
            obj.put("errorReason", step.errorReason)
        }
        arr.put(obj)
    }
    return arr.toString()
}

fun String.toNodeExecutionSteps(): List<NodeExecutionStep>? {
    if (!this.startsWith("[")) return null
    return try {
        val arr = JSONArray(this)
        val list = mutableListOf<NodeExecutionStep>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                NodeExecutionStep(
                    nodeId = obj.getString("nodeId"),
                    nodeName = obj.getString("nodeName"),
                    nodeType = obj.getString("nodeType"),
                    state = NodeStepState.valueOf(obj.getString("state")),
                    logMessage = obj.optString("logMessage", ""),
                    errorReason = if (obj.has("errorReason")) obj.getString("errorReason") else null,
                ),
            )
        }
        list
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

open class WorkflowEngine(
    private val preconditionEvaluator: PreconditionEvaluator,
    private val credentialRepository: CredentialRepository? = null,
    private val executorFactory: NodeExecutorFactory = NodeExecutorFactory(credentialRepository = credentialRepository),
) {
    private fun postProgressNotification(
        context: Context?,
        execContext: ExecutionContext?,
        notificationId: Int,
        workflowName: String,
        statusText: String,
        isFinished: Boolean = false,
        isSuccess: Boolean = true,
    ) {
        val showNotif = execContext?.get("show_workflow_notifications") as? Boolean ?: true
        if (!showNotif || context == null) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "zophop_execution_channel"
        ensureNotificationChannel(nm, channelId)

        val workflowId = execContext?.getString("workflow_id", "") ?: ""
        val openAppIntent = Intent().apply {
            setClassName(context, "dev.zophop.ui.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_workflow_id", workflowId)
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
        if (isSuccess && !statusText.contains("Cancelled", ignoreCase = true)) {
            nm.cancel(notificationId)
            return
        }

        val title = when {
            statusText.contains("Cancelled", ignoreCase = true) -> "🛑 Execution Cancelled: $workflowName"
            else -> "❌ Workflow Failed: $workflowName"
        }
        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .clearActions()
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

        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("⚡ Executing Workflow: $workflowName")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingOpenIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel Execution", pendingCancelIntent)
            .setOngoing(true)
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

        onStepUpdate(steps.toList())

        if (!workflow.isEnabled) {
            steps.indices.forEach { steps[it] = steps[it].copy(state = NodeStepState.SKIPPED) }
            onStepUpdate(steps.toList())
            return@withContext WorkflowExecutionResult.Failed(
                workflowId = workflow.id,
                nodeId = null,
                reason = "Workflow '${workflow.name}' is disabled",
                logs = listOf("Workflow is disabled"),
                steps = steps.toList(),
            )
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
                onStepUpdate(steps.toList())
                val failReason = "No trigger or entry nodes found in workflow"
                postProgressNotification(appContext, context, notifId, workflow.name, failReason, isFinished = true, isSuccess = false)
                return@withContext WorkflowExecutionResult.Failed(
                    workflowId = workflow.id,
                    nodeId = null,
                    reason = failReason,
                    logs = logs,
                    steps = steps.toList(),
                )
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
            WorkflowExecutionResult.Success(workflowId = workflow.id, logs = logs, steps = steps.toList())
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
        updateStep(steps, currentNode.id, NodeStepState.EXECUTING, message = "Executing node...", onStepUpdate = onStepUpdate)
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
            logs.add("Node '${currentNode.name}' is disabled, skipping.")
            updateStep(steps, currentNode.id, NodeStepState.SKIPPED, message = "Node disabled", onStepUpdate = onStepUpdate)
            enqueueOutgoingNodes(workflow, currentNode.id, "main", queue)
            return null
        }

        val precFailure = evaluateNodePreconditions(currentNode, workflow, context, appContext, notifId, logs, steps, onStepUpdate)
        return if (precFailure != null) {
            precFailure
        } else {
            currentCoroutineContext().ensureActive()
            executeNodeLogic(currentNode, workflow, context, appContext, notifId, logs, steps, onStepUpdate, queue)
        }
    }

    private suspend fun evaluateNodePreconditions(
        currentNode: WorkflowNode,
        workflow: Workflow,
        context: ExecutionContext,
        appContext: Context?,
        notifId: Int,
        logs: MutableList<String>,
        steps: MutableList<NodeExecutionStep>,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
    ): WorkflowExecutionResult? {
        val precResult = preconditionEvaluator.evaluateAll(currentNode.preconditions) { chainSteps ->
            val activeStep = chainSteps.lastOrNull()
            val stepDesc = activeStep?.let { " [${it.nodeName}: ${it.logMessage}]" } ?: ""
            updateStep(steps, currentNode.id, NodeStepState.EXECUTING, message = "Evaluating preconditions$stepDesc", onStepUpdate = onStepUpdate)
            postProgressNotification(
                appContext,
                context,
                notifId,
                workflow.name,
                "Evaluating preconditions for '${currentNode.name}'$stepDesc",
                isFinished = false,
            )
        }

        if (precResult is PreconditionResult.Failed) {
            val failReason = precResult.reason
            val logPrefix = if (precResult.shouldQueue) "⏸️ Precondition Queued" else "⏳ Precondition Failed"
            logs.add("$logPrefix for node '${currentNode.name}': $failReason")
            val stepMessage = if (precResult.shouldQueue) "Precondition queued: $failReason" else "Precondition failed: $failReason"
            updateStep(steps, currentNode.id, NodeStepState.FAILED, error = failReason, message = stepMessage, onStepUpdate = onStepUpdate)

            steps.forEachIndexed { i, s ->
                if ((s.state == NodeStepState.PENDING || s.state == NodeStepState.EXECUTING) && s.nodeId != currentNode.id) {
                    steps[i] = steps[i].copy(state = NodeStepState.SKIPPED, logMessage = "Skipped due to upstream failure")
                }
            }
            onStepUpdate(steps.toList())
            val notifTitle =
                if (precResult.shouldQueue) {
                    "Precondition queued at '${currentNode.name}': $failReason"
                } else {
                    "Precondition failed at '${currentNode.name}': $failReason"
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
            return WorkflowExecutionResult.PreconditionFailed(
                workflowId = workflow.id,
                nodeId = currentNode.id,
                nodeName = currentNode.name,
                reason = failReason,
                logs = logs,
                steps = steps.toList(),
                shouldQueue = precResult.shouldQueue,
            )
        }
        return null
    }

    private suspend fun executeNodeLogic(
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
        val executor = try {
            executorFactory.getExecutor(currentNode.type)
        } catch (e: IllegalArgumentException) {
            logs.add("❌ Node '${currentNode.name}' failed: ${e.message}")
            updateStep(steps, currentNode.id, NodeStepState.FAILED, error = e.message, message = e.message.orEmpty(), onStepUpdate = onStepUpdate)
            markPendingStepsSkipped(steps, "Skipped due to disabled or unknown node type")
            onStepUpdate(steps.toList())
            postProgressNotification(
                appContext,
                context,
                notifId,
                workflow.name,
                "Failed at step '${currentNode.name}': ${e.message}",
                isFinished = true,
                isSuccess = false,
            )
            return WorkflowExecutionResult.Failed(
                workflowId = workflow.id,
                nodeId = currentNode.id,
                reason = e.message ?: "Unknown node type",
                logs = logs,
                steps = steps.toList(),
            )
        }

        val result = executor.execute(currentNode, context, appContext)
        return when (result) {
            is NodeExecutionResult.Ignored -> {
                logs.add("ℹ️ Node '${currentNode.name}' ignored: ${result.reason}")
                updateStep(steps, currentNode.id, NodeStepState.SKIPPED, message = result.reason, onStepUpdate = onStepUpdate)
                markPendingStepsSkipped(steps, "Skipped due to filtered-out trigger")
                onStepUpdate(steps.toList())
                WorkflowExecutionResult.Success(workflowId = workflow.id, logs = logs, steps = steps.toList())
            }
            is NodeExecutionResult.Failed -> {
                logs.add("❌ Node '${currentNode.name}' failed: ${result.reason}")
                updateStep(steps, currentNode.id, NodeStepState.FAILED, error = result.reason, message = result.reason, onStepUpdate = onStepUpdate)
                markPendingStepsSkipped(steps, "Skipped due to upstream failure")
                onStepUpdate(steps.toList())
                postProgressNotification(
                    appContext,
                    context,
                    notifId,
                    workflow.name,
                    "Failed at step '${currentNode.name}': ${result.reason}",
                    isFinished = true,
                    isSuccess = false,
                )
                WorkflowExecutionResult.Failed(
                    workflowId = workflow.id,
                    nodeId = currentNode.id,
                    reason = result.reason,
                    logs = logs,
                    steps = steps.toList(),
                )
            }
            is NodeExecutionResult.Success -> {
                logs.add("✅ Node '${currentNode.name}': ${result.logMessage}")
                updateStep(steps, currentNode.id, NodeStepState.SUCCESS, message = result.logMessage, onStepUpdate = onStepUpdate)
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
            it.fromNodeId == fromNodeId && (it.outputPort == outputPort || outputPort == "main")
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

    private fun updateStep(
        steps: MutableList<NodeExecutionStep>,
        nodeId: String,
        newState: NodeStepState,
        message: String = "",
        error: String? = null,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
    ) {
        val idx = steps.indexOfFirst { it.nodeId == nodeId }
        if (idx != -1) {
            steps[idx] = steps[idx].copy(state = newState, logMessage = message, errorReason = error)
            onStepUpdate(steps.toList())
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
                NodeStepState.EXECUTING -> steps[i] = s.copy(
                    state = NodeStepState.FAILED,
                    logMessage = "Execution manually cancelled by user",
                    errorReason = "Cancelled",
                )
                NodeStepState.PENDING -> steps[i] = s.copy(
                    state = NodeStepState.SKIPPED,
                    logMessage = "Skipped due to cancellation",
                )
                else -> {}
            }
        }
        onStepUpdate(steps.toList())
        postProgressNotification(
            appContext,
            context,
            notifId,
            workflow.name,
            "Workflow execution manually cancelled",
            isFinished = true,
            isSuccess = false,
        )
        return WorkflowExecutionResult.Cancelled(workflowId = workflow.id, logs = logs, steps = steps.toList())
    }
}
