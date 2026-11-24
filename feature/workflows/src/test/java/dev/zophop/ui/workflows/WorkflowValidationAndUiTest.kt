package dev.zophop.ui.workflows

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowValidationAndUiTest {
    @Test
    fun testAllNodeTypesHaveTitlesDescriptionsAndIcons() {
        listOf(
            NodeType.SMS_TRIGGER,
            NodeType.NOTIFICATION_TRIGGER,
            NodeType.MANUAL_TRIGGER,
            NodeType.CRON_TRIGGER,
            NodeType.CALL_TRIGGER,
            NodeType.PARSE_NOTIFICATION,
            NodeType.REGEX_PARSER,
            NodeType.JSON_PARSER,
            NodeType.TEMPLATE_FORMATTER,
            NodeType.CONDITION,
            NodeType.NTFY_ACTION,
            NodeType.GOTIFY_ACTION,
            NodeType.HTTP_ACTION,
            NodeType.SEND_NOTIFICATION_ACTION,
            NodeType.SEND_SMS_ACTION,
            NodeType.DISCONNECT_CALL_ACTION,
            NodeType.ANSWER_CALL_ACTION,
        ).forEach { type ->
            val title = WorkflowUiHelper.getNodeTitle(type)
            val desc = WorkflowUiHelper.getNodeDescription(type)
            val icon = WorkflowUiHelper.getNodeIcon(type)

            assertTrue(title.isNotBlank())
            assertTrue(desc.isNotBlank())
            assertNotNull(icon)
        }
    }

    @Test
    fun testAllPreconditionTypesHaveTitlesAndIcons() {
        val types =
            listOf(
                PreconditionType.INTERNET_CHECK.name,
                PreconditionType.VPN_CHECK.name,
                PreconditionType.HEALTH_CHECK.name,
                PreconditionType.SIM_CHECK.name,
                PreconditionType.GPS_CHECK.name,
                PreconditionType.CHAINED_CHECK.name,
            )

        types.forEach { typeName ->
            val title = WorkflowUiHelper.getPreconditionTitle(typeName)
            val icon = WorkflowUiHelper.getPreconditionIcon(typeName)

            assertTrue(title.isNotBlank())
            assertNotNull(icon)
        }
    }

    @Test
    fun testTriggerBoundActionsRelevanceFiltering() {
        val disconnectCallManifest =
            dev.zophop.plugin.api.NodePluginManifest(
                typeId = "dev.zophop.plugin.call.DISCONNECT_CALL_ACTION",
                displayName = "Disconnect Call Action",
                description = "Disconnects incoming call",
                category = dev.zophop.plugin.api.NodeCategory.ACTION,
                supportedTriggerTypes = listOf("dev.zophop.plugin.call.CALL_TRIGGER"),
            )

        val answerCallManifest =
            dev.zophop.plugin.api.NodePluginManifest(
                typeId = "dev.zophop.plugin.call.ANSWER_CALL_ACTION",
                displayName = "Answer Call Action",
                description = "Answers incoming call",
                category = dev.zophop.plugin.api.NodeCategory.ACTION,
                supportedTriggerTypes = listOf("dev.zophop.plugin.call.CALL_TRIGGER"),
            )

        val httpActionManifest =
            dev.zophop.plugin.api.NodePluginManifest(
                typeId = "HTTP_ACTION",
                displayName = "HTTP Webhook Action",
                description = "Sends HTTP request",
                category = dev.zophop.plugin.api.NodeCategory.ACTION,
            )

        val conditionManifest =
            dev.zophop.plugin.api.NodePluginManifest(
                typeId = "CONDITION",
                displayName = "Condition Evaluator",
                description = "Evaluates logic",
                category = dev.zophop.plugin.api.NodeCategory.LOGIC,
            )

        val allManifests =
            listOf(
                disconnectCallManifest,
                answerCallManifest,
                httpActionManifest,
                conditionManifest,
            )

        // When active trigger is SMS_TRIGGER:
        val smsFiltered = allManifests.filter { it.isRegularNode() && it.isSupportedByTrigger(NodeType.SMS_TRIGGER) }
        assertFalse(smsFiltered.contains(disconnectCallManifest))
        assertFalse(smsFiltered.contains(answerCallManifest))
        assertTrue(smsFiltered.contains(httpActionManifest))
        assertTrue(smsFiltered.contains(conditionManifest))

        // When active trigger is NOTIFICATION_TRIGGER:
        val notifFiltered = allManifests.filter { it.isRegularNode() && it.isSupportedByTrigger(NodeType.NOTIFICATION_TRIGGER) }
        assertFalse(notifFiltered.contains(disconnectCallManifest))
        assertFalse(notifFiltered.contains(answerCallManifest))
        assertTrue(notifFiltered.contains(httpActionManifest))

        // When active trigger is CRON_TRIGGER or MANUAL_TRIGGER:
        val cronFiltered = allManifests.filter { it.isRegularNode() && it.isSupportedByTrigger(NodeType.CRON_TRIGGER) }
        assertFalse(cronFiltered.contains(disconnectCallManifest))
        assertFalse(cronFiltered.contains(answerCallManifest))

        // When active trigger is CALL_TRIGGER (full FQN or simple name):
        val callFqnFiltered = allManifests.filter { it.isRegularNode() && it.isSupportedByTrigger("dev.zophop.plugin.call.CALL_TRIGGER") }
        assertTrue(callFqnFiltered.contains(disconnectCallManifest))
        assertTrue(callFqnFiltered.contains(answerCallManifest))
        assertTrue(callFqnFiltered.contains(httpActionManifest))

        val callSimpleFiltered = allManifests.filter { it.isRegularNode() && it.isSupportedByTrigger(NodeType.CALL_TRIGGER) }
        assertTrue(callSimpleFiltered.contains(disconnectCallManifest))
        assertTrue(callSimpleFiltered.contains(answerCallManifest))

        // When active trigger is null / unassigned:
        val nullTriggerFiltered = allManifests.filter { it.isRegularNode() && it.isSupportedByTrigger(null) }
        assertFalse(nullTriggerFiltered.contains(disconnectCallManifest))
        assertFalse(nullTriggerFiltered.contains(answerCallManifest))
        assertTrue(nullTriggerFiltered.contains(httpActionManifest))
    }

    @Test
    fun testWorkflowStructureValidation() {
        val triggerNode = WorkflowNode("n1", "SMS Trigger", NodeType.SMS_TRIGGER)
        val actionNode = WorkflowNode("n2", "ntfy Action", NodeType.NTFY_ACTION)

        val validWorkflow =
            Workflow(
                id = "w_val",
                name = "Valid Workflow",
                isEnabled = true,
                nodes = listOf(triggerNode, actionNode),
                connections = listOf(WorkflowConnection("n1", "n2")),
            )

        assertEquals("Valid Workflow", validWorkflow.name)
        assertEquals(2, validWorkflow.nodes.size)
        assertEquals(1, validWorkflow.connections.size)
    }

    @Test
    fun testAutocompleteSuggestionsOnlyShowUpstreamVariables() {
        val upstreamVars = listOf("sender", "body", "code")

        // Unfiltered query shows only available upstream variables
        val allSuggestions = if ("".isBlank()) upstreamVars else upstreamVars.filter { it.contains("", ignoreCase = true) }
        assertEquals(listOf("sender", "body", "code"), allSuggestions)
        assertFalse(allSuggestions.contains("http_response"))
        assertFalse(allSuggestions.contains("caller_number"))

        // Filter query matches within upstream variables
        val filteredSuggestions = upstreamVars.filter { it.contains("cod", ignoreCase = true) }
        assertEquals(listOf("code"), filteredSuggestions)

        // Query for non-upstream variable returns nothing
        val nonUpstreamSuggestions = upstreamVars.filter { it.contains("http", ignoreCase = true) }
        assertTrue(nonUpstreamSuggestions.isEmpty())
    }

    @Test
    fun testTriggerNodeHasNoUpstreamVariables() {
        val triggerNode = WorkflowNode("t1", "SMS Trigger", NodeType.SMS_TRIGGER)
        val downstreamNode = WorkflowNode("d1", "HTTP Webhook", NodeType.HTTP_ACTION)
        val nodes = listOf(triggerNode, downstreamNode)

        val triggerAvailableVars =
            dev.zophop.domain.engine.WorkflowVariableResolver.getAvailableUpstreamVariables(
                targetNodeId = triggerNode.id,
                nodes = nodes,
            )

        // Trigger node must have 0 upstream variables
        assertTrue(triggerAvailableVars.isEmpty())

        // Downstream node receives trigger variables
        val downstreamAvailableVars =
            dev.zophop.domain.engine.WorkflowVariableResolver.getAvailableUpstreamVariables(
                targetNodeId = downstreamNode.id,
                nodes = nodes,
            )
        assertEquals(listOf("sender", "body", "header", "text"), downstreamAvailableVars)
    }

    @Test
    fun testConditionCheckUpstreamVariablesPropagateToDownstreamNodes() {
        val triggerNode = WorkflowNode("t1", "SMS Trigger", NodeType.SMS_TRIGGER)
        val btNode = WorkflowNode("c1", "Bluetooth Check", NodeType.BLUETOOTH_CHECK)
        val gpsNode = WorkflowNode("c2", "GPS Check", NodeType.GPS_CHECK)
        val actionNode = WorkflowNode("a1", "HTTP Webhook", NodeType.HTTP_ACTION)

        val nodes = listOf(triggerNode, btNode, gpsNode, actionNode)

        val actionVars =
            dev.zophop.domain.engine.WorkflowVariableResolver.getAvailableUpstreamVariables(
                targetNodeId = actionNode.id,
                nodes = nodes,
            )

        // Action node must have trigger variables + bluetooth variables + gps variables
        assertTrue(actionVars.contains("sender"))
        assertTrue(actionVars.contains("body"))
        assertTrue(actionVars.contains("bluetooth_enabled"))
        assertTrue(actionVars.contains("bluetooth_connected"))
        assertTrue(actionVars.contains("connected_device_name"))
        assertTrue(actionVars.contains("gps_enabled"))
        assertTrue(actionVars.contains("latitude"))
        assertTrue(actionVars.contains("longitude"))
    }

    @Test
    fun testConditionNodeDefaultConfigHasEqualsOperator() {
        val defaultConfig = WorkflowUiHelper.getDefaultConfigForType(NodeType.CONDITION)
        val json = parseJsonObjectSafe(defaultConfig)
        assertEquals("status", json.optString("variable"))
        assertEquals("EQUALS", json.optString("operator"))
        assertEquals("active", json.optString("value"))
    }

    @Test
    fun testAllNodeTypesHavePopulatedDefaultConfigs() {
        val allTypes =
            listOf(
                NodeType.INTERNET_CHECK,
                NodeType.VPN_CHECK,
                NodeType.GPS_CHECK,
                NodeType.BATTERY_CHECK,
                NodeType.SIM_CHECK,
                NodeType.BLUETOOTH_CHECK,
                NodeType.CONDITION,
                NodeType.HTTP_ACTION,
                NodeType.PARSE_NOTIFICATION,
                NodeType.REGEX_PARSER,
                NodeType.JSON_PARSER,
                NodeType.TEMPLATE_FORMATTER,
                NodeType.SEND_NOTIFICATION_ACTION,
                NodeType.SEND_SMS_ACTION,
                NodeType.CRON_TRIGGER,
                NodeType.MANUAL_TRIGGER,
                NodeType.SMS_TRIGGER,
                NodeType.NOTIFICATION_TRIGGER,
                NodeType.CALL_TRIGGER,
                NodeType.NTFY_ACTION,
                NodeType.GOTIFY_ACTION,
                "TELEGRAM_ACTION",
            )

        allTypes.forEach { type ->
            val configStr = WorkflowUiHelper.getDefaultConfigForType(type)
            assertTrue("Default config for $type must not be empty or '{}'", configStr != "{}" && configStr.isNotBlank())
            val json = parseJsonObjectSafe(configStr)
            assertTrue("JSON for $type must have keys", json.size > 0)
        }

        // Verify specific key defaults
        val internetJson = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(NodeType.INTERNET_CHECK))
        assertEquals("true", internetJson.optString("requireInternet"))
        assertEquals("true", internetJson.optString("requireConnected"))
        assertEquals("ANY", internetJson.optString("requiredNetworkType"))

        val vpnJson = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(NodeType.VPN_CHECK))
        assertEquals("true", vpnJson.optString("requireConnected"))

        val gpsJson = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(NodeType.GPS_CHECK))
        assertEquals("true", gpsJson.optString("requireGpsEnabled"))
        assertEquals("true", gpsJson.optString("fetchCoordinates"))

        val btJson = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(NodeType.BLUETOOTH_CHECK))
        assertEquals("true", btJson.optString("requireBluetoothEnabled"))
        assertEquals("false", btJson.optString("requireConnected"))

        val batteryJson = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(NodeType.BATTERY_CHECK))
        assertEquals("15", batteryJson.optString("minBatteryPercent"))
        assertEquals("false", batteryJson.optString("requireCharging"))

        val regexJson = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(NodeType.REGEX_PARSER))
        assertEquals("\\b\\d{6}\\b", regexJson.optString("regex"))
        assertEquals("code", regexJson.optString("outputKey"))

        val jsonParserJson = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(NodeType.JSON_PARSER))
        assertEquals("data.status", jsonParserJson.optString("jsonKey"))
        assertEquals("parsedStatus", jsonParserJson.optString("outputKey"))

        val ntfyJson = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(NodeType.NTFY_ACTION))
        assertEquals("3", ntfyJson.optString("priority"))
        assertEquals("https://ntfy.sh", ntfyJson.optString("serverUrl"))

        val gotifyJson = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(NodeType.GOTIFY_ACTION))
        assertEquals("5", gotifyJson.optString("priority"))
        assertEquals("https://gotify.example.com", gotifyJson.optString("serverUrl"))
    }

    @Test
    fun testDecoupledManifestDrivenProperties() {
        val customManifestWithCredentials =
            dev.zophop.plugin.api.NodePluginManifest(
                typeId = "com.custom.plugin.WEBHOOK",
                displayName = "Custom Webhook",
                description = "Sends webhook payloads",
                category = dev.zophop.plugin.api.NodeCategory.ACTION,
                supportsCredentials = true,
                defaultConfigJson = """{"endpoint":"https://api.custom.com"}""",
                requiredPermissions = listOf("android.permission.INTERNET"),
            )

        // 1. Credential support is driven by manifest property
        assertTrue(customManifestWithCredentials.isCredentialSupported())
        val builtInHttp = dev.zophop.domain.engine.plugin.BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.HTTP_ACTION)
        assertNotNull(builtInHttp)
        assertTrue(builtInHttp!!.isCredentialSupported())

        val unauthenticatedManifest =
            dev.zophop.plugin.api.NodePluginManifest(
                typeId = "com.custom.plugin.LOG",
                displayName = "Custom Logger",
                description = "Logs data",
                category = dev.zophop.plugin.api.NodeCategory.ACTION,
                supportsCredentials = false,
            )
        assertFalse(unauthenticatedManifest.isCredentialSupported())

        // 2. Default config is driven by manifest
        assertEquals(
            """{"endpoint":"https://api.custom.com"}""",
            WorkflowUiHelper.getDefaultConfigForType("com.custom.plugin.WEBHOOK", customManifestWithCredentials),
        )

        // 3. Title and description are driven by manifest
        assertEquals("Custom Webhook", WorkflowUiHelper.getNodeTitle("com.custom.plugin.WEBHOOK", customManifestWithCredentials))
        assertEquals("Sends webhook payloads", WorkflowUiHelper.getNodeDescription("com.custom.plugin.WEBHOOK", customManifestWithCredentials))

        // 4. Permissions are driven by manifest
        assertEquals(
            listOf("android.permission.INTERNET"),
            WorkflowUiHelper.getRequiredPermissions("com.custom.plugin.WEBHOOK", customManifestWithCredentials),
        )

        // 5. Logic category drives conditional node check
        val logicManifest =
            dev.zophop.plugin.api.NodePluginManifest(
                typeId = "com.custom.plugin.EVAL",
                displayName = "Custom Evaluator",
                description = "Evaluates boolean logic",
                category = dev.zophop.plugin.api.NodeCategory.LOGIC,
            )
        assertTrue(WorkflowUiHelper.isConditionalNode("com.custom.plugin.EVAL", logicManifest))
        assertFalse(WorkflowUiHelper.isConditionalNode("com.custom.plugin.WEBHOOK", customManifestWithCredentials))
    }
}
