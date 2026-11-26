package dev.zophop.domain.engine

import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleTriggerWorkflowValidationTest {
    @Test
    fun testNodeCategoryTriggerVersusRegularHelpers() {
        val triggerManifest =
            NodePluginManifest(
                typeId = "com.example.CALL_TRIGGER",
                displayName = "Call Trigger",
                description = "Triggers on call",
                category = NodeCategory.TRIGGER,
            )

        val actionManifest =
            NodePluginManifest(
                typeId = "com.example.DISCONNECT_ACTION",
                displayName = "Disconnect Call",
                description = "Disconnects active call",
                category = NodeCategory.ACTION,
            )

        val parserManifest =
            NodePluginManifest(
                typeId = "com.example.REGEX_PARSER",
                displayName = "Regex Parser",
                description = "Parses regex",
                category = NodeCategory.EXTRACTION,
            )

        assertTrue(triggerManifest.isTriggerNode())
        assertFalse(triggerManifest.isRegularNode())

        assertFalse(actionManifest.isTriggerNode())
        assertTrue(actionManifest.isRegularNode())

        assertFalse(parserManifest.isTriggerNode())
        assertTrue(parserManifest.isRegularNode())
    }

    @Test
    fun testUniversalNodeSupportedByAllTriggers() {
        val universalAction =
            NodePluginManifest(
                typeId = "HTTP_ACTION",
                displayName = "HTTP Webhook",
                description = "Sends webhook",
                category = NodeCategory.ACTION,
                supportedTriggerTypes = emptyList(),
            )

        assertTrue(universalAction.isSupportedByTrigger(null))
        assertTrue(universalAction.isSupportedByTrigger(""))
        assertTrue(universalAction.isSupportedByTrigger("   "))
        assertTrue(universalAction.isSupportedByTrigger("SMS_TRIGGER"))
        assertTrue(universalAction.isSupportedByTrigger("dev.zophop.plugin.call.CALL_TRIGGER"))
        assertTrue(universalAction.isSupportedByTrigger("CRON_TRIGGER"))
        assertTrue(universalAction.isSupportedByTrigger("MANUAL_TRIGGER"))
    }

    @Test
    fun testTriggerBoundActionOnlySupportedByMatchingTrigger() {
        val callDisconnectAction =
            NodePluginManifest(
                typeId = "dev.zophop.plugin.call.DISCONNECT_CALL_ACTION",
                displayName = "Disconnect Call Action",
                description = "Disconnects incoming call",
                category = NodeCategory.ACTION,
                supportedTriggerTypes = listOf("dev.zophop.plugin.call.CALL_TRIGGER"),
            )

        // Matching triggers
        assertTrue(callDisconnectAction.isSupportedByTrigger("dev.zophop.plugin.call.CALL_TRIGGER"))
        assertTrue(callDisconnectAction.isSupportedByTrigger("CALL_TRIGGER"))
        assertTrue(callDisconnectAction.isSupportedByTrigger("call_trigger"))
        assertTrue(callDisconnectAction.isSupportedByTrigger("dev.zophop.callplugin.CALL_TRIGGER"))

        // Non-matching triggers
        assertFalse(callDisconnectAction.isSupportedByTrigger(null))
        assertFalse(callDisconnectAction.isSupportedByTrigger(""))
        assertFalse(callDisconnectAction.isSupportedByTrigger("SMS_TRIGGER"))
        assertFalse(callDisconnectAction.isSupportedByTrigger("dev.zophop.plugin.sms.SMS_TRIGGER"))
        assertFalse(callDisconnectAction.isSupportedByTrigger("NOTIFICATION_TRIGGER"))
        assertFalse(callDisconnectAction.isSupportedByTrigger("CRON_TRIGGER"))
        assertFalse(callDisconnectAction.isSupportedByTrigger("MANUAL_TRIGGER"))
    }

    @Test
    fun testMultiTriggerBoundNode() {
        val multiBoundNode =
            NodePluginManifest(
                typeId = "dev.zophop.plugin.test.TELEPHONY_LOGIC",
                displayName = "Telephony Check",
                description = "Telephony operations",
                category = NodeCategory.LOGIC,
                supportedTriggerTypes = listOf("SMS_TRIGGER", "CALL_TRIGGER"),
            )

        assertTrue(multiBoundNode.isSupportedByTrigger("SMS_TRIGGER"))
        assertTrue(multiBoundNode.isSupportedByTrigger("dev.zophop.plugin.sms.SMS_TRIGGER"))
        assertTrue(multiBoundNode.isSupportedByTrigger("CALL_TRIGGER"))
        assertTrue(multiBoundNode.isSupportedByTrigger("dev.zophop.plugin.call.CALL_TRIGGER"))

        assertFalse(multiBoundNode.isSupportedByTrigger("NOTIFICATION_TRIGGER"))
        assertFalse(multiBoundNode.isSupportedByTrigger("CRON_TRIGGER"))
        assertFalse(multiBoundNode.isSupportedByTrigger(null))
    }
}
