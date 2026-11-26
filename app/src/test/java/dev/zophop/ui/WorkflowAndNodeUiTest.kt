package dev.zophop.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], manifest = Config.NONE)
class WorkflowAndNodeUiTest {
    @Test
    fun testNodeModelCreationAndConfigSerialization() {
        val testNode =
            WorkflowNode(
                id = "node_ui_test",
                name = "Test SMS Node",
                type = NodeType.SMS_TRIGGER,
                configJson = """{"allowlist":".*"}""",
            )

        assertNotNull(testNode)
        assertEquals("Test SMS Node", testNode.name)
        assertEquals(NodeType.SMS_TRIGGER, testNode.type)
        assertTrue(testNode.configJson.contains("allowlist"))
    }

    @Test
    fun testActionNodeConfigJsonParsing() {
        val testNode =
            WorkflowNode(
                id = "node_action_test",
                name = "Test ntfy Webhook",
                type = NodeType.NTFY_ACTION,
                configJson = """{"serverUrl":"https://ntfy.sh","topic":"my-alerts"}""",
            )

        assertEquals("Test ntfy Webhook", testNode.name)
        assertEquals(NodeType.NTFY_ACTION, testNode.type)
        assertTrue(testNode.configJson.contains("my-alerts"))
    }
}
