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
import dev.zophop.domain.model.generateShortWorkflowId
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.worker.ExecutionStepLogSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class ZopHopNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class NotificationData(
        val packageName: String,
        val title: String,
        val text: String,
        val sender: String,
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val data = extractNotificationData(sbn) ?: return
        serviceScope.launch {
            val enabled = EncryptedSettingsRepository(applicationContext).getSettings().globalWorkflowsEnabled
            if (enabled) {
                processNotificationWorkflows(data)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun extractNotificationData(sbn: StatusBarNotification?): NotificationData? {
        if (sbn == null || (sbn.packageName == packageName)) return null
        val extras = sbn.notification?.extras ?: return null
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        return if (text.isNullOrBlank()) {
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

        val evaluator = PreconditionEvaluator(applicationContext, credentialRepository = credentialRepository)
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

        val jobs = mutableListOf<Job>()
        for (workflow in notifWorkflows) {
            val workflowContext = ExecutionContext(baseContextMap)
            val executionId = generateShortWorkflowId()
            val stepLogSync =
                ExecutionStepLogSync(
                    executionId = executionId,
                    workflowId = workflow.id,
                    workflowRepository = workflowRepository,
                    scope = serviceScope,
                )
            val job = serviceScope.launch {
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
                        stepLogSync.onStepUpdate(steps)
                    },
                )

                if (result is WorkflowExecutionResult.Ignored) {
                    // Trigger filtered out the event — discard the RUNNING log, no history needed.
                    stepLogSync.finalize(finalLog = null, deleteOnIgnored = true)
                    return@launch
                }

                val logEntity = createLogEntity(executionId, workflow.id, result)
                stepLogSync.finalize(finalLog = logEntity)
            }
            ExecutionJobRegistry.registerJob(workflow.id, job)
            jobs.add(job)
        }
        jobs.joinAll()
    }

    private fun createLogEntity(executionId: String, workflowId: String, result: WorkflowExecutionResult): WorkflowExecutionLogEntity =
        WorkflowExecutionLogEntity(
            id = executionId,
            workflowId = workflowId,
            status = when (result) {
                is WorkflowExecutionResult.Success -> "SUCCESS"
                is WorkflowExecutionResult.PreconditionFailed ->
                    if (result.shouldQueue && !result.isQueueTimeout) "QUEUED" else "PRECONDITION_FAILED"
                is WorkflowExecutionResult.Failed -> "FAILED"
                is WorkflowExecutionResult.Cancelled -> "CANCELLED"
                is WorkflowExecutionResult.Ignored -> "IGNORED" // should never reach here
            },
            timestamp = System.currentTimeMillis(),
            messageId = null,
            logs = result.steps.toJsonString(),
            errorReason = when (result) {
                is WorkflowExecutionResult.Success -> null
                is WorkflowExecutionResult.PreconditionFailed -> result.reason
                is WorkflowExecutionResult.Failed -> result.reason
                is WorkflowExecutionResult.Cancelled -> "Execution cancelled by user"
                is WorkflowExecutionResult.Ignored -> result.reason
            },
        )
}
