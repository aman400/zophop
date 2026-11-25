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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreconditionVaultMappingAndValidationTest {
    @Test
    fun testInternetCheckEntityMapping() {
        val entity =
            PreconditionEntity(
                id = "prec_net",
                name = "Internet Connection Check",
                type = PreconditionType.INTERNET_CHECK.name,
                isEnabled = true,
                onFailureAction = "RETRY",
                maxRetries = 5,
                retryIntervalSeconds = 10,
                showNotificationOnFailure = true,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.InternetCheck)
        val internetCheck = domain as Precondition.InternetCheck
        assertEquals("prec_net", internetCheck.id)
        assertTrue(internetCheck.isEnabled)
        assertEquals("RETRY", internetCheck.onFailureAction)
        assertEquals(5, internetCheck.maxRetries)
        assertEquals(10, internetCheck.retryIntervalSeconds)
    }

    @Test
    fun testVpnCheckEntityMapping() {
        val entity =
            PreconditionEntity(
                id = "prec_vpn",
                name = "Tailscale VPN Check",
                type = PreconditionType.VPN_CHECK.name,
                isEnabled = false,
                onFailureAction = "FAIL_IMMEDIATELY",
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.VpnCheck)
        val vpnCheck = domain as Precondition.VpnCheck
        assertEquals("prec_vpn", vpnCheck.id)
        assertFalse(vpnCheck.isEnabled)
        assertEquals("FAIL_IMMEDIATELY", vpnCheck.onFailureAction)
    }

    @Test
    fun testHealthCheckEntityMapping() {
        val entity =
            PreconditionEntity(
                id = "prec_health_1",
                name = "API Gateway Health Probe",
                type = PreconditionType.HEALTH_CHECK.name,
                isEnabled = true,
                targetUrl = "https://api.internal/healthz",
                method = "POST",
                headers = "Authorization: Bearer secret_123",
                expectedStatus = 204,
                credentialId = "cred_bearer_1",
                onFailureAction = "RETRY",
                maxRetries = 3,
                retryIntervalSeconds = 30,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.HealthCheck)
        val healthCheck = domain as Precondition.HealthCheck
        assertEquals("prec_health_1", healthCheck.id)
        assertEquals("https://api.internal/healthz", healthCheck.targetUrl)
        assertEquals("POST", healthCheck.method)
        assertEquals("Authorization: Bearer secret_123", healthCheck.headers)
        assertEquals(204, healthCheck.expectedStatus)
    }

    @Test
    fun testSimCheckEntityMapping() {
        val entity =
            PreconditionEntity(
                id = "prec_sim_1",
                name = "Jio Carrier Check",
                type = PreconditionType.SIM_CHECK.name,
                isEnabled = true,
                requiredCarrier = "Jio",
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.SimCheck)
        val simCheck = domain as Precondition.SimCheck
        assertEquals("prec_sim_1", simCheck.id)
        assertEquals("Jio", simCheck.requiredCarrier)
    }

    @Test
    fun testGpsCheckEntityMapping() {
        val entity =
            PreconditionEntity(
                id = "prec_gps_1",
                name = "GPS Hardware Check",
                type = PreconditionType.GPS_CHECK.name,
                isEnabled = true,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.GpsCheck)
        val gpsCheck = domain as Precondition.GpsCheck
        assertEquals("prec_gps_1", gpsCheck.id)
        assertTrue(gpsCheck.requireGpsEnabled)
    }

    @Test
    fun testChainedCheckPipelineSerializationAndDeserialization() {
        val nodes =
            listOf(
                WorkflowNode("c_n1", "Manual Test Data", NodeType.MANUAL_TRIGGER, true, """{"testBody":"{\"status\":\"OK\"}"}"""),
                WorkflowNode("c_n2", "Status Extractor", NodeType.JSON_PARSER, true, """{"jsonKey":"status","outputKey":"state"}"""),
            )
        val connections =
            listOf(
                WorkflowConnection("c_n1", "c_n2", "main"),
            )
        val pipelineJson = AppJson.encodeToString(WorkflowGraph(nodes = nodes, connections = connections))

        val entity =
            PreconditionEntity(
                id = "prec_chain_1",
                name = "Chained Pipeline Guard",
                type = PreconditionType.CHAINED_CHECK.name,
                isEnabled = true,
                pipelineJson = pipelineJson,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.ChainCheck)
        val chainCheck = domain as Precondition.ChainCheck
        assertEquals(2, chainCheck.nodes.size)
        assertEquals("Manual Test Data", chainCheck.nodes[0].name)
        assertEquals(NodeType.MANUAL_TRIGGER, chainCheck.nodes[0].type)
        assertEquals("Status Extractor", chainCheck.nodes[1].name)
        assertEquals(NodeType.JSON_PARSER, chainCheck.nodes[1].type)

        assertEquals(1, chainCheck.connections.size)
        assertEquals("c_n1", chainCheck.connections[0].fromNodeId)
        assertEquals("c_n2", chainCheck.connections[0].toNodeId)
    }

    @Test
    fun testUnknownPreconditionTypeFallback() {
        val entity =
            PreconditionEntity(
                id = "prec_unknown",
                name = "Legacy Check",
                type = "UNKNOWN_TYPE_XYZ",
                isEnabled = true,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.InternetCheck)
        assertEquals("prec_unknown", domain.id)
    }

    @Test
    fun testPreconditionWorkflowUsageLinking() {
        val precId = "prec_target"
        val precondition = Precondition.InternetCheck(id = precId, isEnabled = true)

        val nodeWithPrecondition =
            WorkflowNode(
                id = "node_1",
                name = "Test Action",
                type = NodeType.NTFY_ACTION,
                preconditions = listOf(precondition),
            )

        val nodeWithoutPrecondition =
            WorkflowNode(
                id = "node_2",
                name = "Other Action",
                type = NodeType.NTFY_ACTION,
                preconditions = emptyList(),
            )

        val workflowLinked =
            Workflow(
                id = "wf_1",
                name = "Linked Workflow",
                nodes = listOf(nodeWithPrecondition),
            )

        val workflowUnlinked =
            Workflow(
                id = "wf_2",
                name = "Unlinked Workflow",
                nodes = listOf(nodeWithoutPrecondition),
            )

        val workflows = listOf(workflowLinked, workflowUnlinked)

        val linkedWorkflows =
            workflows.filter { wf ->
                wf.nodes.any { node -> node.preconditions.any { p -> p.id == precId } }
            }

        assertEquals(1, linkedWorkflows.size)
        assertEquals("wf_1", linkedWorkflows[0].id)
        assertEquals("Linked Workflow", linkedWorkflows[0].name)
    }

    @Test
    fun testBatteryCheckEntityMapping() {
        val entity =
            PreconditionEntity(
                id = "prec_battery_1",
                name = "Battery Safe Check",
                type = PreconditionType.BATTERY_CHECK.name,
                isEnabled = true,
                minBatteryPercent = 25,
                requireCharging = true,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.BatteryCheck)
        val batteryCheck = domain as Precondition.BatteryCheck
        assertEquals("prec_battery_1", batteryCheck.id)
        assertEquals(25, batteryCheck.minBatteryPercent)
        assertTrue(batteryCheck.requireCharging)
        assertTrue(batteryCheck.isEnabled)
    }

    @Test
    fun testWorkflowEntityWithBatteryCheckRoundTrip() {
        val workflow =
            Workflow(
                id = "wf_battery",
                name = "Battery Guarded Workflow",
                nodes =
                    listOf(
                        WorkflowNode(
                            id = "node_1",
                            name = "Heavy Sync",
                            type = NodeType.HTTP_ACTION,
                            preconditions =
                                listOf(
                                    Precondition.BatteryCheck(
                                        id = "prec_battery_guard",
                                        isEnabled = true,
                                        minBatteryPercent = 30,
                                        requireCharging = true,
                                    ),
                                ),
                        ),
                    ),
            )

        val entity = WorkflowEntity.fromDomain(workflow)
        val restored = entity.toDomain()

        assertEquals(1, restored.nodes.size)
        val precs = restored.nodes[0].preconditions
        assertEquals(1, precs.size)
        assertTrue(precs[0] is Precondition.BatteryCheck)
        val batteryPrec = precs[0] as Precondition.BatteryCheck
        assertEquals("prec_battery_guard", batteryPrec.id)
        assertEquals(30, batteryPrec.minBatteryPercent)
        assertTrue(batteryPrec.requireCharging)
    }
}
