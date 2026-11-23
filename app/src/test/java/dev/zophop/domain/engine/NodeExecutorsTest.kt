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
            val node =
                WorkflowNode(
                    id = "json1",
                    name = "JSON Parser",
                    type = NodeType.JSON_PARSER,
                    configJson =
                        """
                        {
                            "jsonKey": "data.user.id",
                            "outputKey": "userId",
                            "targetType": "NUMBER"
                        }
                        """.trimIndent(),
                )

            val jsonInput =
                """
                {
                    "data": {
                        "user": {
                            "id": 98765,
                            "name": "Alice"
                        }
                    }
                }
                """.trimIndent()

            val context = ExecutionContext(mapOf("http_response" to jsonInput))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals(98765, context.get("userId"))
        }

    @Test
    fun testJsonParserExtractsArrayIndexPath() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    id = "json2",
                    name = "JSON Parser",
                    type = NodeType.JSON_PARSER,
                    configJson =
                        """
                        {
                            "jsonKey": "items[1].code",
                            "outputKey": "extractedCode",
                            "targetType": "STRING"
                        }
                        """.trimIndent(),
                )

            val jsonInput =
                """
                {
                    "items": [
                        {"code": "ALPHA"},
                        {"code": "BETA"},
                        {"code": "GAMMA"}
                    ]
                }
                """.trimIndent()

            val context = ExecutionContext(mapOf("http_response" to jsonInput))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("BETA", context.getString("extractedCode"))
        }

    @Test
    fun testJsonParserTargetTypeConversions() =
        runTest {
            val executor = JsonParserExecutor()
            val jsonInput = """{"strBool": "true", "numStr": "42.5", "rawObj": {"a": 1}}"""

            // Test Boolean conversion
            val boolNode = WorkflowNode("j1", "JSON", NodeType.JSON_PARSER, true, """{"jsonKey":"strBool","targetType":"BOOLEAN"}""")
            val ctx1 = ExecutionContext(mapOf("http_response" to jsonInput))
            executor.execute(boolNode, ctx1, null)
            assertEquals(true, ctx1.get("strBool"))

            // Test Double/Number conversion
            val numNode = WorkflowNode("j2", "JSON", NodeType.JSON_PARSER, true, """{"jsonKey":"numStr","targetType":"NUMBER"}""")
            val ctx2 = ExecutionContext(mapOf("http_response" to jsonInput))
            executor.execute(numNode, ctx2, null)
            assertEquals(42, ctx2.get("numStr"))

            // Test JSON String conversion
            val jsonStrNode = WorkflowNode("j3", "JSON", NodeType.JSON_PARSER, true, """{"jsonKey":"rawObj","targetType":"JSON_STRING"}""")
            val ctx3 = ExecutionContext(mapOf("http_response" to jsonInput))
            executor.execute(jsonStrNode, ctx3, null)
            assertEquals("""{"a":1}""", ctx3.getString("rawObj"))
        }

    @Test
    fun testJsonParserFallbackDefaultValueWhenPathMissing() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    id = "json3",
                    name = "JSON Parser",
                    type = NodeType.JSON_PARSER,
                    configJson =
                        """
                        {
                            "jsonKey": "missing.path",
                            "outputKey": "status",
                            "defaultValue": "UNKNOWN"
                        }
                        """.trimIndent(),
                )

            val context = ExecutionContext(mapOf("http_response" to """{"other": "data"}"""))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("UNKNOWN", context.getString("status"))
        }

    @Test
    fun testJsonParserCustomInputVariable() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    id = "json4",
                    name = "JSON Parser",
                    type = NodeType.JSON_PARSER,
                    configJson =
                        """
                        {
                            "jsonKey": "status",
                            "inputVariable": "custom_payload"
                        }
                        """.trimIndent(),
                )

            val context = ExecutionContext(mapOf("custom_payload" to """{"status": "ACTIVE"}"""))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("ACTIVE", context.getString("status"))
        }

    @Test
    fun testJsonParserFailsOnEmptyInput() =
        runTest {
            val executor = JsonParserExecutor()
            val node = WorkflowNode("j5", "JSON", NodeType.JSON_PARSER, true, """{"jsonKey":"code"}""")
            val context = ExecutionContext(emptyMap())

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Failed)
        }

    // --- Condition Executor Tests ---

    @Test
    fun testConditionEqualsAndNotEquals() =
        runTest {
            val executor = ConditionExecutor()

            val equalsNode =
                WorkflowNode("c1", "Cond", NodeType.CONDITION, true, """{"variable":"status","operator":"EQUALS","value":"ACTIVE"}""")
            val ctx1 = ExecutionContext(mapOf("status" to "active"))
            val res1 = executor.execute(equalsNode, ctx1, null)
            assertTrue(res1 is NodeExecutionResult.Success)

            val notEqualsNode =
                WorkflowNode("c2", "Cond", NodeType.CONDITION, true, """{"variable":"status","operator":"NOT_EQUALS","value":"INACTIVE"}""")
            val res2 = executor.execute(notEqualsNode, ctx1, null)
            assertTrue(res2 is NodeExecutionResult.Success)
        }

    @Test
    fun testConditionContainsAndNotContains() =
        runTest {
            val executor = ConditionExecutor()

            val containsNode =
                WorkflowNode(
                    "c3",
                    "Cond",
                    NodeType.CONDITION,
                    true,
                    """{"variable":"body","operator":"CONTAINS","value":"verification code"}""",
                )
            val ctx1 = ExecutionContext(mapOf("body" to "Your verification code is 849201"))
            val res1 = executor.execute(containsNode, ctx1, null)
            assertTrue(res1 is NodeExecutionResult.Success)

            val notContainsNode =
                WorkflowNode("c4", "Cond", NodeType.CONDITION, true, """{"variable":"body","operator":"NOT_CONTAINS","value":"SPAM"}""")
            val res2 = executor.execute(notContainsNode, ctx1, null)
            assertTrue(res2 is NodeExecutionResult.Success)
        }

    @Test
    fun testConditionNumericGreaterThanAndLessThan() =
        runTest {
            val executor = ConditionExecutor()

            val gtNode =
                WorkflowNode("c5", "Cond", NodeType.CONDITION, true, """{"variable":"amount","operator":"GREATER_THAN","value":"100"}""")
            val ctx1 = ExecutionContext(mapOf("amount" to "250.50"))
            val res1 = executor.execute(gtNode, ctx1, null)
            assertTrue(res1 is NodeExecutionResult.Success)

            val ltNode =
                WorkflowNode("c6", "Cond", NodeType.CONDITION, true, """{"variable":"amount","operator":"LESS_THAN","value":"50"}""")
            val res2 = executor.execute(ltNode, ctx1, null)
            // BUG-01 fix: false condition now returns Success(outputPort="false"), not Failed
            assertTrue(res2 is NodeExecutionResult.Success)
            assertEquals("false", (res2 as NodeExecutionResult.Success).outputPort)
        }

    @Test
    fun testConditionRegexMatchAndEmptyChecks() =
        runTest {
            val executor = ConditionExecutor()

            val regexNode =
                WorkflowNode("c7", "Cond", NodeType.CONDITION, true, """{"variable":"code","operator":"REGEX_MATCH","value":"^\\d{6}$"}""")
            val ctx1 = ExecutionContext(mapOf("code" to "849201"))
            val res1 = executor.execute(regexNode, ctx1, null)
            assertTrue(res1 is NodeExecutionResult.Success)

            val notEmptyNode = WorkflowNode("c8", "Cond", NodeType.CONDITION, true, """{"variable":"code","operator":"NOT_EMPTY"}""")
            val res2 = executor.execute(notEmptyNode, ctx1, null)
            assertTrue(res2 is NodeExecutionResult.Success)

            val isEmptyNode = WorkflowNode("c9", "Cond", NodeType.CONDITION, true, """{"variable":"missingVar","operator":"IS_EMPTY"}""")
            val res3 = executor.execute(isEmptyNode, ctx1, null)
            assertTrue(res3 is NodeExecutionResult.Success)
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
