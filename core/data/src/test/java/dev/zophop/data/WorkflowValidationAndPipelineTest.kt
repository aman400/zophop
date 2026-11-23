package dev.zophop.data

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowValidationAndPipelineTest {
    @Test
    fun testWorkflowEntityToDomainMapping() {
        val node1Obj =
            JSONObject().apply {
                put("id", "n_trig")
                put("name", "SMS Trigger")
                put("type", NodeType.SMS_TRIGGER)
                put("isEnabled", true)
                put("configJson", """{"allowlist":".*"}""")
            }
        val node2Obj =
            JSONObject().apply {
                put("id", "n_action")
                put("name", "HTTP Post")
                put("type", NodeType.HTTP_ACTION)
                put("isEnabled", true)
                put("configJson", """{"url":"https://api.test/post","method":"POST"}""")
            }

        val connObj =
            JSONObject().apply {
                put("fromNodeId", "n_trig")
                put("toNodeId", "n_action")
                put("outputPort", "main")
            }

        val graphObj =
            JSONObject().apply {
                put("nodes", JSONArray().put(node1Obj).put(node2Obj))
                put("connections", JSONArray().put(connObj))
            }

        val entity =
            WorkflowEntity(
                id = "w_pipe_1",
                name = "SMS to Webhook Pipeline",
                description = "Forwards bank SMS alerts to webhook",
                isEnabled = true,
                graphJson = graphObj.toString(),
                updatedAt = 2000L,
            )

        val domain = entity.toDomain()
        assertEquals("w_pipe_1", domain.id)
        assertEquals("SMS to Webhook Pipeline", domain.name)
        assertTrue(domain.isEnabled)
        assertEquals(2, domain.nodes.size)
        assertEquals("SMS Trigger", domain.nodes[0].name)
        assertEquals(NodeType.SMS_TRIGGER, domain.nodes[0].type)

        assertEquals(1, domain.connections.size)
        assertEquals("n_trig", domain.connections[0].fromNodeId)
        assertEquals("n_action", domain.connections[0].toNodeId)
    }

    @Test
    fun testWorkflowDomainToEntityConversion() {
        val node1 = WorkflowNode("n1", "Trigger", NodeType.MANUAL_TRIGGER)
        val node2 = WorkflowNode("n2", "ntfy Push", NodeType.NTFY_ACTION, configJson = """{"topic":"test"}""")
        val conn = WorkflowConnection("n1", "n2")

        val domain =
            Workflow(
                id = "w_domain_1",
                name = "Manual ntfy Pipeline",
                isEnabled = false,
                nodes = listOf(node1, node2),
                connections = listOf(conn),
            )

        val entity = WorkflowEntity.fromDomain(domain)
        assertEquals("w_domain_1", entity.id)
        assertEquals("Manual ntfy Pipeline", entity.name)
        assertFalse(entity.isEnabled)

        val graphObj = JSONObject(entity.graphJson)
        val nodesArr = graphObj.optJSONArray("nodes") ?: JSONArray()
        assertEquals(2, nodesArr.length())
        assertEquals("n1", nodesArr.getJSONObject(0).getString("id"))
        assertEquals("MANUAL_TRIGGER", nodesArr.getJSONObject(0).getString("type"))

        val connArr = graphObj.optJSONArray("connections") ?: JSONArray()
        assertEquals(1, connArr.length())
        assertEquals("n1", connArr.getJSONObject(0).getString("fromNodeId"))
        assertEquals("n2", connArr.getJSONObject(0).getString("toNodeId"))
    }

    @Test
    fun testCorruptWorkflowJsonFallback() {
        val entity =
            WorkflowEntity(
                id = "w_corrupt",
                name = "Corrupt Workflow",
                description = "Corrupt nodes and connections",
                isEnabled = true,
                graphJson = "INVALID_JSON_GRAPH",
                updatedAt = 2000L,
            )

        val domain = entity.toDomain()
        assertEquals("w_corrupt", domain.id)
        assertTrue(domain.nodes.isEmpty())
        assertTrue(domain.connections.isEmpty())
    }
}
