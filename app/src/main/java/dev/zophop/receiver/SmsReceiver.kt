package dev.zophop.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
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
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Log.i("SmsReceiver", "SMS_RECEIVED broadcast received by SmsReceiver")
        val (sender, fullBody) = extractSmsData(context, intent) ?: run {
            Log.w("SmsReceiver", "extractSmsData returned null (globalWorkflowsEnabled may be false or empty body)")
            return
        }
        Log.i("SmsReceiver", "Processing incoming SMS from '$sender', body length: ${fullBody.length}")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                processSmsWorkflows(context, sender, fullBody)
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error processing SMS workflows: ${e.message}", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun extractSmsData(context: Context, intent: Intent): Pair<String, String>? {
        val isEnabled = EncryptedSettingsRepository(context).getSettings().globalWorkflowsEnabled
        if (!isEnabled) return null

        return parseSmsMessages(intent) ?: parseFallbackSmsExtras(intent)
    }

    private fun parseSmsMessages(intent: Intent): Pair<String, String>? {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return null
        val sender = messages.firstOrNull()?.originatingAddress ?: "Unknown"
        val fullBody = messages.joinToString("") {
            it.displayMessageBody?.ifBlank { it.messageBody } ?: it.messageBody ?: ""
        }
        return if (sender.isNotBlank() && fullBody.isNotBlank()) sender to fullBody else null
    }

    private fun parseFallbackSmsExtras(intent: Intent): Pair<String, String>? {
        val sender = intent.getStringExtra("sender") ?: intent.getStringExtra("address")
        val body = intent.getStringExtra("body") ?: intent.getStringExtra("sms_body") ?: intent.getStringExtra("message")
        return if (!sender.isNullOrBlank() && !body.isNullOrBlank()) sender to body else null
    }

    private suspend fun processSmsWorkflows(context: Context, sender: String, fullBody: String) {
        dev.zophop.domain.engine.plugin.BuiltInPluginRegistry.discoverBundledModules()

        val db = AppDatabase.getInstance(context)
        val workflowRepository = RoomWorkflowRepository(db.workflowDao(), db.preconditionDao())
        val credentialRepository = RoomCredentialRepository(db.credentialDao())

        DefaultWorkflowInitializer(workflowRepository).checkAndInitialize()

        val activeWorkflows = workflowRepository.getActiveWorkflows()
        val smsWorkflows = activeWorkflows.filter { workflow ->
            workflow.nodes.any { it.type.contains("SMS_TRIGGER", ignoreCase = true) }
        }
        Log.i("SmsReceiver", "Found ${smsWorkflows.size} active SMS workflows (out of ${activeWorkflows.size} active workflows)")
        if (smsWorkflows.isEmpty()) return

        val evaluator = PreconditionEvaluator(context)
        val engine = WorkflowEngine(evaluator, credentialRepository)

        val baseContextMap = mapOf(
            "sender" to sender,
            "header" to sender,
            "sms_header" to sender,
            "address" to sender,
            "body" to fullBody,
            "sms_body" to fullBody,
            "message" to fullBody,
            "text" to fullBody,
            "received_at" to System.currentTimeMillis(),
        )

        for (workflow in smsWorkflows) {
            val workflowContext = ExecutionContext(baseContextMap)
            val job = CoroutineScope(Dispatchers.IO).launch {
                Log.i("SmsReceiver", "Executing workflow '${workflow.name}' (${workflow.id})")
                val result = engine.executeWorkflow(workflow, workflowContext, context)
                val resultDetails = when (result) {
                    is WorkflowExecutionResult.Success -> "SUCCESS"
                    is WorkflowExecutionResult.Failed -> "FAILED (Node: ${result.nodeId}, Reason: ${result.reason})"
                    is WorkflowExecutionResult.PreconditionFailed -> "PRECONDITION_FAILED (Reason: ${result.reason})"
                    is WorkflowExecutionResult.Cancelled -> "CANCELLED"
                }
                Log.i("SmsReceiver", "Workflow '${workflow.name}' completed with result: $resultDetails")
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
