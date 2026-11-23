package dev.zophop.data

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
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

        val pipelineJson =
            org.json
                .JSONObject()
                .apply {
                    val nodesArr =
                        org.json.JSONArray().apply {
                            put(
                                org.json.JSONObject().apply {
                                    put("id", node1.id)
                                    put("name", node1.name)
                                    put("type", node1.type)
                                    put("isEnabled", node1.isEnabled)
                                    put("configJson", node1.configJson)
                                },
                            )
                        }
                    val connsArr =
                        org.json.JSONArray().apply {
                            put(
                                org.json.JSONObject().apply {
                                    put("fromNodeId", conn1.fromNodeId)
                                    put("toNodeId", conn1.toNodeId)
                                    put("outputPort", conn1.outputPort)
                                },
                            )
                        }
                    put("nodes", nodesArr)
                    put("connections", connsArr)
                }.toString()

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
        assertEquals(2, deserialized.nodes.size)
        assertEquals(1, deserialized.connections.size)

        val deserializedAction = deserialized.nodes.first { it.id == actionId }
        assertEquals(1, deserializedAction.preconditions.size)

        val prec = deserializedAction.preconditions[0] as Precondition.HealthCheck
        assertEquals(precId, prec.id)
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
}
