package dev.zophop.data

import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowGraph
import dev.zophop.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EntityMappingAndSerializationTest {
    @Test
    fun testPreconditionEntityToDomainConversion() {
        val entity =
            PreconditionEntity(
                id = "prec_100",
                name = "Healthcheck Service",
                type = PreconditionType.HEALTH_CHECK.name,
                isEnabled = true,
                icon = "🩺",
                targetUrl = "https://example.com/health",
                method = "POST",
                headers = "Authorization: Bearer token123",
                expectedStatus = 200,
                onFailureAction = "RETRY",
                maxRetries = 5,
                retryIntervalSeconds = 15,
                showNotificationOnFailure = true,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.HealthCheck)
        val healthDomain = domain as Precondition.HealthCheck

        assertEquals("prec_100", healthDomain.id)
        assertEquals(true, healthDomain.isEnabled)
        assertEquals("🩺", healthDomain.icon)
        assertEquals("https://example.com/health", healthDomain.targetUrl)
        assertEquals("POST", healthDomain.method)
        assertEquals("Authorization: Bearer token123", healthDomain.headers)
        assertEquals(200, healthDomain.expectedStatus)
        assertEquals("RETRY", healthDomain.onFailureAction)
        assertEquals(5, healthDomain.maxRetries)
        assertEquals(15, healthDomain.retryIntervalSeconds)
        assertEquals(true, healthDomain.showNotificationOnFailure)
    }

    @Test
    fun testPreconditionEntityChainedConversion() {
        val node1 = WorkflowNode("n1", "Trigger", NodeType.MANUAL_TRIGGER, true, """{"testBody":"hello"}""")
        val conn1 = WorkflowConnection("n1", "n2", "main")

        val pipelineJson = AppJson.encodeToString(WorkflowGraph(nodes = listOf(node1), connections = listOf(conn1)))

        val entity =
            PreconditionEntity(
                id = "prec_chain",
                name = "Chained Pipeline Precondition",
                type = PreconditionType.CHAINED_CHECK.name,
                isEnabled = true,
                pipelineJson = pipelineJson,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.ChainCheck)
        val chainDomain = domain as Precondition.ChainCheck

        assertEquals("prec_chain", chainDomain.id)
        assertEquals(1, chainDomain.nodes.size)
        assertEquals("n1", chainDomain.nodes[0].id)
        assertEquals(NodeType.MANUAL_TRIGGER, chainDomain.nodes[0].type)
        assertEquals(1, chainDomain.connections.size)
        assertEquals("n1", chainDomain.connections[0].fromNodeId)
    }

    @Test
    fun testWorkflowEntityGraphSerializationRoundtrip() {
        val triggerId = UUID.randomUUID().toString()
        val actionId = UUID.randomUUID().toString()
        val precId = "prec_vault_404"

        val triggerNode =
            WorkflowNode(
                id = triggerId,
                name = "SMS Trigger",
                type = NodeType.SMS_TRIGGER,
                configJson = """{"allowlist":"BANK*"}""",
            )

        val actionNode =
            WorkflowNode(
                id = actionId,
                name = "HTTP Webhook",
                type = NodeType.HTTP_ACTION,
                configJson = """{"url":"https://api.example.com/webhook"}""",
                preconditions =
                    listOf(
                        Precondition.HealthCheck(
                            id = precId,
                            isEnabled = true,
                            icon = "🩺",
                            targetUrl = "https://api.example.com/health",
                            onFailureAction = "RETRY",
                            maxRetries = 4,
                            retryIntervalSeconds = 20,
                            showNotificationOnFailure = true,
                        ),
                    ),
            )

        val workflow =
            Workflow(
                id = "wf_999",
                name = "Bank Notification Forwarder",
                description = "Forwards bank SMS to webhook",
                isEnabled = true,
                icon = "🏦",
                nodes = listOf(triggerNode, actionNode),
                connections =
                    listOf(
                        WorkflowConnection(fromNodeId = triggerId, toNodeId = actionId),
                    ),
            )

        val entity = WorkflowEntity.fromDomain(workflow)
        val deserialized = entity.toDomain()

        assertEquals(workflow.id, deserialized.id)
        assertEquals(workflow.name, deserialized.name)
        assertEquals(workflow.icon, deserialized.icon)
        assertEquals(2, deserialized.nodes.size)
        assertEquals(1, deserialized.connections.size)

        val deserializedAction = deserialized.nodes.first { it.id == actionId }
        assertEquals(1, deserializedAction.preconditions.size)

        val prec = deserializedAction.preconditions[0] as Precondition.HealthCheck
        assertEquals(precId, prec.id)
        assertEquals("🩺", prec.icon)
        assertEquals("https://api.example.com/health", prec.targetUrl)
        assertEquals("RETRY", prec.onFailureAction)
        assertEquals(4, prec.maxRetries)
        assertEquals(20, prec.retryIntervalSeconds)
        assertEquals(true, prec.showNotificationOnFailure)
    }

    @Test
    fun testPreconditionEntityBatteryConversion() {
        val entity =
            PreconditionEntity(
                id = "prec_bat",
                name = "Battery Check Guard",
                type = PreconditionType.BATTERY_CHECK.name,
                isEnabled = true,
                minBatteryPercent = 35,
                requireCharging = true,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.BatteryCheck)
        val batteryDomain = domain as Precondition.BatteryCheck

        assertEquals("prec_bat", batteryDomain.id)
        assertEquals(true, batteryDomain.isEnabled)
        assertEquals(35, batteryDomain.minBatteryPercent)
        assertEquals(true, batteryDomain.requireCharging)
    }

    @Test
    fun testWorkflowEntityPreconditionQueueTimeoutRoundtrip() {
        val triggerId = UUID.randomUUID().toString()
        val actionId = UUID.randomUUID().toString()
        val precId = "prec_queue_test"

        val triggerNode =
            WorkflowNode(
                id = triggerId,
                name = "Trigger",
                type = NodeType.MANUAL_TRIGGER,
            )

        val actionNode =
            WorkflowNode(
                id = actionId,
                name = "Action",
                type = NodeType.HTTP_ACTION,
                preconditions =
                    listOf(
                        Precondition.InternetCheck(
                            id = precId,
                            isEnabled = true,
                            onFailureAction = "QUEUE",
                            queueTimeoutMinutes = 15,
                        ),
                    ),
            )

        val workflow =
            Workflow(
                id = "wf_queue_test",
                name = "Queue Test Workflow",
                nodes = listOf(triggerNode, actionNode),
                connections = listOf(WorkflowConnection(fromNodeId = triggerId, toNodeId = actionId)),
            )

        val entity = WorkflowEntity.fromDomain(workflow)
        val deserialized = entity.toDomain()

        val deserializedAction = deserialized.nodes.first { it.id == actionId }
        val prec = deserializedAction.preconditions[0] as Precondition.InternetCheck
        assertEquals("QUEUE", prec.onFailureAction)
        assertEquals(15, prec.queueTimeoutMinutes)
    }

    @Test
    fun testWorkflowEntityPreconditionRetryStrategyRoundtrip() {
        val triggerId = UUID.randomUUID().toString()
        val actionId = UUID.randomUUID().toString()

        val triggerNode =
            WorkflowNode(
                id = triggerId,
                name = "Trigger",
                type = NodeType.MANUAL_TRIGGER,
            )

        val actionNode =
            WorkflowNode(
                id = actionId,
                name = "Action",
                type = NodeType.HTTP_ACTION,
                preconditions =
                    listOf(
                        Precondition.HealthCheck(
                            id = "prec_retry_strategy",
                            isEnabled = true,
                            onFailureAction = "RETRY",
                            maxRetries = 5,
                            retryIntervalSeconds = 12,
                            retryStrategy = "EXPONENTIAL",
                        ),
                    ),
            )

        val workflow =
            Workflow(
                id = "wf_retry_strategy_test",
                name = "Retry Strategy Test",
                nodes = listOf(triggerNode, actionNode),
                connections = listOf(WorkflowConnection(fromNodeId = triggerId, toNodeId = actionId)),
            )

        val entity = WorkflowEntity.fromDomain(workflow)
        val deserialized = entity.toDomain()

        val deserializedAction = deserialized.nodes.first { it.id == actionId }
        val prec = deserializedAction.preconditions[0] as Precondition.HealthCheck
        assertEquals("RETRY", prec.onFailureAction)
        assertEquals(5, prec.maxRetries)
        assertEquals(12, prec.retryIntervalSeconds)
        assertEquals("EXPONENTIAL", prec.retryStrategy)
    }

    @Test
    fun testWorkflowEntityResilienceOnMalformedPreconditionType() {
        val graphJson =
            """
            {
                "nodes": [
                    {
                        "id": "node_1",
                        "name": "Node With Unknown Prec",
                        "type": "HTTP_ACTION",
                        "isEnabled": true,
                        "configJson": "{}",
                        "preconditions": [
                            {
                                "id": "p1",
                                "type": "UNKNOWN_FUTURE_PRECONDITION",
                                "isEnabled": true,
                                "onFailureAction": "FAIL_IMMEDIATELY"
                            },
                            {
                                "id": "p2",
                                "type": "  internet_check  ",
                                "isEnabled": true,
                                "onFailureAction": "QUEUE"
                            }
                        ]
                    }
                ],
                "connections": []
            }
            """.trimIndent()

        val entity =
            WorkflowEntity(
                id = "wf_resilience_test",
                name = "Resilience Test",
                graphJson = graphJson,
            )

        val domain = entity.toDomain()
        assertEquals(1, domain.nodes.size)
        val node = domain.nodes[0]
        assertEquals("node_1", node.id)
        assertEquals(2, node.preconditions.size)
        // Unknown type gracefully fell back to INTERNET_CHECK rather than dropping the node
        assertTrue(node.preconditions[0] is Precondition.InternetCheck)
        // Case-insensitive trimmed type parsed properly
        assertTrue(node.preconditions[1] is Precondition.InternetCheck)
        assertEquals("QUEUE", node.preconditions[1].onFailureAction)
    }

    @Test
    fun testWorkflowSerializationPreservesIconAndSpecialEmojis() {
        val workflow =
            Workflow(
                id = "wf_emoji_1",
                name = "Complex Emoji Pipeline",
                icon = "🛠️",
                nodes =
                    listOf(
                        WorkflowNode(
                            id = "n1",
                            name = "Manual Trigger",
                            type = NodeType.MANUAL_TRIGGER,
                        ),
                    ),
            )

        val entity = WorkflowEntity.fromDomain(workflow)
        assertEquals("🛠️", entity.icon)

        val deserialized = entity.toDomain()
        assertEquals("🛠️", deserialized.icon)
        assertEquals(workflow.id, deserialized.id)
    }

    @Test
    fun testChainedPreconditionSerializationWithCustomIcon() {
        val chainCheck =
            Precondition.ChainCheck(
                id = "c_check_1",
                name = "Custom Vault Pipeline",
                icon = "🛡️",
                nodes =
                    listOf(
                        WorkflowNode(
                            id = "cn1",
                            name = "Sub VPN Check",
                            type = NodeType.VPN_CHECK,
                        ),
                    ),
            )

        val node =
            WorkflowNode(
                id = "main_node",
                name = "Secure Node",
                type = NodeType.HTTP_ACTION,
                preconditions = listOf(chainCheck),
            )

        val workflow =
            Workflow(
                id = "wf_chained_icon",
                name = "Chained Precondition Workflow",
                icon = "⚡",
                nodes = listOf(node),
            )

        val entity = WorkflowEntity.fromDomain(workflow)
        val deserialized = entity.toDomain()

        assertEquals("⚡", deserialized.icon)
        val deserializedPrec = deserialized.nodes[0].preconditions[0]
        assertTrue(deserializedPrec is Precondition.ChainCheck)
        val chained = deserializedPrec as Precondition.ChainCheck
        assertEquals("Custom Vault Pipeline", chained.name)
        assertEquals("🛡️", chained.icon)
        assertEquals(1, chained.nodes.size)
        assertEquals("Sub VPN Check", chained.nodes[0].name)
    }

    @Test
    fun testLegacyWorkflowEntityWithoutIconDefaultsToEmpty() {
        val entity =
            WorkflowEntity(
                id = "legacy_wf",
                name = "Legacy Workflow",
                graphJson = "{}",
            )

        assertEquals("", entity.icon)
        val domain = entity.toDomain()
        assertEquals("", domain.icon)
    }
}
