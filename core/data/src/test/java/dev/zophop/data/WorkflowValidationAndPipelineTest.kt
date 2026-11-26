package dev.zophop.data

import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowGraph
import dev.zophop.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowValidationAndPipelineTest {
    @Test
    fun testWorkflowEntityToDomainMapping() {
        val node1 =
            WorkflowNode(
                id = "n_trig",
                name = "SMS Trigger",
                type = NodeType.SMS_TRIGGER,
                isEnabled = true,
                configJson = """{"allowlist":".*"}""",
            )
        val node2 =
            WorkflowNode(
                id = "n_action",
                name = "HTTP Post",
                type = NodeType.HTTP_ACTION,
                isEnabled = true,
                configJson = """{"url":"https://api.test/post","method":"POST"}""",
            )
        val conn =
            WorkflowConnection(
                fromNodeId = "n_trig",
                toNodeId = "n_action",
                outputPort = "main",
            )
        val graph =
            WorkflowGraph(
                nodes = listOf(node1, node2),
                connections = listOf(conn),
            )

        val entity =
            WorkflowEntity(
                id = "w_pipe_1",
                name = "SMS to Webhook Pipeline",
                description = "Forwards bank SMS alerts to webhook",
                isEnabled = true,
                graphJson = AppJson.encodeToString(graph),
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

        val graph = AppJson.decodeFromString<WorkflowGraph>(entity.graphJson)
        assertEquals(2, graph.nodes.size)
        assertEquals("n1", graph.nodes[0].id)
        assertEquals("MANUAL_TRIGGER", graph.nodes[0].type)

        assertEquals(1, graph.connections.size)
        assertEquals("n1", graph.connections[0].fromNodeId)
        assertEquals("n2", graph.connections[0].toNodeId)
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
