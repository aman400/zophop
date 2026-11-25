package dev.zophop.domain.model

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.UUID

private const val WORKFLOW_ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private val workflowIdRandom = SecureRandom()

fun generateShortWorkflowId(length: Int = 8): String =
    buildString(length) {
        repeat(length) {
            append(WORKFLOW_ID_ALPHABET[workflowIdRandom.nextInt(WORKFLOW_ID_ALPHABET.length)])
        }
    }

object NodeType {
    // Triggers
    const val SMS_TRIGGER = "SMS_TRIGGER"
    const val NOTIFICATION_TRIGGER = "NOTIFICATION_TRIGGER"
    const val MANUAL_TRIGGER = "MANUAL_TRIGGER"
    const val CRON_TRIGGER = "CRON_TRIGGER"
    const val CALL_TRIGGER = "CALL_TRIGGER"

    // Parsers & Logic
    const val PARSE_NOTIFICATION = "PARSE_NOTIFICATION"
    const val REGEX_PARSER = "REGEX_PARSER"
    const val JSON_PARSER = "JSON_PARSER"
    const val TEMPLATE_FORMATTER = "TEMPLATE_FORMATTER"
    const val CONDITION = "CONDITION"

    // Condition Checks (Hardware, Network & Sensors)
    const val VPN_CHECK = "VPN_CHECK"
    const val GPS_CHECK = "GPS_CHECK"
    const val INTERNET_CHECK = "INTERNET_CHECK"
    const val BLUETOOTH_CHECK = "BLUETOOTH_CHECK"
    const val SIM_CHECK = "SIM_CHECK"
    const val BATTERY_CHECK = "BATTERY_CHECK"

    // Actions
    const val NTFY_ACTION = "NTFY_ACTION"
    const val GOTIFY_ACTION = "GOTIFY_ACTION"
    const val HTTP_ACTION = "HTTP_ACTION"
    const val SEND_NOTIFICATION_ACTION = "SEND_NOTIFICATION_ACTION"
    const val SEND_SMS_ACTION = "SEND_SMS_ACTION"
    const val DISCONNECT_CALL_ACTION = "DISCONNECT_CALL_ACTION"
    const val ANSWER_CALL_ACTION = "ANSWER_CALL_ACTION"
}

@Serializable
data class WorkflowNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val isEnabled: Boolean = true,
    val configJson: String = "{}",
    val preconditions: List<Precondition> = emptyList(),
)

@Serializable
data class WorkflowConnection(
    val fromNodeId: String,
    val toNodeId: String,
    val outputPort: String = "main", // "main", "true", "false"
    val inputPort: String = "main",
)

@Serializable
data class WorkflowGraph(
    val nodes: List<WorkflowNode> = emptyList(),
    val connections: List<WorkflowConnection> = emptyList(),
)

@Serializable
data class Workflow(
    val id: String = generateShortWorkflowId(),
    val name: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val icon: String = "",
    val nodes: List<WorkflowNode> = emptyList(),
    val connections: List<WorkflowConnection> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
