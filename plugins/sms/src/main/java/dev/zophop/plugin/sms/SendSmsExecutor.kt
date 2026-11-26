package dev.zophop.plugin.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.ExecutionLogFormatter
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.serialization.json.JsonObject

/**
 * Strategy executor for [NodeType.SEND_SMS_ACTION].
 */
class SendSmsExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        if (!hasSendSmsPermission(appContext)) {
            return NodeExecutionResult.Failed("Missing Send SMS permission (SEND_SMS)")
        }
        return dispatchSms(node, context, appContext)
    }

    private fun dispatchSms(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseJsonConfig(node.configJson)
        val recipientTemplate = config.optString("recipient", "")
        val bodyTemplate = config.optString("body", "\${formatted_payload}")

        val recipient = context.interpolate(recipientTemplate).trim()
        val body = context.interpolate(bodyTemplate).trim()

        return if (recipient.isBlank() || body.isBlank()) {
            NodeExecutionResult.Failed("SMS recipient or body is empty")
        } else {
            try {
                val smsManager = resolveSmsManager(appContext)
                if (smsManager == null) {
                    NodeExecutionResult.Failed("SmsManager unavailable")
                } else {
                    smsManager.sendTextMessage(recipient, null, body, null, null)
                    val log =
                        ExecutionLogFormatter.buildStructuredLog(
                            summary = "Dispatched SMS to $recipient",
                            sections =
                                listOf(
                                    "SMS Dispatch Details" to "Recipient: $recipient\nMessage Length: ${body.length} chars",
                                    "Message Body" to ExecutionLogFormatter.previewText(body, 500),
                                ),
                        )
                    NodeExecutionResult.Success(logMessage = log)
                }
            } catch (e: IllegalArgumentException) {
                NodeExecutionResult.Failed("Failed to send SMS to $recipient: ${e.message}")
            } catch (e: SecurityException) {
                NodeExecutionResult.Failed("Failed to send SMS to $recipient: ${e.message}")
            }
        }
    }

    private fun resolveSmsManager(appContext: Context?): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && appContext != null) {
            appContext.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
}

private fun hasSendSmsPermission(appContext: Context?): Boolean {
    if (appContext == null) return true
    return ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun parseJsonConfig(configJson: String): JsonObject = parseJsonObjectSafe(configJson)
