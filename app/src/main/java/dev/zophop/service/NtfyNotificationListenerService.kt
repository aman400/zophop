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
import dev.zophop.domain.engine.PreconditionEvaluator
import dev.zophop.domain.engine.WorkflowEngine
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.engine.toJsonString
import dev.zophop.plugin.api.ExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class NtfyNotificationListenerService : NotificationListenerService() {
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
        if (sbn == null || sbn.packageName == packageName) return null
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

        val activeWorkflows = workflowRepository.getActiveWorkflows()
        val evaluator = PreconditionEvaluator(applicationContext)
        val engine = WorkflowEngine(evaluator, credentialRepository)

        val execContext = ExecutionContext(
            mapOf(
                "package_name" to data.packageName,
                "title" to data.title,
                "text" to data.text,
                "sender" to data.sender,
                "body" to data.text,
                "received_at" to System.currentTimeMillis(),
            ),
        )

        for (workflow in activeWorkflows) {
            val job = CoroutineScope(Dispatchers.IO).launch {
                val result = engine.executeWorkflow(workflow, execContext, applicationContext)
                val logEntity = createLogEntity(workflow.id, result)
                workflowRepository.saveExecutionLog(logEntity)
            }
            ExecutionJobRegistry.registerJob(workflow.id, job)
            job.join()
        }
    }

    private fun createLogEntity(workflowId: String, result: WorkflowExecutionResult): WorkflowExecutionLogEntity =
        WorkflowExecutionLogEntity(
            id = UUID.randomUUID().toString(),
            workflowId = workflowId,
            status = when (result) {
                is WorkflowExecutionResult.Success -> "SUCCESS"
                is WorkflowExecutionResult.PreconditionFailed -> "PRECONDITION_FAILED"
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
