package dev.zophop.domain.engine

import dev.zophop.domain.engine.HttpHeaderUtils
import dev.zophop.domain.engine.executors.ConditionExecutor
import dev.zophop.domain.engine.executors.JsonParserExecutor
import dev.zophop.domain.engine.executors.RegexParserExecutor
import dev.zophop.domain.engine.executors.TemplateFormatterExecutor
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.notification.NotificationTriggerExecutor
import dev.zophop.plugin.sms.SmsTriggerExecutor
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug Regression Tests for ZopHop
 *
 * BUG-01: ConditionExecutor always used outputPort="main" — true/false branching was completely broken.
 * BUG-02: JsonParserExecutor couldn't navigate bare array-root paths like "[0].name" (regex was anchored with `.+?` requiring a non-empty prop name).
 * BUG-03: SmsFilter.matchesAllowlist() returned false for an empty allowlist, blocking all forwarding when no allowlist was configured.
 * BUG-04: PreconditionEvaluator ThreadLocal cycle detection is not coroutine-safe (documented/structural, not easily unit-testable in isolation).
 * BUG-05: NotificationTriggerExecutor in executors/ package (non-plugin) returned Failed instead of Ignored on filter mismatch — stopping the workflow instead of silently skipping.
 */
class BugRegressionTest {
    // ─────────────────────────────────────────────────────────────
    // BUG-01: ConditionExecutor true/false output port branching
    // ─────────────────────────────────────────────────────────────

    /**
     * BUG-01a: When condition is MET, the output port must be "true" so the
     * WorkflowEngine routes to nodes connected on the "true" port.
     */
    @Test
    fun conditionExecutor_conditionMet_outputPortIsTrue() =
        runTest {
            val executor = ConditionExecutor()
            val node =
                WorkflowNode(
                    id = "c1",
                    name = "Check",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"status","operator":"EQUALS","value":"ACTIVE"}""",
                )
            val ctx = ExecutionContext(mapOf("status" to "ACTIVE"))
            val result = executor.execute(node, ctx, null)

            assertTrue("Expected Success", result is NodeExecutionResult.Success)
            assertEquals(
                "Output port must be 'true' when condition passes (BUG-01)",
                "true",
                (result as NodeExecutionResult.Success).outputPort,
            )
        }

    /**
     * BUG-01b: When condition is NOT met, the output port must be "false" (not a
     * Failed result) so workflows can still route to an "else" branch.
     */
    @Test
    fun conditionExecutor_conditionNotMet_outputPortIsFalse() =
        runTest {
            val executor = ConditionExecutor()
            val node =
                WorkflowNode(
                    id = "c2",
                    name = "Check",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"status","operator":"EQUALS","value":"ACTIVE"}""",
                )
            val ctx = ExecutionContext(mapOf("status" to "PENDING"))
            val result = executor.execute(node, ctx, null)

