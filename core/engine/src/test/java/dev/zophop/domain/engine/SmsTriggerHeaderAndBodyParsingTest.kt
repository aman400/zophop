package dev.zophop.domain.engine

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.sms.SmsTriggerExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsTriggerHeaderAndBodyParsingTest {
    @Test
    fun testSmsTriggerParsesSenderFromHeaderOrSmsHeaderKey() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    id = "sms_node_1",
                    name = "SMS Header Match",
                    type = NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":"*HDFCBK*","keywordRegex":".*OTP.*"}""",
                )

            // Context providing "header" and "sms_body" instead of generic "sender"/"body"
            val context =
                ExecutionContext(
                    mapOf(
                        "header" to "VM-HDFCBK",
                        "sms_body" to "Your HDFC Bank OTP code is 987654",
                    ),
                )

            val result = executor.execute(node, context, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("VM-HDFCBK", context.getString("sender"))
            assertEquals("VM-HDFCBK", context.getString("header"))
            assertEquals("VM-HDFCBK", context.getString("sms_header"))
            assertEquals("Your HDFC Bank OTP code is 987654", context.getString("body"))
            assertEquals("Your HDFC Bank OTP code is 987654", context.getString("sms_body"))
        }

    @Test
    fun testSmsTriggerParsesBodyFromMessageOrTextKey() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    id = "sms_node_2",
                    name = "SMS Message Match",
                    type = NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":".*","keywordRegex":"(?i)verification code"}""",
                )

            // Context providing "address" and "text"
            val context =
                ExecutionContext(
                    mapOf(
                        "address" to "+15551234567",
                        "text" to "Your Google verification code is 445566",
                    ),
                )

            val result = executor.execute(node, context, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("+15551234567", context.getString("sender"))
            assertEquals("Your Google verification code is 445566", context.getString("body"))
        }

    @Test
    fun testSmsTriggerIgnoresWhenHeaderDoesNotMatchAllowlist() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    id = "sms_node_3",
                    name = "Strict Allowlist",
                    type = NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":"BANKALERT","keywordRegex":".*"}""",
                )

            val context =
                ExecutionContext(
                    mapOf(
                        "sms_header" to "UNKNOWN_SPAMMERS",
                        "sms_body" to "Free rewards claim now",
                    ),
                )

            val result = executor.execute(node, context, null)

            assertTrue(result is NodeExecutionResult.Ignored)
            val reason = (result as NodeExecutionResult.Ignored).reason
            assertTrue(reason.contains("allowlist"))
        }

    @Test
    fun testSmsTriggerIgnoresWhenBodyDoesNotMatchKeywordRegex() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    id = "sms_node_4",
                    name = "Keyword Filter",
                    type = NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":".*","keywordRegex":"(?i)OTP|Code"}""",
                )

            val context =
                ExecutionContext(
                    mapOf(
                        "header" to "VM-HDFCBK",
                        "body" to "Hello friend, see you at 5 PM today.",
                    ),
                )

            val result = executor.execute(node, context, null)

            assertTrue(result is NodeExecutionResult.Ignored)
            val reason = (result as NodeExecutionResult.Ignored).reason
            assertTrue(reason.contains("keyword regex") || reason.contains("body"))
        }
}
