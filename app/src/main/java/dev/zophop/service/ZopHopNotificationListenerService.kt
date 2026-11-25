package dev.zophop.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.zophop.data.AppDatabase
import dev.zophop.data.DefaultWorkflowInitializer
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.data.RoomCredentialRepository
import dev.zophop.data.RoomWorkflowRepository
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.ExecutionJobRegistry
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.PreconditionEvaluator
import dev.zophop.domain.engine.WorkflowEngine
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.engine.toJsonString
import dev.zophop.plugin.api.ExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class ZopHopNotificationListenerService : NotificationListenerService() {
    private data class NotificationData(
        val packageName: String,
        val title: String,
        val text: String,
        val sender: String,
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val data = extractNotificationData(sbn) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            processNotificationWorkflows(data)
        }
    }

    private fun extractNotificationData(sbn: StatusBarNotification?): NotificationData? {
        if (sbn == null || (sbn.packageName == packageName)) return null
        val enabled = EncryptedSettingsRepository(applicationContext).getSettings().globalWorkflowsEnabled
        val extras = if (enabled) sbn.notification?.extras else null
        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        return if (extras == null || text.isNullOrBlank()) {
            null
        } else {
            val sender = if (!rawTitle.isNullOrBlank()) rawTitle else sbn.packageName
            NotificationData(sbn.packageName, rawTitle ?: sbn.packageName, text, sender)
        }
    }

    private suspend fun processNotificationWorkflows(data: NotificationData) {
        val db = AppDatabase.getInstance(applicationContext)
        val workflowRepository = RoomWorkflowRepository(db.workflowDao(), db.preconditionDao())
        val credentialRepository = RoomCredentialRepository(db.credentialDao())

        DefaultWorkflowInitializer(workflowRepository).checkAndInitialize()
        dev.zophop.domain.engine.plugin.BuiltInPluginRegistry.discoverBundledModules()

        val activeWorkflows = workflowRepository.getActiveWorkflows()
        val notifWorkflows = activeWorkflows.filter { workflow ->
            workflow.nodes.any { it.type.contains("NOTIFICATION_TRIGGER", ignoreCase = true) }
        }
        if (notifWorkflows.isEmpty()) return

        val evaluator = PreconditionEvaluator(applicationContext)
        val engine = WorkflowEngine(evaluator, credentialRepository)

        val showWorkflowNotifications = EncryptedSettingsRepository(applicationContext).getSettings().showWorkflowNotifications
        val baseContextMap = mapOf(
            "package_name" to data.packageName,
            "title" to data.title,
            "text" to data.text,
            "sender" to data.sender,
            "body" to data.text,
            "received_at" to System.currentTimeMillis(),
            "show_workflow_notifications" to showWorkflowNotifications,
        )

        for (workflow in notifWorkflows) {
            val workflowContext = ExecutionContext(baseContextMap)
            val executionId = UUID.randomUUID().toString()
            val job = CoroutineScope(Dispatchers.IO).launch {
                val initialLog = WorkflowExecutionLogEntity(
                    id = executionId,
                    workflowId = workflow.id,
                    status = "RUNNING",
                    timestamp = System.currentTimeMillis(),
                    messageId = null,
                    logs = emptyList<NodeExecutionStep>().toJsonString(),
                    errorReason = null,
                )
                workflowRepository.saveExecutionLog(initialLog)

                val result = engine.executeWorkflowWithProgress(
                    workflow = workflow,
                    context = workflowContext,
                    appContext = applicationContext,
                    startNodeId = null,
                    onStepUpdate = { steps ->
                        if (steps.any { it.nodeId.startsWith("queue") }) {
                            val waitingStep = steps.lastOrNull { it.nodeId.startsWith("queue") }
                            val queuedLog = WorkflowExecutionLogEntity(
                                id = executionId,
                                workflowId = workflow.id,
                                status = "QUEUED",
                                timestamp = System.currentTimeMillis(),
                                messageId = null,
                                logs = steps.toJsonString(),
                                errorReason = waitingStep?.logMessage ?: "Waiting for preconditions in queue",
                            )
                            CoroutineScope(Dispatchers.IO).launch {
                                workflowRepository.saveExecutionLog(queuedLog)
                            }
                        }
                    },
                )
                val logEntity = createLogEntity(executionId, workflow.id, result)
                workflowRepository.saveExecutionLog(logEntity)
            }
            ExecutionJobRegistry.registerJob(workflow.id, job)
            job.join()
        }
    }

    private fun createLogEntity(executionId: String, workflowId: String, result: WorkflowExecutionResult): WorkflowExecutionLogEntity =
        WorkflowExecutionLogEntity(
            id = executionId,
            workflowId = workflowId,
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
                is WorkflowExecutionResult.Cancelled -> "Execution cancelled by user"
            },
        )
}
