package dev.zophop.domain.engine

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowVariableResolverTest {
    @Test
    fun testSequentialPipelineUpstreamNodesResolution() {
        val n1 = WorkflowNode("n1", "SMS Trigger", NodeType.SMS_TRIGGER)
        val n2 = WorkflowNode("n2", "Regex Parser", NodeType.REGEX_PARSER)
        val n3 = WorkflowNode("n3", "Condition", NodeType.CONDITION)
        val n4 = WorkflowNode("n4", "HTTP Webhook", NodeType.HTTP_ACTION)
        val nodes = listOf(n1, n2, n3, n4)

        // For n1 (trigger): no upstream nodes
        val upstream1 = WorkflowVariableResolver.getUpstreamNodes("n1", nodes)
        assertTrue(upstream1.isEmpty())

        // For n2: upstream is n1
        val upstream2 = WorkflowVariableResolver.getUpstreamNodes("n2", nodes)
        assertEquals(listOf("n1"), upstream2.map { it.id })

        // For n3: upstream is n1, n2
        val upstream3 = WorkflowVariableResolver.getUpstreamNodes("n3", nodes)
        assertEquals(listOf("n1", "n2"), upstream3.map { it.id })

        // For n4: upstream is n1, n2, n3
        val upstream4 = WorkflowVariableResolver.getUpstreamNodes("n4", nodes)
        assertEquals(listOf("n1", "n2", "n3"), upstream4.map { it.id })
    }

    @Test
    fun testBranchingPipelineUpstreamNodesResolution() {
        val trigger = WorkflowNode("t1", "SMS Trigger", NodeType.SMS_TRIGGER)
        val branchA1 = WorkflowNode("a1", "JSON Parser", NodeType.JSON_PARSER)
        val branchA2 = WorkflowNode("a2", "HTTP Webhook", NodeType.HTTP_ACTION)
        val branchB1 = WorkflowNode("b1", "Send SMS", NodeType.SEND_SMS_ACTION)

        val nodes = listOf(trigger, branchA1, branchA2, branchB1)
        val connections =
            listOf(
                WorkflowConnection(fromNodeId = "t1", toNodeId = "a1"),
                WorkflowConnection(fromNodeId = "a1", toNodeId = "a2"),
                WorkflowConnection(fromNodeId = "t1", toNodeId = "b1"),
            )

        // Upstream for a2 should be t1 and a1 (Branch B1 must NOT be included)
        val upstreamA2 = WorkflowVariableResolver.getUpstreamNodes("a2", nodes, connections)
        assertEquals(listOf("t1", "a1"), upstreamA2.map { it.id })

        // Upstream for b1 should be only t1 (Branch A nodes must NOT be included)
        val upstreamB1 = WorkflowVariableResolver.getUpstreamNodes("b1", nodes, connections)
        assertEquals(listOf("t1"), upstreamB1.map { it.id })
    }

    @Test
    fun testNodeOutputVariablesContract() {
        // SMS Trigger contract
        val smsNode = WorkflowNode("n1", "SMS Trigger", NodeType.SMS_TRIGGER)
        val smsVars = WorkflowVariableResolver.getNodeOutputVariables(smsNode)
        assertTrue(smsVars.contains("sender"))
        assertTrue(smsVars.contains("body"))
        assertTrue(smsVars.contains("header"))

        // Call Trigger contract
        val callNode = WorkflowNode("n2", "Call Trigger", "dev.zophop.plugin.call.CALL_TRIGGER")
        val callVars = WorkflowVariableResolver.getNodeOutputVariables(callNode)
        assertTrue(callVars.contains("caller_number"))
        assertTrue(callVars.contains("sender"))

        // HTTP Action contract
        val httpNode = WorkflowNode("n3", "HTTP Action", NodeType.HTTP_ACTION)
        val httpVars = WorkflowVariableResolver.getNodeOutputVariables(httpNode)
        assertTrue(httpVars.contains("http_response"))
        assertTrue(httpVars.contains("http_status"))

        // Call Action contract
        val disconnectNode = WorkflowNode("n4", "Disconnect", "dev.zophop.plugin.call.DISCONNECT_CALL_ACTION")
        val disconnectVars = WorkflowVariableResolver.getNodeOutputVariables(disconnectNode)
        assertTrue(disconnectVars.contains("call_status"))

        // Action node without outputs
        val sendSmsNode = WorkflowNode("n5", "Send SMS", NodeType.SEND_SMS_ACTION)
        val sendSmsVars = WorkflowVariableResolver.getNodeOutputVariables(sendSmsNode)
        assertTrue(sendSmsVars.isEmpty())
    }

    @Test
    fun testManualTriggerOutputVariablesContract() {
        val manualNode = WorkflowNode("m1", "Manual Trigger", NodeType.MANUAL_TRIGGER)
        val manualVars = WorkflowVariableResolver.getNodeOutputVariables(manualNode)

        assertTrue("Must declare body", manualVars.contains("body"))
        assertTrue("Must declare text", manualVars.contains("text"))
        assertTrue("Must declare timestamp", manualVars.contains("timestamp"))
        assertFalse("Manual trigger must NOT include sender", manualVars.contains("sender"))
        assertFalse("Manual trigger must NOT include caller_number", manualVars.contains("caller_number"))
        assertFalse("Manual trigger must NOT include package_name", manualVars.contains("package_name"))
    }

    @Test
    fun testCronTriggerOutputVariablesContract() {
        val cronNode = WorkflowNode("c1", "Cron Schedule", NodeType.CRON_TRIGGER)
        val cronVars = WorkflowVariableResolver.getNodeOutputVariables(cronNode)

        assertTrue("Must declare body", cronVars.contains("body"))
        assertTrue("Must declare text", cronVars.contains("text"))
        assertTrue("Must declare sender", cronVars.contains("sender"))
        assertTrue("Must declare timestamp", cronVars.contains("timestamp"))
    }

    @Test
    fun testConditionCheckNodesOutputVariablesContract() {
        // VPN Check
        val vpnVars = WorkflowVariableResolver.getNodeOutputVariables(WorkflowNode("v1", "VPN", NodeType.VPN_CHECK))
        assertTrue(vpnVars.contains("vpn_connected"))
        assertTrue(vpnVars.contains("vpn_interface"))
        assertTrue(vpnVars.contains("vpn_type"))

        // GPS Check
        val gpsVars = WorkflowVariableResolver.getNodeOutputVariables(WorkflowNode("g1", "GPS", NodeType.GPS_CHECK))
        assertTrue(gpsVars.contains("gps_enabled"))
        assertTrue(gpsVars.contains("latitude"))
        assertTrue(gpsVars.contains("longitude"))
        assertTrue(gpsVars.contains("accuracy"))
        assertTrue(gpsVars.contains("maps_url"))
        assertTrue(gpsVars.contains("location_provider"))

        // Internet Check
        val netVars = WorkflowVariableResolver.getNodeOutputVariables(WorkflowNode("i1", "Net", NodeType.INTERNET_CHECK))
        assertTrue(netVars.contains("internet_connected"))
        assertTrue(netVars.contains("network_type"))
        assertTrue(netVars.contains("is_metered"))

        // Bluetooth Check
        val btVars = WorkflowVariableResolver.getNodeOutputVariables(WorkflowNode("b1", "BT", NodeType.BLUETOOTH_CHECK))
        assertTrue(btVars.contains("bluetooth_enabled"))
        assertTrue(btVars.contains("bluetooth_connected"))
        assertTrue(btVars.contains("connected_device_name"))
        assertTrue(btVars.contains("connected_device_address"))
        assertTrue(btVars.contains("bonded_device_count"))

        // SIM Check
        val simVars = WorkflowVariableResolver.getNodeOutputVariables(WorkflowNode("s1", "SIM", NodeType.SIM_CHECK))
        assertTrue(simVars.contains("sim_ready"))
        assertTrue(simVars.contains("carrier_name"))
        assertTrue(simVars.contains("sim_count"))
        assertTrue(simVars.contains("is_roaming"))

        // Battery Check
        val batVars = WorkflowVariableResolver.getNodeOutputVariables(WorkflowNode("bat1", "Battery", NodeType.BATTERY_CHECK))
        assertTrue(batVars.contains("battery_percent"))
        assertTrue(batVars.contains("is_charging"))
        assertTrue(batVars.contains("charging_status"))
    }

    @Test
    fun testDynamicOutputKeyFromNodeConfigIsIncluded() {
        val parserWithCustomKey =
            WorkflowNode(
                id = "p1",
                name = "Regex Parser",
                type = NodeType.REGEX_PARSER,
                configJson = """{"regex":"\\d+","outputKey":"account_pin"}""",
            )

        val vars = WorkflowVariableResolver.getNodeOutputVariables(parserWithCustomKey)
        assertTrue(vars.contains("code"))
        assertTrue(vars.contains("otp"))
        assertTrue(vars.contains("account_pin"))
    }

    @Test
    fun testManifestProducedVariablesMethod() {
        val manifest =
            NodePluginManifest(
                typeId = "custom.parser",
                displayName = "Custom Parser",
                description = "Parses custom payload",
                category = NodeCategory.EXTRACTION,
                outputVariables = listOf("fixed_field_1", "fixed_field_2"),
            )

        val varsWithoutConfig = manifest.getProducedVariables(null)
        assertEquals(listOf("fixed_field_1", "fixed_field_2"), varsWithoutConfig)

        val varsWithDynamicOutputKey = manifest.getProducedVariables("""{"outputKey":"extracted_token"}""")
        assertEquals(listOf("fixed_field_1", "fixed_field_2", "extracted_token"), varsWithDynamicOutputKey)
    }

    @Test
    fun testAvailableUpstreamVariablesAreScopedStrictlyToUpstream() {
        val trigger = WorkflowNode("t1", "SMS Trigger", NodeType.SMS_TRIGGER)
        val parser =
            WorkflowNode(
                id = "p1",
                name = "Regex Parser",
                type = NodeType.REGEX_PARSER,
                configJson = """{"outputKey":"auth_code"}""",
            )
        val httpAction = WorkflowNode("h1", "HTTP Webhook", NodeType.HTTP_ACTION)
        val condition = WorkflowNode("c1", "Condition", NodeType.CONDITION)

        val nodes = listOf(trigger, parser, httpAction, condition)
        val connections =
            listOf(
                WorkflowConnection("t1", "p1"),
                WorkflowConnection("p1", "h1"),
                WorkflowConnection("h1", "c1"),
            )

        // 1. For Trigger itself: 0 available upstream variables
        val triggerUpstreamVars = WorkflowVariableResolver.getAvailableUpstreamVariables("t1", nodes, connections)
        assertTrue(triggerUpstreamVars.isEmpty())

        // 2. For Parser: only Trigger variables (sender, body, header, text)
        val parserUpstreamVars = WorkflowVariableResolver.getAvailableUpstreamVariables("p1", nodes, connections)
        assertTrue(parserUpstreamVars.contains("sender"))
        assertTrue(parserUpstreamVars.contains("body"))
        assertFalse(parserUpstreamVars.contains("auth_code")) // produced by parser itself, not upstream
        assertFalse(parserUpstreamVars.contains("http_response")) // downstream
        assertFalse(parserUpstreamVars.contains("caller_number")) // call trigger, unrelated

        // 3. For HTTP Action: Trigger variables + Parser variables
        val httpUpstreamVars = WorkflowVariableResolver.getAvailableUpstreamVariables("h1", nodes, connections)
        assertTrue(httpUpstreamVars.contains("sender"))
        assertTrue(httpUpstreamVars.contains("body"))
        assertTrue(httpUpstreamVars.contains("auth_code"))
        assertTrue(httpUpstreamVars.contains("code"))
        assertFalse(httpUpstreamVars.contains("http_response")) // produced by HTTP action itself
        assertFalse(httpUpstreamVars.contains("caller_number"))

        // 4. For Condition: Trigger + Parser + HTTP variables
        val conditionUpstreamVars = WorkflowVariableResolver.getAvailableUpstreamVariables("c1", nodes, connections)
        assertTrue(conditionUpstreamVars.contains("sender"))
        assertTrue(conditionUpstreamVars.contains("body"))
        assertTrue(conditionUpstreamVars.contains("auth_code"))
        assertTrue(conditionUpstreamVars.contains("http_response"))
        assertTrue(conditionUpstreamVars.contains("http_status"))
        assertFalse(conditionUpstreamVars.contains("caller_number"))
    }

    @Test
    fun testCircularWorkflowConnectionsSafelyTerminatesWithoutLooping() {
        val n1 = WorkflowNode("n1", "Trigger", NodeType.SMS_TRIGGER)
        val n2 = WorkflowNode("n2", "Step 2", NodeType.REGEX_PARSER, configJson = """{"outputKey":"token"}""")
        val n3 = WorkflowNode("n3", "Step 3", NodeType.CONDITION)

        val nodes = listOf(n1, n2, n3)
        // Circular graph: n1 -> n2 -> n3 -> n1
        val circularConnections =
            listOf(
                WorkflowConnection("n1", "n2"),
                WorkflowConnection("n2", "n3"),
                WorkflowConnection("n3", "n1"),
            )

        // Must terminate safely and return upstream nodes without hanging or throwing StackOverflow
        val upstreamN2 = WorkflowVariableResolver.getUpstreamNodes("n2", nodes, circularConnections)
        assertTrue(upstreamN2.any { it.id == "n1" })
        assertTrue(upstreamN2.any { it.id == "n3" })

        val vars = WorkflowVariableResolver.getAvailableUpstreamVariables("n2", nodes, circularConnections)
        assertTrue(vars.isNotEmpty())
    }
}
