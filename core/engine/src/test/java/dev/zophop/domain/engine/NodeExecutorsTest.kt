package dev.zophop.domain.engine

import dev.zophop.domain.engine.executors.ConditionExecutor
import dev.zophop.domain.engine.executors.JsonParserExecutor
import dev.zophop.domain.engine.executors.RegexParserExecutor
import dev.zophop.domain.engine.executors.TemplateFormatterExecutor
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.notification.NotificationTriggerExecutor
import dev.zophop.plugin.sms.SmsTriggerExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeExecutorsTest {
    // --- JSON Parser Executor Tests ---

    @Test
    fun testJsonParserExtractsNestedObjectPath() =
        runTest {
            val executor = JsonParserExecutor()
            val node = WorkflowNode("j1", "JSON", NodeType.JSON_PARSER, true, """{"jsonKey":"data.status","outputKey":"statusResult"}""")
            val context = ExecutionContext(mapOf("http_response" to """{"data": {"status": "ACTIVE"}}"""))

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("ACTIVE", context.getString("statusResult"))
        }

    @Test
    fun testJsonParserHandlesArrayPath() =
        runTest {
            val executor = JsonParserExecutor()
            val node = WorkflowNode("j2", "JSON", NodeType.JSON_PARSER, true, """{"jsonKey":"items.0.name","outputKey":"itemName"}""")
            val context = ExecutionContext(mapOf("http_response" to """{"items": [{"name": "FirstItem"}, {"name": "SecondItem"}] }"""))

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("FirstItem", context.getString("itemName"))
        }

    @Test
    fun testJsonParserDefaultsWhenPathMissing() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j3",
                    "JSON",
                    NodeType.JSON_PARSER,
                    true,
                    """{"jsonKey":"missing.path","outputKey":"res","defaultValue":"UNKNOWN"}""",
                )
            val context = ExecutionContext(mapOf("http_response" to """{"data": 123}"""))

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("UNKNOWN", context.getString("res"))
        }

    // --- Condition Evaluator Tests ---

    @Test
    fun testConditionEvaluatesEqualsAndContains() =
        runTest {
            val executor = ConditionExecutor()

            // EQUALS pass
            val node1 =
                WorkflowNode("c1", "Cond", NodeType.CONDITION, true, """{"variable":"status","operator":"EQUALS","value":"SUCCESS"}""")
            val ctx1 = ExecutionContext(mapOf("status" to "SUCCESS"))
            assertTrue(executor.execute(node1, ctx1, null) is NodeExecutionResult.Success)

            // EQUALS fail -> BUG-01 fix: returns Success(outputPort="false") for false-branch routing
            val ctx1Fail = ExecutionContext(mapOf("status" to "PENDING"))
            val failResult = executor.execute(node1, ctx1Fail, null)
            assertTrue(failResult is NodeExecutionResult.Success)
            assertEquals("false", (failResult as NodeExecutionResult.Success).outputPort)

            // CONTAINS pass
            val node2 =
                WorkflowNode("c2", "Cond", NodeType.CONDITION, true, """{"variable":"msg","operator":"CONTAINS","value":"verified"}""")
            val ctx2 = ExecutionContext(mapOf("msg" to "Your account is verified successfully."))
            assertTrue(executor.execute(node2, ctx2, null) is NodeExecutionResult.Success)
        }

    // --- SMS & Notification Trigger Tests ---

    @Test
    fun testSmsTriggerAllowlistAndKeywordMatching() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    id = "sms1",
                    name = "SMS Trigger",
                    type = NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":"BANKALERT, +1555*","keywordRegex":"(?i)OTP|Code"}""",
                )

            // Matching sender & keyword
            val ctx1 = ExecutionContext(mapOf("sender" to "BANKALERT", "body" to "Your OTP code is 1234"))
            val res1 = executor.execute(node, ctx1, null)
            assertTrue(res1 is NodeExecutionResult.Success)

            // Non-matching sender
            val ctx2 = ExecutionContext(mapOf("sender" to "SPAMMER", "body" to "Your OTP code is 1234"))
            val res2 = executor.execute(node, ctx2, null)
            assertTrue(res2 is NodeExecutionResult.Ignored)

            // Non-matching keyword
            val ctx3 = ExecutionContext(mapOf("sender" to "BANKALERT", "body" to "Hello world, buy now!"))
            val res3 = executor.execute(node, ctx3, null)
            assertTrue(res3 is NodeExecutionResult.Ignored)
        }

    @Test
    fun testNotificationTriggerFilterMatching() =
        runTest {
            val executor = NotificationTriggerExecutor()
            val node =
                WorkflowNode(
                    id = "notif1",
                    name = "Notification Trigger",
                    type = NodeType.NOTIFICATION_TRIGGER,
                    configJson = """{"packageFilter":"com.whatsapp, com.telegram.*","textRegex":"(?i)security|code"}""",
                )

            val ctx1 =
                ExecutionContext(
                    mapOf(
                        "package_name" to "com.telegram.messenger",
                        "title" to "Security Alert",
                        "text" to "Login code 554433",
                    ),
                )
            val res1 = executor.execute(node, ctx1, null)
            assertTrue(res1 is NodeExecutionResult.Success)

            val ctx2 = ExecutionContext(mapOf("package_name" to "com.unrelated.app", "title" to "Security Alert", "text" to "Login code"))
            val res2 = executor.execute(node, ctx2, null)
            assertTrue(res2 is NodeExecutionResult.Ignored)
        }

    // --- Regex Parser & Template Formatter Tests ---

    @Test
    fun testRegexParserExtractsOtp() =
        runTest {
            val executor = RegexParserExecutor()
            val node = WorkflowNode("r1", "Regex", NodeType.REGEX_PARSER, true, """{"regex":"\\b\\d{6}\\b","outputKey":"otp_code"}""")
            val context = ExecutionContext(mapOf("body" to "Use 948201 as your login verification code."))

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("948201", context.getString("otp_code"))
        }

    @Test
    fun testTemplateFormatterInterpolatesVariables() =
        runTest {
            val executor = TemplateFormatterExecutor()
            val node =
                WorkflowNode("t1", "Template", NodeType.TEMPLATE_FORMATTER, true, "{\"template\":\"Alert from \${sender}: Code \${code}\"}")
            val context = ExecutionContext(mapOf("sender" to "ServiceX", "code" to "443211"))

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("Alert from ServiceX: Code 443211", context.getString("formatted_payload"))
        }
}
