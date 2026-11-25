package dev.zophop.worker

import android.content.Context
import android.util.Log
import dev.zophop.data.AppDatabase
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.data.RoomCredentialRepository
import dev.zophop.data.RoomWorkflowRepository
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.ExecutionJobRegistry
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.PreconditionEvaluator
import dev.zophop.domain.engine.WorkflowEngine
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.engine.toJsonString
import dev.zophop.domain.engine.toNodeExecutionSteps
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private const val TAG = "WorkflowQueueManager"

object WorkflowQueueManager {
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Suppress("TooGenericExceptionCaught")
    suspend fun processQueuedWorkflows(context: Context) {
        val appContext = context.applicationContext ?: context
        mutex.withLock {
            try {
                val db = AppDatabase.getInstance(appContext)
                val workflowRepo = RoomWorkflowRepository(db.workflowDao(), db.preconditionDao())
                val credentialRepo = RoomCredentialRepository(db.credentialDao())
                val evaluator = PreconditionEvaluator(appContext)
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

    private fun retrySingleQueuedWorkflow(
        workflow: dev.zophop.domain.model.Workflow,
        log: WorkflowExecutionLogEntity,
        engine: WorkflowEngine,
        workflowRepo: RoomWorkflowRepository,
        db: AppDatabase,
        showNotif: Boolean,
        appContext: Context,
    ) {
        val steps = try {
            log.logs.toNodeExecutionSteps()
        } catch (_: Exception) {
            null
        }
        val failedNodeId = steps?.lastOrNull {
            (it.state == NodeStepState.FAILED) || it.nodeId.startsWith("queue")
        }?.nodeId

        val triggerNode = workflow.nodes.firstOrNull { node ->
            val manifest = dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
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
                    baseContextMap[contextKey] = valStr
                }
            }
        }
        baseContextMap["show_workflow_notifications"] = showNotif

        val execContext = ExecutionContext(baseContextMap)
        val job = scope.launch {
            Log.i(TAG, "Retrying queued workflow '${workflow.name}' (${workflow.id})")
            val newExecutionId = UUID.randomUUID().toString()
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

            val result = engine.executeWorkflowWithProgress(
                workflow = workflow,
                context = execContext,
                appContext = appContext,
                startNodeId = failedNodeId,
                onStepUpdate = { steps ->
                    if (steps.any { it.nodeId.startsWith("queue") }) {
                        val waitingStep = steps.lastOrNull { it.nodeId.startsWith("queue") }
                        val queuedLog = WorkflowExecutionLogEntity(
                            id = newExecutionId,
                            workflowId = workflow.id,
                            status = "QUEUED",
                            timestamp = System.currentTimeMillis(),
                            messageId = null,
                            logs = steps.toJsonString(),
                            errorReason = waitingStep?.logMessage ?: "Waiting for preconditions in queue",
                        )
                        scope.launch {
                            workflowRepo.saveExecutionLog(queuedLog)
                        }
                    }
                },
            )
            val newLog = WorkflowExecutionLogEntity(
                id = newExecutionId,
                workflowId = workflow.id,
                status = when (result) {
                    is WorkflowExecutionResult.Success -> "SUCCESS"
                    is WorkflowExecutionResult.PreconditionFailed -> if (result.shouldQueue) "QUEUED" else "PRECONDITION_FAILED"
                    is WorkflowExecutionResult.Failed -> "FAILED"
                    is WorkflowExecutionResult.Cancelled -> "CANCELLED"
                },
                timestamp = System.currentTimeMillis(),
                messageId = null,
                logs = result.steps.toJsonString(),
                errorReason = when (result) {
                    is WorkflowExecutionResult.Success -> null
                    is WorkflowExecutionResult.PreconditionFailed -> result.reason
                    is WorkflowExecutionResult.Failed -> result.reason
                    is WorkflowExecutionResult.Cancelled -> "Execution cancelled"
                },
            )
            workflowRepo.saveExecutionLog(newLog)
        }
        ExecutionJobRegistry.registerJob(workflow.id, job)
    }
}