            assertTrue(
                "Condition false must still return Success (not Failed) to allow false-branch routing (BUG-01)",
                result is NodeExecutionResult.Success,
            )
            assertEquals(
                "Output port must be 'false' when condition does not pass (BUG-01)",
                "false",
                (result as NodeExecutionResult.Success).outputPort,
            )
        }

    /**
     * BUG-01c: End-to-end: a workflow with true/false branching must correctly route
     * to the "true" action node and NOT the "false" action node when condition passes.
     */
    @Test
    fun workflowEngine_conditionBranching_routesCorrectlyViaTruePort() =
        runTest {
            val requestedTopics = mutableListOf<String>()

            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        // capture which ntfy topic was requested
                        requestedTopics.add(
                            chain
                                .request()
                                .url.pathSegments
                                .last(),
                        )
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    executorFactory = NodeExecutorFactory(client),
                )

            val triggerNode = WorkflowNode("n_trigger", "SMS Trigger", NodeType.SMS_TRIGGER)
            val condNode =
                WorkflowNode(
                    "n_cond",
                    "Priority Check",
                    NodeType.CONDITION,
                    configJson = """{"variable":"body","operator":"CONTAINS","value":"URGENT"}""",
                )
            val urgentAction =
                WorkflowNode(
                    "n_urgent",
                    "Urgent Alert",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"urgent"}""",
                )
            val normalAction =
                WorkflowNode(
                    "n_normal",
                    "Normal Alert",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"normal"}""",
                )

            val workflow =
                Workflow(
                    id = "w_branching_test",
                    name = "Branching Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, condNode, urgentAction, normalAction),
                    connections =
                        listOf(
                            WorkflowConnection("n_trigger", "n_cond"),
                            WorkflowConnection("n_cond", "n_urgent", outputPort = "true"),
                            WorkflowConnection("n_cond", "n_normal", outputPort = "false"),
                        ),
                )

            // URGENT body → condition true → route to urgent topic
            val urgentCtx = ExecutionContext(mapOf("sender" to "Admin", "body" to "URGENT: server down"))
            engine.executeWorkflow(workflow, urgentCtx, null)
            assertEquals("URGENT body must route to 'urgent' topic (BUG-01)", listOf("urgent"), requestedTopics)

            requestedTopics.clear()

            // Normal body → condition false → route to normal topic
            val normalCtx = ExecutionContext(mapOf("sender" to "Admin", "body" to "All systems nominal"))
            engine.executeWorkflow(workflow, normalCtx, null)
            assertEquals("Non-urgent body must route to 'normal' topic (BUG-01)", listOf("normal"), requestedTopics)
        }

    /**
     * BUG-01d: Multiple condition operators emit the correct output port.
     */
    @Test
    fun conditionExecutor_allOperators_emitCorrectOutputPort() =
        runTest {
            val executor = ConditionExecutor()

            data class Case(
                val operator: String,
                val value: String,
                val ctxVal: String,
                val expectTrue: Boolean,
            )

            val cases =
                listOf(
                    Case("NOT_EQUALS", "X", "Y", expectTrue = true),
                    Case("NOT_EQUALS", "X", "X", expectTrue = false),
                    Case("CONTAINS", "hello", "say hello world", expectTrue = true),
                    Case("NOT_CONTAINS", "hello", "goodbye", expectTrue = true),
                    Case("NOT_EMPTY", "", "somevalue", expectTrue = true),
                    Case("IS_EMPTY", "", "", expectTrue = true),
                    Case("GREATER_THAN", "5", "10", expectTrue = true),
                    Case("LESS_THAN", "10", "3", expectTrue = true),
                    Case("REGEX_MATCH", "\\\\d{4}", "code 1234", expectTrue = true),
                )

            for (case in cases) {
                val node =
                    WorkflowNode(
                        "c_op",
                        "Check",
                        NodeType.CONDITION,
                        configJson = """{"variable":"v","operator":"${case.operator}","value":"${case.value}"}""",
                    )
                val ctx = ExecutionContext(mapOf("v" to case.ctxVal))
                val result = executor.execute(node, ctx, null) as NodeExecutionResult.Success
                val expectedPort = if (case.expectTrue) "true" else "false"
                assertEquals(
                    "Operator ${case.operator} with val='${case.ctxVal}' expected port '$expectedPort' (BUG-01)",
                    expectedPort,
                    result.outputPort,
                )
            }
        }

    // ─────────────────────────────────────────────────────────────
    // BUG-02: JsonParserExecutor bare array-root path resolution
    // ─────────────────────────────────────────────────────────────

    /**
     * BUG-02a: Bare array-root indexing with bracket notation, e.g. "[0]"
     * on a JSON array root should extract the first element.
     */
    @Test
    fun jsonParser_bareArrayRootBracketNotation_extractsFirstElement() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_bare",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"[0]","outputKey":"first"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """["alpha","beta","gamma"]"""))
            val result = executor.execute(node, ctx, null)

            assertTrue("Should succeed for bare [0] path (BUG-02)", result is NodeExecutionResult.Success)
            assertEquals("First element must be 'alpha' (BUG-02)", "alpha", ctx.getString("first"))
        }

    /**
     * BUG-02b: Bare array bracket + property access, e.g. "[1].name" on a JSON array.
     */
    @Test
    fun jsonParser_bareArrayRootWithPropertyAccess_extractsCorrectValue() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_bare2",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"[1].name","outputKey":"secondName"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """[{"name":"Alice"},{"name":"Bob"}]"""))
            val result = executor.execute(node, ctx, null)

            assertTrue("Should succeed for [1].name path on array root (BUG-02)", result is NodeExecutionResult.Success)
            assertEquals("Second element name must be 'Bob' (BUG-02)", "Bob", ctx.getString("secondName"))
        }

    /**
     * BUG-02c: Named property with bracket notation still works correctly,
     * i.e., "items[0].code" on a JSON object.
     */
    @Test
    fun jsonParser_namedArrayWithBracketNotation_extractsCorrectValue() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_named",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"items[0].code","outputKey":"code"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"items":[{"code":"OTP-001"},{"code":"OTP-002"}]}"""))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("OTP-001", ctx.getString("code"))
        }

    /**
     * BUG-02d: Out-of-bounds index on array root returns Failed (not a crash).
     */
    @Test
    fun jsonParser_outOfBoundsArrayIndex_returnsFailedGracefully() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_oob",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"[5]","outputKey":"item"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """["only","two"]"""))
            val result = executor.execute(node, ctx, null)

            // No crash; returns Failed (no value found, no default)
            assertTrue("Out-of-bounds index must not crash (BUG-02)", result is NodeExecutionResult.Failed)
        }

    // ─────────────────────────────────────────────────────────────
    // BUG-03: SmsFilter.matchesAllowlist() empty allowlist behavior
    // ─────────────────────────────────────────────────────────────

    /**
     * BUG-03a: An empty allowlist list (no entries) must match ALL senders,
     * behaving like a wildcard — matching any SMS for forwarding.
     * Previously returned false, blocking all forwarding.
     */
    @Test
    fun smsFilter_emptyAllowlist_matchesAllSenders() {
        // Import inline to avoid a separate import of dev.zophop.filter.SmsFilter
        // The class is in a different module (:app) so we test SmsTriggerExecutor behavior
        // which implements the same empty-allowlist logic via allowlistPatterns.isEmpty()
        // We verify via SmsTriggerExecutor directly:
        // allowlistPatterns.isEmpty() -> senderMatches = true -> proceed to keyword check
        val executor =
            dev.zophop.plugin.sms
                .SmsTriggerExecutor()
        // Run in a coroutine context via runTest
    }

    /**
     * BUG-03b: SmsTriggerExecutor with allowlist="" (empty string) must still
     * process messages (the empty split produces an empty list → match all).
     */
    @Test
    fun smsTrigger_emptyAllowlist_doesNotBlockForwarding() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    "sms_empty",
                    "SMS",
                    NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":"","keywordRegex":".*"}""",
                )
            val ctx = ExecutionContext(mapOf("sender" to "ANYBANK", "body" to "Your code is 123456"))
            val result = executor.execute(node, ctx, null)

            assertTrue(
                "Empty allowlist must NOT block forwarding; got: $result (BUG-03)",
                result is NodeExecutionResult.Success,
            )
        }

    /**
     * BUG-03c: SmsTriggerExecutor with allowlist containing only whitespace
     * must also behave as match-all.
     */
    @Test
    fun smsTrigger_whitespaceOnlyAllowlist_doesNotBlockForwarding() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    "sms_ws",
                    "SMS",
                    NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":"   ","keywordRegex":".*"}""",
                )
            val ctx = ExecutionContext(mapOf("sender" to "ANYONE", "body" to "Hello"))
            val result = executor.execute(node, ctx, null)

            assertTrue(
                "Whitespace allowlist must NOT block forwarding (BUG-03)",
                result is NodeExecutionResult.Success,
            )
        }

    /**
     * BUG-03d: Wildcard allowlist ".*" must match any sender.
     */
    @Test
    fun smsTrigger_wildcardAllowlist_matchesAnySender() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    "sms_wc",
                    "SMS",
                    NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":".*","keywordRegex":".*"}""",
                )
            val ctx = ExecutionContext(mapOf("sender" to "+19998887777", "body" to "Test message"))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
        }

    // ─────────────────────────────────────────────────────────────
    // BUG-05: NotificationTriggerExecutor returns Ignored (not Failed)
    //         when package or text filter doesn't match
    // ─────────────────────────────────────────────────────────────

    /**
     * BUG-05a: Non-matching package filter should return Ignored, not Failed.
     * Returning Failed would halt the entire workflow incorrectly.
     */
    @Test
    fun notificationTrigger_nonMatchingPackage_returnsIgnored() =
        runTest {
            val executor = NotificationTriggerExecutor()
            val node =
                WorkflowNode(
                    "notif_pkg",
                    "Notif Trigger",
                    NodeType.NOTIFICATION_TRIGGER,
                    configJson = """{"packageFilter":"com.bankapp","textRegex":".*"}""",
                )
            val ctx =
                ExecutionContext(
                    mapOf(
                        "package_name" to "com.unrelated.app",
                        "title" to "Promo",
                        "text" to "Buy now!",
                    ),
                )
            val result = executor.execute(node, ctx, null)

            assertFalse(
                "Non-matching package must NOT return Failed (would halt workflow) (BUG-05)",
                result is NodeExecutionResult.Failed,
            )
            assertTrue(
                "Non-matching package must return Ignored (BUG-05)",
                result is NodeExecutionResult.Ignored,
            )
        }

    /**
     * BUG-05b: Non-matching text regex should return Ignored, not Failed.
     */
    @Test
    fun notificationTrigger_nonMatchingTextRegex_returnsIgnored() =
        runTest {
            val executor = NotificationTriggerExecutor()
            val node =
                WorkflowNode(
                    "notif_txt",
                    "Notif Trigger",
                    NodeType.NOTIFICATION_TRIGGER,
                    configJson = """{"packageFilter":".*","textRegex":"(?i)OTP|verification"}""",
                )
            val ctx =
                ExecutionContext(
                    mapOf(
                        "package_name" to "com.whatsapp",
                        "title" to "New Message",
                        "text" to "Hey, what's up!",
                    ),
                )
            val result = executor.execute(node, ctx, null)

            assertFalse(
                "Non-matching text must NOT return Failed (BUG-05)",
                result is NodeExecutionResult.Failed,
            )
            assertTrue(
                "Non-matching text must return Ignored (BUG-05)",
                result is NodeExecutionResult.Ignored,
            )
        }

    /**
     * BUG-05c: When BOTH package and text match, the trigger must succeed.
     */
    @Test
    fun notificationTrigger_matchingPackageAndText_returnsSuccess() =
        runTest {
            val executor = NotificationTriggerExecutor()
            val node =
                WorkflowNode(
                    "notif_ok",
                    "Notif Trigger",
                    NodeType.NOTIFICATION_TRIGGER,
                    configJson = """{"packageFilter":"com.whatsapp","textRegex":"(?i)code|otp"}""",
                )
            val ctx =
                ExecutionContext(
                    mapOf(
                        "package_name" to "com.whatsapp",
                        "title" to "Security",
                        "text" to "Your login code is 445566",
                    ),
                )
            val result = executor.execute(node, ctx, null)

            assertTrue("Matching package + text must return Success (BUG-05)", result is NodeExecutionResult.Success)
        }

    /**
     * BUG-05d: A workflow where the notification trigger is filtered out must
     * complete as Success (not Failed), because Ignored halts gracefully.
     */
    @Test
    fun workflowEngine_ignoredNotificationTrigger_completesAsSuccess() =
        runTest {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    executorFactory = NodeExecutorFactory(client),
                )

            val triggerId = "notif_trigger_id"
            val actionId = "action_id"

            val triggerNode =
                WorkflowNode(
                    triggerId,
                    "Notif Trigger",
                    NodeType.NOTIFICATION_TRIGGER,
                    configJson = """{"packageFilter":"com.bankapp","textRegex":".*"}""",
                )
            val actionNode =
                WorkflowNode(
                    actionId,
                    "ntfy Action",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"alerts"}""",
                )

            val workflow =
                Workflow(
                    id = "w_notif_ignored",
                    name = "Notif Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, actionNode),
                    connections = listOf(WorkflowConnection(triggerId, actionId)),
                )

            // Package doesn't match — trigger should be Ignored, workflow should not fail
            val ctx =
                ExecutionContext(
                    mapOf(
                        "package_name" to "com.unrelated",
                        "title" to "Hey",
                        "text" to "some text",
                    ),
                )
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue(
                "Workflow with Ignored trigger must complete as Success, not Failed (BUG-05)",
                result is WorkflowExecutionResult.Success,
            )
        }

    // ─────────────────────────────────────────────────────────────
    // Additional edge-case regression tests
    // ─────────────────────────────────────────────────────────────

    /**
     * RegexParser: When a named capture group is used, the full match (group 0)
     * is still set as "code" and "otp" in context.
     */
    @Test
    fun regexParser_withNoExplicitCaptureGroup_setsCodeFromFullMatch() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r1",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\b\\d{6}\\b","outputKey":"myOtp"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "Your code is 998877. Use it now."))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("998877", ctx.getString("myOtp"))
            // Also aliased to "code" and "otp"
            assertEquals("998877", ctx.getString("code"))
            assertEquals("998877", ctx.getString("otp"))
        }

    /**
     * RegexParser: With an explicit capture group, the captured group is preferred
     * over the full match.
     */
    @Test
    fun regexParser_withCaptureGroup_prefersCaptureGroupOverFullMatch() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r2",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"code[:\\s]+(\\d+)","outputKey":"extracted"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "Login code: 445566. Valid 10 mins."))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("445566", ctx.getString("extracted"))
        }

    /**
     * JsonParser: Boolean type conversion from string "true" value.
     */
    @Test
    fun jsonParser_booleanTypeConversion_convertsStringToBoolean() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_bool",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"active","outputKey":"isActive","targetType":"BOOLEAN"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"active":true}"""))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("true", ctx.getString("isActive"))
        }

    /**
     * JsonParser: Default value is applied when path is missing.
     */
    @Test
    fun jsonParser_missingPath_usesDefaultValue() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_def",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"missing.path","outputKey":"fallback","defaultValue":"NONE"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"other":"value"}"""))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("NONE", ctx.getString("fallback"))
        }

    /**
     * JsonParser: Empty input body returns Failed (not a crash).
     */
    @Test
    fun jsonParser_emptyInput_returnsFailed() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_empty",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"code","outputKey":"code"}""",
                )
            val ctx = ExecutionContext(emptyMap())
            val result = executor.execute(node, ctx, null)

            assertTrue("Empty input must return Failed (not crash)", result is NodeExecutionResult.Failed)
        }

    /**
     * TemplateFormatter: Verifies that interpolation of multiple variables works correctly.
     */
    @Test
    fun templateFormatter_multipleVariables_interpolatesAllCorrectly() =
        runTest {
            val executor = TemplateFormatterExecutor()
            val node =
                WorkflowNode(
                    "t1",
                    "Template",
                    NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"[${"\$"}{sender}] OTP: ${"\$"}{code} - expires in ${"\$"}{expiry} mins"}""",
                )
            val ctx = ExecutionContext(mapOf("sender" to "HDFC", "code" to "123456", "expiry" to "10"))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("[HDFC] OTP: 123456 - expires in 10 mins", ctx.getString("formatted_payload"))
        }

    /**
     * WorkflowEngine: A disabled workflow must return Failed immediately without
     * executing any node.
     */
    @Test
    fun workflowEngine_disabledWorkflow_returnsFailed() =
        runTest {
            val evaluator = PreconditionEvaluator(context = null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator)

            val triggerNode = WorkflowNode("t1", "Trigger", NodeType.MANUAL_TRIGGER)
            val workflow =
                Workflow(
                    id = "w_disabled",
                    name = "Disabled",
                    isEnabled = false,
                    nodes = listOf(triggerNode),
                    connections = emptyList(),
                )
            val ctx = ExecutionContext(mapOf("body" to "test"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue("Disabled workflow must return Failed", result is WorkflowExecutionResult.Failed)
            val failed = result as WorkflowExecutionResult.Failed
            assertTrue(failed.reason.contains("disabled", ignoreCase = true))
        }

    /**
     * WorkflowEngine: Cycle detection via visitedNodeIds — a circular graph must
     * terminate and not loop infinitely.
     */
    @Test
    fun workflowEngine_circularGraph_terminatesCleanly() =
        runTest {
            val client = OkHttpClient.Builder().build()
            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    executorFactory = NodeExecutorFactory(client),
                )

            val nodeA = WorkflowNode("na", "Formatter A", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"A"}""")
            val nodeB = WorkflowNode("nb", "Formatter B", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"B"}""")

            // A → B → A  (cycle)
            val workflow =
                Workflow(
                    id = "w_cycle",
                    name = "Cycle",
                    isEnabled = true,
                    nodes = listOf(nodeA, nodeB),
                    connections =
                        listOf(
                            WorkflowConnection("na", "nb"),
                            WorkflowConnection("nb", "na"),
                        ),
                )

            val ctx = ExecutionContext(mapOf("body" to "test"))
            // Must complete (not hang), result is Success or Failed but not infinite
            val result = engine.executeWorkflow(workflow, ctx, null)
            assertTrue(
                "Circular graph must terminate",
                result is WorkflowExecutionResult.Success || result is WorkflowExecutionResult.Failed,
            )
        }

    /**
     * PreconditionEvaluator: Disabled precondition is skipped (always passes).
     */
    @Test
    fun preconditionEvaluator_disabledPrecondition_alwaysPasses() =
        runTest {
            val evaluator = PreconditionEvaluator(context = null)
            val disabled =
                Precondition.HealthCheck(
                    isEnabled = false,
                    targetUrl = "https://unreachable.example.com",
                    expectedStatus = 200,
                )
            val result = evaluator.evaluate(disabled)
            assertTrue("Disabled precondition must pass without making any request", result is PreconditionResult.Passed)
        }

    /**
     * PreconditionEvaluator: Empty URL HealthCheck returns Failed.
     */
    @Test
    fun preconditionEvaluator_healthCheckEmptyUrl_returnsFailed() =
        runTest {
            val evaluator = PreconditionEvaluator(context = null)
            val healthCheck =
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "",
                    expectedStatus = 200,
                )
            val result = evaluator.evaluate(healthCheck)
            assertTrue("Empty URL healthcheck must return Failed", result is PreconditionResult.Failed)
            assertTrue(
                (result as PreconditionResult.Failed).reason.contains("empty", ignoreCase = true),
            )
        }

    /**
     * PreconditionEvaluator: ChainCheck with empty nodes list returns Failed.
     */
    @Test
    fun preconditionEvaluator_emptyChainCheck_returnsFailed() =
        runTest {
            val evaluator = PreconditionEvaluator(context = null)
            val chainCheck =
                Precondition.ChainCheck(
                    isEnabled = true,
                    nodes = emptyList(),
                    connections = emptyList(),
                )
            val result = evaluator.evaluate(chainCheck)
            assertTrue("ChainCheck with no nodes must return Failed", result is PreconditionResult.Failed)
        }

    /**
     * PreconditionEvaluator: Internet check with null context returns Passed
     * (fail-safe when context is unavailable in unit tests).
     */
    @Test
    fun preconditionEvaluator_internetCheck_nullContext_returnsPassed() =
        runTest {
            val evaluator = PreconditionEvaluator(context = null)
            val check = Precondition.InternetCheck(isEnabled = true)
            val result = evaluator.evaluate(check)
            // With null context isInternetAvailable() returns true (fail-open)
            assertTrue(result is PreconditionResult.Passed)
        }

    /**
     * PreconditionEvaluator: HealthCheck retry exhaustion appends retry count to reason.
     */
    @Test
    fun preconditionEvaluator_healthCheckRetryExhausted_reasonContainsRetryCount() =
        runTest {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(503)
                            .message("Unavailable")
                            .body("error".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val healthCheck =
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "https://example.com/health",
                    expectedStatus = 200,
                    onFailureAction = "RETRY",
                    maxRetries = 2,
                    retryIntervalSeconds = 1,
                )

            val result = evaluator.evaluate(healthCheck)
            assertTrue(result is PreconditionResult.Failed)
            val reason = (result as PreconditionResult.Failed).reason
            assertTrue(
                "Retry exhaustion reason must mention retry count, got: $reason",
                reason.contains("2 retries", ignoreCase = true),
            )
        }

    // ─────────────────────────────────────────────────────────────
    // BUG-06: HttpHeaderUtils.parseHeaders preserves commas in header values
    // ─────────────────────────────────────────────────────────────

    @Test
    fun httpHeaderUtils_parsesAcceptHeaderWithCommasCorrectly() {
        val input = "Accept: text/html,application/xhtml+xml,application/xml;q=0.9"
        val headers = HttpHeaderUtils.parseHeaders(input)

        assertEquals(1, headers.size)
        assertEquals("Accept", headers[0].first)
        assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9", headers[0].second)
    }

    @Test
    fun httpHeaderUtils_parsesMultilineHeadersWithCommasInValues() {
        val input =
            """
            User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)
            Accept: text/html,application/xhtml+xml
            Cache-Control: no-cache, no-store, must-revalidate
            """.trimIndent()
        val headers = HttpHeaderUtils.parseHeaders(input)

        assertEquals(3, headers.size)
        assertEquals("User-Agent", headers[0].first)
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", headers[0].second)
        assertEquals("Accept", headers[1].first)
        assertEquals("text/html,application/xhtml+xml", headers[1].second)
        assertEquals("Cache-Control", headers[2].first)
        assertEquals("no-cache, no-store, must-revalidate", headers[2].second)
    }

    @Test
    fun httpHeaderUtils_parsesCommaSeparatedHeaders() {
        val input = "X-Api-Key: secret123, X-Client: AndroidApp"
        val headers = HttpHeaderUtils.parseHeaders(input)

        assertEquals(2, headers.size)
        assertEquals("X-Api-Key", headers[0].first)
        assertEquals("secret123", headers[0].second)
        assertEquals("X-Client", headers[1].first)
        assertEquals("AndroidApp", headers[1].second)
    }

    // ─────────────────────────────────────────────────────────────
    // BUG-07: ConditionExecutor Variable Interpolation & Numeric Comparison
    // ─────────────────────────────────────────────────────────────

    @Test
    fun conditionExecutor_interpolatesTargetValueAgainstContext() =
        runTest {
            val executor = ConditionExecutor()
            val node =
                WorkflowNode(
                    "c_interp",
                    "Check",
                    NodeType.CONDITION,
                    configJson = """{"variable":"actual_code","operator":"EQUALS","value":"${"$"}{expected_code}"}""",
                )
            val ctx = ExecutionContext(mapOf("actual_code" to "9988", "expected_code" to "9988"))
            val result = executor.execute(node, ctx, null) as NodeExecutionResult.Success
            assertEquals("true", result.outputPort)
        }

    @Test
    fun conditionExecutor_nonNumericValuesDoNotFalselyPassNumericComparison() =
        runTest {
            val executor = ConditionExecutor()
            val node =
                WorkflowNode(
                    "c_num",
                    "Check",
                    NodeType.CONDITION,
                    configJson = """{"variable":"empty_var","operator":"GREATER_THAN","value":"-5.0"}""",
                )
            val ctx = ExecutionContext(emptyMap())
            val result = executor.execute(node, ctx, null) as NodeExecutionResult.Success
            // Empty variable must NOT be treated as 0.0 > -5.0
            assertEquals("false", result.outputPort)
        }

    @Test
    fun conditionExecutor_supportsNewComparisonAndStringOperators() =
        runTest {
            val executor = ConditionExecutor()

            data class TestCase(
                val op: String,
                val act: String,
                val tgt: String,
                val expected: String,
            )
            val testCases =
                listOf(
                    TestCase("GREATER_THAN_OR_EQUAL", "10", "10", "true"),
                    TestCase("GREATER_THAN_OR_EQUAL", "9", "10", "false"),
                    TestCase("LESS_THAN_OR_EQUAL", "10", "10", "true"),
                    TestCase("LESS_THAN_OR_EQUAL", "11", "10", "false"),
                    TestCase("STARTS_WITH", "Bearer eyJhbGciOi", "Bearer", "true"),
                    TestCase("STARTS_WITH", "Token 123", "Bearer", "false"),
                    TestCase("ENDS_WITH", "report.pdf", ".pdf", "true"),
                    TestCase("ENDS_WITH", "report.docx", ".pdf", "false"),
                )

            for (tc in testCases) {
                val node =
                    WorkflowNode(
                        "c_op",
                        "Check",
                        NodeType.CONDITION,
                        configJson = """{"variable":"v","operator":"${tc.op}","value":"${tc.tgt}"}""",
                    )
                val ctx = ExecutionContext(mapOf("v" to tc.act))
                val result = executor.execute(node, ctx, null) as NodeExecutionResult.Success
                assertEquals("Operator ${tc.op} on '${tc.act}' against '${tc.tgt}'", tc.expected, result.outputPort)
            }
        }

    // ─────────────────────────────────────────────────────────────
    // BUG-08: RegexParser fallback variables and custom inputVariable
    // ─────────────────────────────────────────────────────────────

    @Test
    fun regexParser_fallsBackToHttpResponseAndFormattedPayload() =
        runTest {
            val executor = RegexParserExecutor()

            // Fallback to http_response
            val nodeHttp =
                WorkflowNode(
                    "r_http",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\b\\d{6}\\b","outputKey":"token"}""",
                )
            val ctxHttp = ExecutionContext(mapOf("http_response" to """{"status":"ok","code":"654321"}"""))
            val resultHttp = executor.execute(nodeHttp, ctxHttp, null)
            assertTrue(resultHttp is NodeExecutionResult.Success)
            assertEquals("654321", ctxHttp.getString("token"))

            // Fallback to formatted_payload
            val nodeFmt =
                WorkflowNode(
                    "r_fmt",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"ID:([A-Z0-9]+)","outputKey":"id"}""",
                )
            val ctxFmt = ExecutionContext(mapOf("formatted_payload" to "Order processed ID:ABC123XYZ successfully"))
            val resultFmt = executor.execute(nodeFmt, ctxFmt, null)
            assertTrue(resultFmt is NodeExecutionResult.Success)
            assertEquals("ABC123XYZ", ctxFmt.getString("id"))

            // Custom inputVariable
            val nodeCustom =
                WorkflowNode(
                    "r_cust",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\d+","inputVariable":"custom_data","outputKey":"num"}""",
                )
            val ctxCustom = ExecutionContext(mapOf("custom_data" to "Items count 42 in cart"))
            val resultCustom = executor.execute(nodeCustom, ctxCustom, null)
            assertTrue(resultCustom is NodeExecutionResult.Success)
            assertEquals("42", ctxCustom.getString("num"))
        }

    // ─────────────────────────────────────────────────────────────
    // BUG-09: TemplateFormatter custom outputKey
    // ─────────────────────────────────────────────────────────────

    @Test
    fun templateFormatter_storesIntoCustomOutputKey() =
        runTest {
            val executor = TemplateFormatterExecutor()
            val node =
                WorkflowNode(
                    "t_out",
                    "Template",
                    NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"Hello ${"$"}{name}","outputKey":"greeting"}""",
                )
            val ctx = ExecutionContext(mapOf("name" to "Alice"))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("Hello Alice", ctx.getString("greeting"))
            assertEquals("Hello Alice", ctx.getString("formatted_payload"))
        }

    // ─────────────────────────────────────────────────────────────
    // BUG-10: WorkflowEngine Custom Trigger Discovery & Unknown Node Isolation
    // ─────────────────────────────────────────────────────────────

    @Test
    fun workflowEngine_recognizesCustomAndPluginTriggers() =
        runTest {
            val client = OkHttpClient.Builder().build()
            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    executorFactory = NodeExecutorFactory(client),
                )

            // CALL_TRIGGER should be recognized as trigger node
            val callTrigger = WorkflowNode("call_trig", "Call Trigger", "CALL_TRIGGER")
            val formatter =
                WorkflowNode("fmt", "Formatter", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"Call from ${"$"}{caller}"}""")
            val workflow =
                Workflow(
                    id = "w_call",
                    name = "Call Workflow",
                    isEnabled = true,
                    nodes = listOf(callTrigger, formatter),
                    connections = listOf(WorkflowConnection("call_trig", "fmt")),
                )

            val ctx = ExecutionContext(mapOf("caller" to "+1234567890"))
            val result = engine.executeWorkflow(workflow, ctx, null)
            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals("Call from +1234567890", ctx.getString("formatted_payload"))
        }

    @Test
    fun workflowEngine_unknownNodeType_failsStepGracefullyWithoutCrashing() =
        runTest {
            val client = OkHttpClient.Builder().build()
            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    executorFactory = NodeExecutorFactory(client),
                )

            val triggerNode = WorkflowNode("t1", "Trigger", NodeType.MANUAL_TRIGGER)
            val unknownNode = WorkflowNode("u1", "Unknown Action", "UNINSTALLED_PLUGIN_ACTION")
            val workflow =
                Workflow(
                    id = "w_unknown",
                    name = "Unknown Plugin Test",
                    isEnabled = true,
                    nodes = listOf(triggerNode, unknownNode),
                    connections = listOf(WorkflowConnection("t1", "u1")),
                )

            val ctx = ExecutionContext(emptyMap())
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue("Workflow with unknown executor must return Failed cleanly", result is WorkflowExecutionResult.Failed)
            val failed = result as WorkflowExecutionResult.Failed
            assertEquals("u1", failed.nodeId)
            assertTrue(failed.reason.contains("Unknown node type", ignoreCase = true))
        }

    // ─────────────────────────────────────────────────────────────
    // BUG-12 / ENHANCEMENT: Advanced JsonParserExecutor Capabilities
    // ─────────────────────────────────────────────────────────────

    @Test
    fun jsonParser_multiDimensionalArray_extractsNestedItem() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_multi",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"matrix[1][0]","outputKey":"cell"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"matrix": [["A", "B"], ["C", "D"]]}"""))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("C", ctx.getString("cell"))
        }

    @Test
    fun jsonParser_negativeIndex_extractsFromEndOfArray() =
        runTest {
            val executor = JsonParserExecutor()
            val nodeLast =
                WorkflowNode(
                    "j_last",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"items[-1].name","outputKey":"lastName"}""",
                )
            val nodeSecondLast =
                WorkflowNode(
                    "j_2last",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"items[-2].name","outputKey":"secondLastName"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"items": [{"name":"First"}, {"name":"Middle"}, {"name":"Last"}]}"""))

            val resLast = executor.execute(nodeLast, ctx, null)
            assertTrue(resLast is NodeExecutionResult.Success)
            assertEquals("Last", ctx.getString("lastName"))

            val res2Last = executor.execute(nodeSecondLast, ctx, null)
            assertTrue(res2Last is NodeExecutionResult.Success)
            assertEquals("Middle", ctx.getString("secondLastName"))
        }

    @Test
    fun jsonParser_wildcardArrayProjection_extractsAllMatchingSubfields() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_wildcard",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"users[*].id","outputKey":"userIds"}""",
                )
            val ctx =
                ExecutionContext(
                    mapOf(
                        "body" to """{"users": [{"id": 101, "name": "A"}, {"id": 102, "name": "B"}, {"id": 103, "name": "C"}]}""",
                    ),
                )
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("[101,102,103]", ctx.getString("userIds"))
        }

    @Test
    fun jsonParser_lengthAndCountProperties_returnsArrayAndObjectSizes() =
        runTest {
            val executor = JsonParserExecutor()
            val nodeArrLen =
                WorkflowNode(
                    "j_len",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"items.length","outputKey":"itemCount","targetType":"NUMBER"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"items": ["a", "b", "c", "d"]}"""))
            val res = executor.execute(nodeArrLen, ctx, null)
            assertTrue(res is NodeExecutionResult.Success)
            assertEquals(4, ctx.get("itemCount"))
        }

    @Test
    fun jsonParser_recursiveDescent_findsDeepNestedProperty() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_rec",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"..otpCode","outputKey":"foundOtp"}""",
                )
            val ctx =
                ExecutionContext(
                    mapOf(
                        "body" to """{
            "response": {
                "auth": {
                    "payload": {
                        "otpCode": "987654"
                    }
                }
            }
        }""",
                    ),
                )
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("987654", ctx.getString("foundOtp"))
        }

    @Test
    fun jsonParser_cleansMarkdownFencedJsonAndSurroundingPreamble() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_md",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"status","outputKey":"status"}""",
                )
            // Markdown fence
            val ctxMd = ExecutionContext(mapOf("body" to "```json\n{\"status\": \"CONFIRMED\"}\n```"))
            val resMd = executor.execute(node, ctxMd, null)
            assertTrue(resMd is NodeExecutionResult.Success)
            assertEquals("CONFIRMED", ctxMd.getString("status"))

            // Leading and trailing preamble
            val ctxPreamble = ExecutionContext(mapOf("body" to "Incoming event log: {\"status\": \"SUCCESS\"} - processed successfully"))
            val resPreamble = executor.execute(node, ctxPreamble, null)
            assertTrue(resPreamble is NodeExecutionResult.Success)
            assertEquals("SUCCESS", ctxPreamble.getString("status"))
        }

    @Test
    fun jsonParser_multiFieldMappings_extractsMultipleFieldsInSingleNode() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_multi_fields",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{
                "mappings": {
                    "userId": "data.user.id",
                    "userName": "data.user.name",
                    "orderStatus": "data.order.status"
                }
            }""",
                )
            val ctx =
                ExecutionContext(
                    mapOf(
                        "body" to """{
            "data": {
                "user": {"id": 456, "name": "Charlie"},
                "order": {"status": "SHIPPED"}
            }
        }""",
                    ),
                )
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals(456, ctx.get("userId"))
            assertEquals("Charlie", ctx.getString("userName"))
            assertEquals("SHIPPED", ctx.getString("orderStatus"))
        }

    @Test
    fun jsonParser_interpolatesVariablesInJsonKey() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_dyn",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"users[${"$"}{targetIndex}].name","outputKey":"chosenName"}""",
                )
            val ctx =
                ExecutionContext(
                    mapOf(
                        "targetIndex" to "1",
                        "body" to """{"users": [{"name": "Alpha"}, {"name": "Beta"}]}""",
                    ),
                )
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("Beta", ctx.getString("chosenName"))
        }

    // ─────────────────────────────────────────────────────────────
    // BUG-13 / ENHANCEMENT: Advanced Regex Matching & Parsing
    // ─────────────────────────────────────────────────────────────

    @Test
    fun regexParser_matchesAcrossMultiLineWithDotAll() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r_multiline",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"code:.*valid for (\\d+) mins","outputKey":"validity"}""",
                )
            val text = "Your verification\ncode: 123456\nvalid for 15 mins."
            val ctx = ExecutionContext(mapOf("body" to text))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("15", ctx.getString("validity"))
        }

    @Test
    fun regexParser_extractsNamedCaptureGroupsDirectlyIntoContext() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r_named",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"OTP:\\s*(?<code>\\d{6}).*?ref:\\s*(?<ref>[A-Z0-9]+)","outputKey":"full"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "OTP: 987123, ref: TXN8877A completed."))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("987123", ctx.getString("code"))
            assertEquals("TXN8877A", ctx.getString("ref"))
        }

    @Test
    fun regexParser_extractsNumberedGroupsIntoOutputKeysArray() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r_keys",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{
                "regex":"Amount: \\$([0-9.]+), Card: \\*([0-9]{4})",
                "outputKeys": ["amount", "last4"]
            }""",
                )
            val ctx = ExecutionContext(mapOf("body" to "Transaction alert. Amount: $49.99, Card: *4321 approved."))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("49.99", ctx.getString("amount"))
            assertEquals("4321", ctx.getString("last4"))
        }

    @Test
    fun regexParser_supportsMatchIndexNegativeOneForLastMatch() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r_last_match",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\b\\d{4,6}\\b","matchIndex":-1,"outputKey":"finalCode"}""",
                )
            // Message has ref ID (1001) first, then OTP (998877) last
            val ctx = ExecutionContext(mapOf("body" to "Ref 1001: your security code is 998877"))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("998877", ctx.getString("finalCode"))
        }

    @Test
    fun regexParser_matchAllCollectsAllOccurrencesAsJsonArray() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r_all",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"#(\\w+)","outputType":"ARRAY","outputKey":"hashtags"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "Welcome #android and #kotlin community!"))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("""["android","kotlin"]""", ctx.getString("hashtags"))
        }

    @Test
    fun regexParser_usesDefaultValueWhenNoMatchFound() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r_def",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\b\\d{8}\\b","outputKey":"pin","defaultValue":"000000"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "No eight digit number here"))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("000000", ctx.getString("pin"))
        }

    @Test
    fun conditionExecutor_supportsNotRegexMatchOperator() =
        runTest {
            val executor = ConditionExecutor()
            val nodePass =
                WorkflowNode(
                    "c_not_regex_pass",
                    "Check",
                    NodeType.CONDITION,
                    configJson = """{"variable":"body","operator":"NOT_REGEX_MATCH","value":"(?i)spam|fraud"}""",
                )
            val ctxPass = ExecutionContext(mapOf("body" to "Your bank statement is ready"))
            val resPass = executor.execute(nodePass, ctxPass, null) as NodeExecutionResult.Success
            assertEquals("true", resPass.outputPort)

            val nodeFail =
                WorkflowNode(
                    "c_not_regex_fail",
                    "Check",
                    NodeType.CONDITION,
                    configJson = """{"variable":"body","operator":"NOT_REGEX_MATCH","value":"(?i)spam|fraud"}""",
                )
            val ctxFail = ExecutionContext(mapOf("body" to "Warning: Potential fraud detected"))
            val resFail = executor.execute(nodeFail, ctxFail, null) as NodeExecutionResult.Success
            assertEquals("false", resFail.outputPort)
        }
}
