package dev.zophop.data

import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import org.json.JSONObject
import java.util.UUID

class DefaultWorkflowInitializer(
    private val workflowRepository: WorkflowRepository,
) {
    suspend fun checkAndInitialize() {
        if (workflowRepository.getWorkflowCount() > 0) {
            return
        }

        val triggerId = UUID.randomUUID().toString()
        val triggerNode =
            WorkflowNode(
                id = triggerId,
                name = "Manual Action Trigger",
                type = "MANUAL_TRIGGER",
                isEnabled = true,
                configJson = "{}",
            )

        val templateId = UUID.randomUUID().toString()
        val templateNode =
            WorkflowNode(
                id = templateId,
                name = "Message Payload Formatter",
                type = "TEMPLATE_FORMATTER",
                isEnabled = true,
                configJson =
                    JSONObject()
                        .apply {
                            put("template", "Test Alert Payload: \${body}")
                        }.toString(),
            )

        val actionId = UUID.randomUUID().toString()
        val actionNode =
            WorkflowNode(
                id = actionId,
                name = "Send System Notification",
                type = "SEND_NOTIFICATION_ACTION",
                isEnabled = true,
                configJson =
                    JSONObject()
                        .apply {
                            put("title", "ZopHop Forwarder Test")
                            put("body", "\${formatted_payload}")
                        }.toString(),
            )

        val defaultWorkflow =
            Workflow(
                id = "default_manual_workflow",
                name = "Initial Starter Workflow",
                description = "Default starter workflow for on-demand manual trigger testing and system notification delivery",
                isEnabled = true,
                nodes = listOf(triggerNode, templateNode, actionNode),
                connections =
                    listOf(
                        WorkflowConnection(fromNodeId = triggerId, toNodeId = templateId),
                        WorkflowConnection(fromNodeId = templateId, toNodeId = actionId),
                    ),
            )

        workflowRepository.saveWorkflow(defaultWorkflow)
    }
}
