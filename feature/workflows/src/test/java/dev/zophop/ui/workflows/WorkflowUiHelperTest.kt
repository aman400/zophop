package dev.zophop.ui.workflows

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.PreconditionType
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowUiHelperTest {
    private val mockContext: Context = object : ContextWrapper(null) {}

    @Test
    fun testNodeTitlesAndDescriptions() {
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
            NodeType.VPN_CHECK,
            NodeType.GPS_CHECK,
            NodeType.INTERNET_CHECK,
            NodeType.BLUETOOTH_CHECK,
            NodeType.SIM_CHECK,
            NodeType.BATTERY_CHECK,
        ).forEach { type ->
            val title = WorkflowUiHelper.getNodeTitle(type)
            val desc = WorkflowUiHelper.getNodeDescription(type)
            val icon = WorkflowUiHelper.getNodeIcon(type)
            assertNotNull(title)
            assertNotNull(desc)
            assertNotNull(icon)
        }
    }

    @Test
    fun testNtfyAndGotifyTitlesComeFromModules() {
        val ntfyTitle = WorkflowUiHelper.getNodeTitle(NodeType.NTFY_ACTION)
        assertEquals("ntfy Push Action", ntfyTitle)

        val ntfyDesc = WorkflowUiHelper.getNodeDescription(NodeType.NTFY_ACTION)
        assertEquals("Sends payload to a ntfy.sh or self-hosted server", ntfyDesc)

        val gotifyTitle = WorkflowUiHelper.getNodeTitle(NodeType.GOTIFY_ACTION)
        assertEquals("Gotify Push Action", gotifyTitle)

        val gotifyDesc = WorkflowUiHelper.getNodeDescription(NodeType.GOTIFY_ACTION)
        assertEquals("Sends payload to a Gotify push server", gotifyDesc)
    }

    @Test
    fun testPreconditionHelpers() {
        val internetTitle = WorkflowUiHelper.getPreconditionTitle(PreconditionType.INTERNET_CHECK.name)
        assertEquals("Network Internet Check", internetTitle)

        val internetIcon = WorkflowUiHelper.getPreconditionIcon(PreconditionType.INTERNET_CHECK.name)
        assertNotNull(internetIcon)
    }

    @Test
    fun testRequiredPermissionsForNodeTypes() {
        val notifPerms = WorkflowUiHelper.getRequiredPermissions(NodeType.NOTIFICATION_TRIGGER)
        assertTrue(notifPerms.contains("NOTIFICATION_LISTENER"))

        val smsTriggerPerms = WorkflowUiHelper.getRequiredPermissions(NodeType.SMS_TRIGGER)
        assertTrue(smsTriggerPerms.contains(Manifest.permission.RECEIVE_SMS))

        val sendSmsPerms = WorkflowUiHelper.getRequiredPermissions(NodeType.SEND_SMS_ACTION)
        assertTrue(sendSmsPerms.contains(Manifest.permission.SEND_SMS))

        val gpsPerms = WorkflowUiHelper.getRequiredPermissions(NodeType.GPS_CHECK)
        assertTrue(gpsPerms.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(gpsPerms.contains(Manifest.permission.ACCESS_COARSE_LOCATION))

        val simPerms = WorkflowUiHelper.getRequiredPermissions(NodeType.SIM_CHECK)
        assertTrue(simPerms.contains(Manifest.permission.READ_PHONE_STATE))

        val callTriggerPerms = WorkflowUiHelper.getRequiredPermissions(NodeType.CALL_TRIGGER)
        assertTrue(callTriggerPerms.contains(Manifest.permission.READ_PHONE_STATE))

        val answerCallPerms = WorkflowUiHelper.getRequiredPermissions(NodeType.ANSWER_CALL_ACTION)
        assertTrue(answerCallPerms.contains(Manifest.permission.ANSWER_PHONE_CALLS))

        // Node with manifest permissions
        val manifest =
            NodePluginManifest(
                typeId = "CUSTOM_NODE",
                displayName = "Custom Node",
                description = "Custom Desc",
                category = NodeCategory.ACTION,
                requiredPermissions = listOf("custom.permission.FOO", "custom.permission.BAR"),
            )
        val customPerms = WorkflowUiHelper.getRequiredPermissions("CUSTOM_NODE", manifest)
        assertTrue(customPerms.contains("custom.permission.FOO"))
        assertTrue(customPerms.contains("custom.permission.BAR"))
    }

    @Test
    fun testPermissionInstructionDataContents() {
        // Notification listener
        val notifInstruction =
            WorkflowUiHelper.getPermissionInstruction(
                context = mockContext,
                missingPermissions = listOf("NOTIFICATION_LISTENER"),
                nodeType = NodeType.NOTIFICATION_TRIGGER,
            )
        assertEquals("Notification Access Required", notifInstruction.title)
        assertTrue(notifInstruction.message.contains("1. Tap 'Open Settings'"))
        assertTrue(notifInstruction.message.contains("ZopHop"))

        // Location
        val locationInstruction =
            WorkflowUiHelper.getPermissionInstruction(
                context = mockContext,
                missingPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
                nodeType = NodeType.GPS_CHECK,
            )
        assertEquals("Location Permission Required", locationInstruction.title)
        assertTrue(locationInstruction.message.contains("Location"))
        assertTrue(locationInstruction.message.contains("Allow only while using the app"))

        // SMS
        val smsInstruction =
            WorkflowUiHelper.getPermissionInstruction(
                context = mockContext,
                missingPermissions = listOf(Manifest.permission.RECEIVE_SMS),
                nodeType = NodeType.SMS_TRIGGER,
            )
        assertEquals("SMS Permission Required", smsInstruction.title)
        assertTrue(smsInstruction.message.contains("SMS"))

        // Phone
        val phoneInstruction =
            WorkflowUiHelper.getPermissionInstruction(
                context = mockContext,
                missingPermissions = listOf(Manifest.permission.READ_PHONE_STATE),
                nodeType = NodeType.SIM_CHECK,
            )
        assertEquals("Phone & Call Permission Required", phoneInstruction.title)
        assertTrue(phoneInstruction.message.contains("Phone"))

        // Bluetooth
        val btInstruction =
            WorkflowUiHelper.getPermissionInstruction(
                context = mockContext,
                missingPermissions = listOf("android.permission.BLUETOOTH_CONNECT"),
                nodeType = NodeType.BLUETOOTH_CHECK,
            )
        assertEquals("Nearby Devices Permission Required", btInstruction.title)
        assertTrue(btInstruction.message.contains("Nearby devices"))

        // POST_NOTIFICATIONS
        val postNotifInstruction =
            WorkflowUiHelper.getPermissionInstruction(
                context = mockContext,
                missingPermissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                nodeType = NodeType.SEND_NOTIFICATION_ACTION,
            )
        assertEquals("Notification Permission Required", postNotifInstruction.title)
        assertTrue(postNotifInstruction.message.contains("Notifications"))

        // External plugin
        val pluginInstruction =
            WorkflowUiHelper.getPermissionInstruction(
                context = mockContext,
                missingPermissions = listOf("custom.plugin.perm"),
                nodeType = "EXTERNAL_PLUGIN",
                pluginPackage = "com.external.plugin",
            )
        assertEquals("Plugin Permission Required", pluginInstruction.title)
        assertTrue(pluginInstruction.message.contains("plugin application"))

        // Fallback
        val genericInstruction =
            WorkflowUiHelper.getPermissionInstruction(
                context = mockContext,
                missingPermissions = listOf("android.permission.CAMERA"),
                nodeType = "CAMERA_STEP",
            )
        assertEquals("Permission Required", genericInstruction.title)
        assertTrue(genericInstruction.message.contains("CAMERA"))
    }

    @Test
    fun testIsConditionalNodeClassification() {
        // Condition & Check nodes must be identified as conditional nodes
        val conditionalNodes =
            listOf(
                NodeType.CONDITION,
                NodeType.VPN_CHECK,
                NodeType.GPS_CHECK,
                NodeType.INTERNET_CHECK,
                NodeType.BLUETOOTH_CHECK,
                NodeType.SIM_CHECK,
                NodeType.BATTERY_CHECK,
                "CUSTOM_CHECK",
                "CUSTOM_CONDITION_EVALUATOR",
            )
        conditionalNodes.forEach { type ->
            assertTrue("Expected $type to be conditional node", WorkflowUiHelper.isConditionalNode(type))
        }

        // Custom plugin manifest with LOGIC category
        val logicManifest =
            NodePluginManifest(
                typeId = "CUSTOM_LOGIC",
                displayName = "Custom Logic",
                description = "Logic node",
                category = NodeCategory.LOGIC,
            )
        assertTrue(WorkflowUiHelper.isConditionalNode("CUSTOM_LOGIC", logicManifest))

        // Non-conditional nodes (Actions, Triggers, Parsers) must NOT be conditional
        val nonConditionalNodes =
            listOf(
                NodeType.SMS_TRIGGER,
                NodeType.NOTIFICATION_TRIGGER,
                NodeType.MANUAL_TRIGGER,
                NodeType.CRON_TRIGGER,
                NodeType.CALL_TRIGGER,
                NodeType.HTTP_ACTION,
                NodeType.NTFY_ACTION,
                NodeType.GOTIFY_ACTION,
                NodeType.SEND_SMS_ACTION,
                NodeType.SEND_NOTIFICATION_ACTION,
                NodeType.ANSWER_CALL_ACTION,
                NodeType.DISCONNECT_CALL_ACTION,
                NodeType.PARSE_NOTIFICATION,
                NodeType.REGEX_PARSER,
                NodeType.JSON_PARSER,
                NodeType.TEMPLATE_FORMATTER,
            )
        nonConditionalNodes.forEach { type ->
            org.junit.Assert.assertFalse("Expected $type to NOT be conditional node", WorkflowUiHelper.isConditionalNode(type))
        }

        val actionManifest =
            NodePluginManifest(
                typeId = "CUSTOM_ACTION",
                displayName = "Custom Action",
                description = "Action node",
                category = NodeCategory.ACTION,
            )
        org.junit.Assert.assertFalse(WorkflowUiHelper.isConditionalNode("CUSTOM_ACTION", actionManifest))
    }
}
