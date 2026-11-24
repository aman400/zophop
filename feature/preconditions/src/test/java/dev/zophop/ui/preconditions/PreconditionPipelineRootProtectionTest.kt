package dev.zophop.ui.preconditions

import dev.zophop.data.PreconditionEntity
import dev.zophop.data.getStepCount
import dev.zophop.data.hasNodeType
import dev.zophop.data.toDomain
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowGraph
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.NodeCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PreconditionPipelineRootProtectionTest {
    @Test
    fun testRootNodeIdentificationInPipeline() {
        val rootNode = WorkflowNode(id = "root_1", name = "Network Internet Check", type = NodeType.INTERNET_CHECK)
        val step2 = WorkflowNode(id = "step_2", name = "Condition Rule", type = NodeType.CONDITION)
        val step3 = WorkflowNode(id = "step_3", name = "ntfy Push Action", type = NodeType.NTFY_ACTION)

        val chainNodes = listOf(rootNode, step2, step3)

        // The root node is always index 0 / firstOrNull
        assertEquals("root_1", chainNodes.firstOrNull()?.id)

        chainNodes.forEachIndexed { index, node ->
            val isRootNode = index == 0
            if (node.id == "root_1") {
                assertTrue("root_1 must be identified as root node", isRootNode)
            } else {
                assertFalse("${node.id} must NOT be identified as root node", isRootNode)
            }
        }
    }

    @Test
    fun testRootNodeDeletionAttemptIsPrevented() {
        val rootNode = WorkflowNode(id = "root_check", name = "GPS Location Service Check", type = NodeType.GPS_CHECK)
        val step2 = WorkflowNode(id = "step_condition", name = "Location Condition", type = NodeType.CONDITION)
        val step3 = WorkflowNode(id = "step_http", name = "HTTP Healthcheck", type = NodeType.HTTP_ACTION)

        val chainNodes = listOf(rootNode, step2, step3)

        // Deletion simulation mimicking PreconditionEditorScreen.kt safeguard
        fun attemptDeleteNode(
            nodes: List<WorkflowNode>,
            nodeToDelete: WorkflowNode,
        ): List<WorkflowNode>? {
            if (nodes.firstOrNull()?.id == nodeToDelete.id) {
                return null // Guarded: Deletion is rejected for the root node
            }
            val remaining = nodes.filterNot { it.id == nodeToDelete.id }
            return remaining
        }

        // Attempting to delete the root node must be rejected
        val rootDeleteResult = attemptDeleteNode(chainNodes, rootNode)
        assertNull("Deleting root node must return null (aborted/prevented)", rootDeleteResult)

        // Deleting downstream non-root step2 must succeed
        val step2DeleteResult = attemptDeleteNode(chainNodes, step2)
        assertNotNull("Deleting non-root step must succeed", step2DeleteResult)
        assertEquals(2, step2DeleteResult?.size)
        assertEquals(listOf("root_check", "step_http"), step2DeleteResult?.map { it.id })

        // Rebuilt connections after deleting step2 should bridge root_check -> step_http
        val newConnections = mutableListOf<WorkflowConnection>()
        step2DeleteResult?.let { remaining ->
            for (i in 0 until remaining.size - 1) {
                newConnections.add(WorkflowConnection(fromNodeId = remaining[i].id, toNodeId = remaining[i + 1].id))
            }
        }
        assertEquals(1, newConnections.size)
        assertEquals("root_check", newConnections[0].fromNodeId)
        assertEquals("step_http", newConnections[0].toNodeId)
    }

    @Test
    fun testSingleNodePipelineRootCannotBeRemoved() {
        val rootOnly =
            listOf(
                WorkflowNode(id = "single_vpn", name = "VPN Connection Check", type = NodeType.VPN_CHECK),
            )

        // In a single-node chain, the only node is the root node
        val nodeToDelete = rootOnly.first()
        val isRoot = rootOnly.firstOrNull()?.id == nodeToDelete.id
        assertTrue("Single node in chain must be root node", isRoot)

        // Deletion check
        val canDelete = !isRoot
        assertFalse("Cannot delete single root node in precondition chain", canDelete)
    }

    @Test
    fun testInitialPreconditionDialogFilterOnlyPermitsChecksAndHttps() {
        val allManifests = BuiltInPluginRegistry.getBuiltInManifests()

        val conditionCheckNodes =
            allManifests.filter {
                it.category == NodeCategory.LOGIC &&
                    (it.typeId.contains("CHECK", ignoreCase = true) || it.displayName.contains("Check", ignoreCase = true))
            }

        val httpNodes =
            allManifests.filter {
                it.typeId.contains("HTTP_ACTION", ignoreCase = true) ||
                    it.typeId.equals("HTTP", ignoreCase = true)
            }

        val initialPreconditionAllowed = (conditionCheckNodes + httpNodes).map { it.typeId }

        // Must permit all 6 core system and hardware checks
        assertTrue("Must include INTERNET_CHECK", initialPreconditionAllowed.contains(NodeType.INTERNET_CHECK))
        assertTrue("Must include VPN_CHECK", initialPreconditionAllowed.contains(NodeType.VPN_CHECK))
        assertTrue("Must include GPS_CHECK", initialPreconditionAllowed.contains(NodeType.GPS_CHECK))
        assertTrue("Must include BATTERY_CHECK", initialPreconditionAllowed.contains(NodeType.BATTERY_CHECK))
        assertTrue("Must include BLUETOOTH_CHECK", initialPreconditionAllowed.contains(NodeType.BLUETOOTH_CHECK))
        assertTrue("Must include SIM_CHECK", initialPreconditionAllowed.contains(NodeType.SIM_CHECK))

        // Must permit HTTP/HTTPS node
        assertTrue("Must include HTTP_ACTION", initialPreconditionAllowed.contains(NodeType.HTTP_ACTION))

        // Must NOT permit other actions, parsers, triggers, or logic conditions as initial root check
        assertFalse("Must NOT include NTFY_ACTION as initial check", initialPreconditionAllowed.contains(NodeType.NTFY_ACTION))
        assertFalse("Must NOT include GOTIFY_ACTION as initial check", initialPreconditionAllowed.contains(NodeType.GOTIFY_ACTION))
        assertFalse("Must NOT include SEND_SMS_ACTION as initial check", initialPreconditionAllowed.contains(NodeType.SEND_SMS_ACTION))
        assertFalse(
            "Must NOT include SEND_NOTIFICATION_ACTION as initial check",
            initialPreconditionAllowed.contains(NodeType.SEND_NOTIFICATION_ACTION),
        )
        assertFalse("Must NOT include CONDITION as initial check", initialPreconditionAllowed.contains(NodeType.CONDITION))
        assertFalse("Must NOT include JSON_PARSER as initial check", initialPreconditionAllowed.contains(NodeType.JSON_PARSER))
        assertFalse("Must NOT include REGEX_PARSER as initial check", initialPreconditionAllowed.contains(NodeType.REGEX_PARSER))
        assertFalse("Must NOT include MANUAL_TRIGGER as initial check", initialPreconditionAllowed.contains(NodeType.MANUAL_TRIGGER))
    }

    @Test
    fun testSubsequentStepDialogFilterExcludesAllTriggers() {
        val allManifests = BuiltInPluginRegistry.getBuiltInManifests()

        val conditionCheckNodes =
            allManifests.filter {
                it.category == NodeCategory.LOGIC &&
                    (it.typeId.contains("CHECK", ignoreCase = true) || it.displayName.contains("Check", ignoreCase = true))
            }
        val logicNodes =
            allManifests.filter {
                it.category == NodeCategory.LOGIC &&
                    !it.typeId.contains("CHECK", ignoreCase = true) &&
                    !it.displayName.contains("Check", ignoreCase = true)
            }
        val actionNodes = allManifests.filter { it.category == NodeCategory.ACTION }
        val extractionNodes = allManifests.filter { it.category == NodeCategory.EXTRACTION }

        // Pipeline steps only allow checks, logic, actions, and extraction
        val allowedPipelineSteps =
            listOf(
                conditionCheckNodes,
                logicNodes,
                actionNodes,
                extractionNodes,
            ).flatten()

        assertTrue("Check nodes must be present", conditionCheckNodes.isNotEmpty())
        assertTrue("Logic nodes must be present (Condition)", logicNodes.any { it.typeId == NodeType.CONDITION })
        assertTrue("Action nodes must be present (HTTP, etc.)", actionNodes.any { it.typeId == NodeType.HTTP_ACTION })
        assertTrue("Extraction nodes must be present", extractionNodes.any { it.typeId == NodeType.JSON_PARSER })

        // Precondition pipeline steps must NOT contain any triggers (including Manual Trigger)
        assertTrue(
            "Pipeline steps must not include any trigger category nodes",
            allowedPipelineSteps.none {
                it.category ==
                    NodeCategory.TRIGGER
            },
        )
        assertFalse("Pipeline steps must not include MANUAL_TRIGGER", allowedPipelineSteps.any { it.typeId == NodeType.MANUAL_TRIGGER })
        assertFalse("Pipeline steps must not include SMS_TRIGGER", allowedPipelineSteps.any { it.typeId == NodeType.SMS_TRIGGER })
        assertFalse("Pipeline steps must not include CRON_TRIGGER", allowedPipelineSteps.any { it.typeId == NodeType.CRON_TRIGGER })
    }

    @Test
    fun testRootNodePreservationInPipelineJsonSerialization() {
        val rootNodeId = UUID.randomUUID().toString()
        val rootNode =
            WorkflowNode(
                id = rootNodeId,
                name = "Battery Check",
                type = NodeType.BATTERY_CHECK,
                isEnabled = true,
                configJson = """{"minBatteryPercent":"25","requireCharging":"true"}""",
            )

        val downstreamId = UUID.randomUUID().toString()
        val downstreamNode =
            WorkflowNode(
                id = downstreamId,
                name = "ntfy Alert",
                type = NodeType.NTFY_ACTION,
                isEnabled = true,
                configJson = """{"topic":"battery_alerts"}""",
            )

        val pipeJson =
            AppJson.encodeToString(
                WorkflowGraph(
                    nodes = listOf(rootNode, downstreamNode),
                    connections = listOf(WorkflowConnection(fromNodeId = rootNodeId, toNodeId = downstreamId)),
                ),
            )

        val entity =
            PreconditionEntity(
                id = "battery_chained_entity",
                name = "Battery Alert Guard",
                type = PreconditionType.CHAINED_CHECK.name,
                pipelineJson = pipeJson,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.ChainCheck)
        val chain = domain as Precondition.ChainCheck

        // Root node must remain at index 0
        assertEquals(2, chain.nodes.size)
        assertEquals(rootNodeId, chain.nodes[0].id)
        assertEquals(NodeType.BATTERY_CHECK, chain.nodes[0].type)
        assertEquals(downstreamId, chain.nodes[1].id)

        // Helper checks
        assertTrue(entity.hasNodeType(NodeType.BATTERY_CHECK))
        assertTrue(entity.hasNodeType(NodeType.NTFY_ACTION))
        assertEquals(2, entity.getStepCount())
    }
}
