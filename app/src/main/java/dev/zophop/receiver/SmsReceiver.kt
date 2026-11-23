package dev.zophop.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
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

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val smsData = extractSmsData(context, intent) ?: return
        val (sender, fullBody) = smsData
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processSmsWorkflows(context, sender, fullBody)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun extractSmsData(context: Context, intent: Intent): Pair<String, String>? {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return null
        val enabled = EncryptedSettingsRepository(context).getSettings().globalWorkflowsEnabled
        val messages = if (enabled) Telephony.Sms.Intents.getMessagesFromIntent(intent) else null
        val sender = messages?.firstOrNull()?.originatingAddress ?: "Unknown"
        val fullBody = messages?.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }.orEmpty()

        return if (messages.isNullOrEmpty() || sender.isBlank() || fullBody.isBlank()) {
            null
        } else {
            sender to fullBody
        }
    }

    private suspend fun processSmsWorkflows(context: Context, sender: String, fullBody: String) {
        val db = AppDatabase.getInstance(context)
        val workflowRepository = RoomWorkflowRepository(db.workflowDao(), db.preconditionDao())
        val credentialRepository = RoomCredentialRepository(db.credentialDao())

        DefaultWorkflowInitializer(workflowRepository).checkAndInitialize()

        val activeWorkflows = workflowRepository.getActiveWorkflows()
        val evaluator = PreconditionEvaluator(context)
        val engine = WorkflowEngine(evaluator, credentialRepository)

        val execContext = ExecutionContext(
            mapOf(
                "sender" to sender,
                "header" to sender,
                "sms_header" to sender,
                "address" to sender,
                "body" to fullBody,
                "sms_body" to fullBody,
                "message" to fullBody,
                "text" to fullBody,
                "received_at" to System.currentTimeMillis(),
            ),
        )

        for (workflow in activeWorkflows) {
            val job = CoroutineScope(Dispatchers.IO).launch {
                val result = engine.executeWorkflow(workflow, execContext, context)
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
