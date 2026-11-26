package dev.zophop.domain.engine

import dev.zophop.domain.engine.executors.ConditionExecutor
import dev.zophop.domain.engine.executors.HttpActionExecutor
import dev.zophop.domain.engine.executors.JsonParserExecutor
import dev.zophop.domain.engine.executors.NotificationTriggerExecutor
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security and QA edge-case test suite evaluating robustness against:
 * 1. Template variable injection / recursive expansion vectors
 * 2. Malformed, deeply nested, or boundary JSON structures
 * 3. ReDoS and invalid regex patterns in conditions
 * 4. Workflow graph topologies (cycles, diamond branches, orphan nodes)
 */
class SecurityAndEdgeCaseTest {

    @Test
    fun testTemplateInjection_untrustedInputCannotExfiltrateSecretsDeterministically() {
        val context = ExecutionContext()
        context.set("secret_token", "SUPER_CONFIDENTIAL_KEY_99999")
        context.set("untrusted_body", "Hello, my referral code is \${secret_token}")

        val template = "Notification received: \${untrusted_body}"
        val result = context.interpolate(template)

        assertNotNull(result)
        assertFalse("Untrusted body must NOT trigger second-order expansion of secrets", result.contains("SUPER_CONFIDENTIAL_KEY_99999"))
        assertEquals("Notification received: Hello, my referral code is \${secret_token}", result)
    }

    @Test
    fun testExecutionContext_emptyAndSpecialCharacterVariableNames() {
        val context = ExecutionContext()
        context.set("a-b", "hyphen_val")
        context.set("a_b", "underscore_val")
        context.set("a.b", "dot_val")
        context.set("123", "number_key")

        assertEquals("hyphen_val", context.interpolate("\${a-b}"))
        assertEquals("underscore_val", context.interpolate("\${a_b}"))
        assertEquals("dot_val", context.interpolate("\${a.b}"))
        assertEquals("number_key", context.interpolate("\${123}"))
    }

    @Test
    fun testExecutionContext_curlyBraceAlternateSyntax() {
        val context = ExecutionContext(mapOf("token" to "xyz123"))
        val result1 = context.interpolate("Bearer {token}")
        val result2 = context.interpolate("Bearer \${token}")

        assertEquals("Bearer xyz123", result1)
        assertEquals("Bearer xyz123", result2)
    }

    @Test
    fun testJsonParser_handlesMalformedAndAdversarialJsonGracefully() = runTest {
        val parser = JsonParserExecutor()

        // 1. Broken / Truncated JSON
        val nodeBroken = WorkflowNode(
            id = "n_json",
            name = "Parse",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"payload","jsonKey":"user.id"}""",
        )
        val ctxBroken = ExecutionContext(mapOf("payload" to "{\"user\": {\"id\": 123"))
        val resBroken = parser.execute(nodeBroken, ctxBroken, null)
        assertTrue(resBroken is NodeExecutionResult.Failed)

        // 2. Deeply nested JSON
        val deepJson = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":42}}}}}}"
        val ctxDeep = ExecutionContext(mapOf("payload" to deepJson))
        val nodeDeep = WorkflowNode(
            id = "n_json_deep",
            name = "Parse Deep",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"payload","jsonKey":"a.b.c.d.e.f","outputKey":"deep_res"}""",
        )
        val resDeep = parser.execute(nodeDeep, ctxDeep, null)
        assertTrue(resDeep is NodeExecutionResult.Success)
        assertEquals(42, ctxDeep.get("deep_res"))

        // 3. Array negative indexing bounds
        val arrayJson = "{\"items\":[\"first\",\"middle\",\"last\"]}"
        val ctxArray = ExecutionContext(mapOf("payload" to arrayJson))
        val nodeNeg = WorkflowNode(
            id = "n_json_neg",
            name = "Parse Neg",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"payload","jsonKey":"items[-1]","outputKey":"last_item"}""",
        )
        val resNeg = parser.execute(nodeNeg, ctxArray, null)
        assertTrue(resNeg is NodeExecutionResult.Success)
        assertEquals("last", ctxArray.get("last_item"))

        // 4. Array out-of-bounds negative index
        val nodeOutOfBounds = WorkflowNode(
            id = "n_json_oob",
            name = "Parse OOB",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"payload","jsonKey":"items[-99]","defaultValue":"fallback"}""",
        )
        val resOob = parser.execute(nodeOutOfBounds, ctxArray, null)
        assertTrue(resOob is NodeExecutionResult.Success)
        assertEquals("fallback", ctxArray.get("items[-99]"))
    }

    @Test
    fun testJsonParser_handlesMarkdownCodeFencesAndPreambles() = runTest {
        val parser = JsonParserExecutor()
        val markdownJson = """
            Here is the response from the LLM or webhook:
            ```json
            {
               "status": "APPROVED",
               "auth_code": 88421
            }
            ```
            Hope this helps!
        """.trimIndent()

        val context = ExecutionContext(mapOf("body" to markdownJson))
        val node = WorkflowNode(
            id = "n_json_md",
            name = "Clean Markdown JSON",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"body","jsonKey":"auth_code","outputKey":"code","targetType":"INTEGER"}""",
        )
        val result = parser.execute(node, context, null)
        assertTrue(result is NodeExecutionResult.Success)
        assertEquals(88421, context.get("code"))
    }

    @Test
    fun testConditionExecutor_handlesInvalidRegexWithoutCrashing() = runTest {
        val executor = ConditionExecutor()
        val context = ExecutionContext(mapOf("test_val" to "some string"))

        // Invalid regex syntax (unclosed parenthesis)
        val nodeInvalidRegex = WorkflowNode(
            id = "n_cond",
            name = "Condition",
            type = NodeType.CONDITION,
            configJson = """{"variable":"test_val","operator":"REGEX_MATCH","value":"(unclosed_group"}""",
        )
        val result = executor.execute(nodeInvalidRegex, context, null)
        assertTrue(result is NodeExecutionResult.Success)
        assertEquals("false", (result as NodeExecutionResult.Success).outputPort)
    }

    @Test
    fun testConditionExecutor_boundaryNumericComparisons() = runTest {
        val executor = ConditionExecutor()
        val context = ExecutionContext(mapOf("battery" to "50"))

        val checkGte = WorkflowNode(
            id = "c1",
            name = "GTE",
            type = NodeType.CONDITION,
            configJson = """{"variable":"battery","operator":">=","value":"50"}""",
        )
        val resGte = executor.execute(checkGte, context, null)
        assertEquals("true", (resGte as NodeExecutionResult.Success).outputPort)

        val checkGt = WorkflowNode(
            id = "c2",
            name = "GT",
            type = NodeType.CONDITION,
            configJson = """{"variable":"battery","operator":">","value":"50"}""",
        )
        val resGt = executor.execute(checkGt, context, null)
        assertEquals("false", (resGt as NodeExecutionResult.Success).outputPort)

        val checkLte = WorkflowNode(
            id = "c3",
            name = "LTE",
            type = NodeType.CONDITION,
            configJson = """{"variable":"battery","operator":"<=","value":"50"}""",
        )
        val resLte = executor.execute(checkLte, context, null)
        assertEquals("true", (resLte as NodeExecutionResult.Success).outputPort)

        val checkLt = WorkflowNode(
            id = "c4",
            name = "LT",
            type = NodeType.CONDITION,
            configJson = """{"variable":"battery","operator":"<","value":"50"}""",
        )
        val resLt = executor.execute(checkLt, context, null)
        assertEquals("false", (resLt as NodeExecutionResult.Success).outputPort)
    }

    @Test
    fun testWorkflowEngine_cycleDetectionPreventsInfiniteLoop() = runTest {
        // Build cyclic graph: Node 1 (Trigger) -> Node 2 -> Node 3 -> Node 2 (Cycle)
        val n1 = WorkflowNode(id = "n1", name = "Trigger", type = NodeType.MANUAL_TRIGGER)
        val n2 = WorkflowNode(
            id = "n2",
            name = "Step A",
            type = NodeType.TEMPLATE_FORMATTER,
            configJson = """{"template":"stepA"}""",
        )
        val n3 = WorkflowNode(
            id = "n3",
            name = "Step B",
            type = NodeType.TEMPLATE_FORMATTER,
            configJson = """{"template":"stepB"}""",
        )

        val connections = listOf(
            WorkflowConnection("n1", "n2"),
            WorkflowConnection("n2", "n3"),
            WorkflowConnection("n3", "n2"), // Loop back to n2
        )

        val workflow = Workflow(
            id = "wf_cycle",
            name = "Cyclic Workflow",
            nodes = listOf(n1, n2, n3),
            connections = connections,
        )

        val evaluator = PreconditionEvaluator(null)
        val engine = WorkflowEngine(evaluator)
        val context = ExecutionContext()

        // Engine must terminate cleanly and not hang or stack-overflow
        val result = engine.executeWorkflow(workflow, context, null)
        assertTrue(result is WorkflowExecutionResult.Success)

        // Each node was executed exactly once due to cycle suppression
        val successSteps = (result as WorkflowExecutionResult.Success).steps.filter { it.state == NodeStepState.SUCCESS }
        assertEquals(3, successSteps.size)
    }

    @Test
    fun testWorkflowEngine_diamondBranchTopologyExecution() = runTest {
        // Diamond graph:
        //          n1 (Trigger)
        //         /            \
        //     n2 (Branch A)   n3 (Branch B)
        //         \            /
        //          n4 (Join Node)
        val n1 = WorkflowNode(id = "n1", name = "Start", type = NodeType.MANUAL_TRIGGER)
        val n2 = WorkflowNode(id = "n2", name = "Branch A", type = NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"A"}""")
        val n3 = WorkflowNode(id = "n3", name = "Branch B", type = NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"B"}""")
        val n4 = WorkflowNode(id = "n4", name = "Join", type = NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"Joined"}""")

        val connections = listOf(
            WorkflowConnection("n1", "n2"),
            WorkflowConnection("n1", "n3"),
            WorkflowConnection("n2", "n4"),
            WorkflowConnection("n3", "n4"),
        )

        val workflow = Workflow(
            id = "wf_diamond",
            name = "Diamond Workflow",
            nodes = listOf(n1, n2, n3, n4),
            connections = connections,
        )

        val evaluator = PreconditionEvaluator(null)
        val engine = WorkflowEngine(evaluator)
        val context = ExecutionContext()

        val result = engine.executeWorkflow(workflow, context, null)
        assertTrue(result is WorkflowExecutionResult.Success)

        val steps = (result as WorkflowExecutionResult.Success).steps
        // All 4 nodes are accounted for in steps
        assertEquals(4, steps.size)
        assertTrue(steps.all { it.state == NodeStepState.SUCCESS })
    }

    @Test
    fun testHttpActionExecutor_rejectsUserInfoAndSanitizesCrlfAndLogs() = runTest {
        var capturedHeaderValue: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            capturedHeaderValue = req.header("X-Custom")
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("ok".toResponseBody())
                .build()
        }.build()

        val executor = HttpActionExecutor(client)

        // 1. Userinfo in URL must fail before network dispatch
        val userInfoNode = WorkflowNode(
            id = "http_userinfo",
            name = "UserInfo",
            type = NodeType.HTTP_ACTION,
            configJson = """{"url":"https://user:pass@example.com/hook","method":"GET"}""",
        )
        val userInfoRes = executor.execute(userInfoNode, ExecutionContext(), null)
        assertTrue(userInfoRes is NodeExecutionResult.Failed)
        assertTrue((userInfoRes as NodeExecutionResult.Failed).reason.contains("credentials"))

        // 2. Interpolated CRLF in header value is stripped and sensitive query param is redacted from logs
        val crlfNode = WorkflowNode(
            id = "http_crlf",
            name = "CRLF",
            type = NodeType.HTTP_ACTION,
            configJson = """{"url":"https://example.com/hook?api_key=super_secret_123&mode=1","method":"GET","headers":"X-Custom: ${'$'}{untrusted}"}""",
        )
        val ctx = ExecutionContext(mapOf("untrusted" to "abc\r\nX-Injected: evil"))
        val crlfRes = executor.execute(crlfNode, ctx, null)
        assertTrue(crlfRes is NodeExecutionResult.Success)
        assertEquals("abcX-Injected: evil", capturedHeaderValue)
        val logMsg = (crlfRes as NodeExecutionResult.Success).logMessage
        assertFalse("Sensitive query param must be redacted from logs", logMsg.contains("super_secret_123"))
        assertTrue(logMsg.contains("api_key=***"))
    }

    @Test
    fun testWorkflowEngine_disabledConditionDoesNotExecuteOutgoingBranches() = runTest {
        val n1 = WorkflowNode(id = "n1", name = "Start", type = NodeType.MANUAL_TRIGGER)
        val cond = WorkflowNode(
            id = "n_cond",
            name = "Disabled Cond",
            type = NodeType.CONDITION,
            isEnabled = false,
            configJson = """{"variable":"x","operator":"==","value":"1"}""",
        )
        val trueBranch = WorkflowNode(id = "n_true", name = "True Branch", type = NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"T"}""")
        val falseBranch = WorkflowNode(id = "n_false", name = "False Branch", type = NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"F"}""")

        val workflow = Workflow(
            id = "wf_disabled_cond",
            name = "Disabled Condition",
            nodes = listOf(n1, cond, trueBranch, falseBranch),
            connections = listOf(
                WorkflowConnection("n1", "n_cond"),
                WorkflowConnection("n_cond", "n_true", outputPort = "true"),
                WorkflowConnection("n_cond", "n_false", outputPort = "false"),
            ),
        )

        val engine = WorkflowEngine(PreconditionEvaluator(null))
        val result = engine.executeWorkflow(workflow, ExecutionContext(), null)
        assertTrue(result is WorkflowExecutionResult.Success)
        val steps = (result as WorkflowExecutionResult.Success).steps
        assertEquals(NodeStepState.SKIPPED, steps.first { it.nodeId == "n_cond" }.state)
        assertTrue(steps.none { it.nodeId == "n_true" && it.state == NodeStepState.SUCCESS })
        assertTrue(steps.none { it.nodeId == "n_false" && it.state == NodeStepState.SUCCESS })
    }

    @Test
    fun testNotificationTriggerExecutor_dotStarRegexMatchesExactPackageName() = runTest {
        val executor = NotificationTriggerExecutor()
        val node = WorkflowNode(
            id = "n_notif",
            name = "Notif",
            type = NodeType.NOTIFICATION_TRIGGER,
            configJson = """{"packageFilter":".*com\\.bank.*","keywordRegex":".*"}""",
        )
        val ctx = ExecutionContext(mapOf("package_name" to "com.bank", "text" to "OTP 1234"))
        val res = executor.execute(node, ctx, null)
        assertTrue("Regex .*com\\.bank.* must match exact package 'com.bank'", res is NodeExecutionResult.Success)
    }

    @Test
    fun testPreconditionEvaluator_cyclicChainCheckWithRetriesDoesNotExceedMaxDepth() = runTest {
        val evaluator = PreconditionEvaluator(context = null, delayDuration = {})
        val innerCyclicRef = Precondition.ChainCheck(
            id = "chain_self_1",
            name = "Inner Self Ref",
            nodes = emptyList(),
            connections = emptyList(),
            onFailureAction = "RETRY",
            maxRetries = 2,
            retryIntervalSeconds = 1,
        )
        val outerChain = Precondition.ChainCheck(
            id = "chain_self_1",
            name = "Outer Chain",
            nodes = listOf(
                WorkflowNode(
                    id = "cn1",
                    name = "Step 1",
                    type = NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"ok"}""",
                    preconditions = listOf(innerCyclicRef),
                ),
            ),
            connections = emptyList(),
            onFailureAction = "RETRY",
            maxRetries = 2,
            retryIntervalSeconds = 1,
        )
        val result = evaluator.evaluate(outerChain)
        assertTrue(result is PreconditionResult.Failed)
        assertTrue((result as PreconditionResult.Failed).reason.contains("Circular precondition"))
    }

    @Test
    fun testQueueTimeout_marksIsQueueTimeoutTrueAndEnforcesWallClockDeadline() = runTest {
        var fakeWallMs = 1_000_000L
        val failingClient = OkHttpClient.Builder().addInterceptor { chain ->
            fakeWallMs += 25_000L // simulate slow probe/network attempt
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Down")
                .body("down".toResponseBody())
                .build()
        }.build()

        val evaluator = PreconditionEvaluator(
            context = null,
            okHttpClient = failingClient,
            delayDuration = { fakeWallMs += 1_000L },
            currentTimeMs = { fakeWallMs },
        )
        val engine = WorkflowEngine(evaluator, executorFactory = NodeExecutorFactory(failingClient))

        val trigger = WorkflowNode("t1", "Trigger", NodeType.MANUAL_TRIGGER)
        val action = WorkflowNode(
            id = "a1",
            name = "Queued Action",
            type = NodeType.HTTP_ACTION,
            configJson = """{"url":"https://example.com/hook"}""",
            preconditions = listOf(
                Precondition.HealthCheck(
                    id = "hc_q",
                    isEnabled = true,
                    targetUrl = "https://example.com/health",
                    expectedStatus = 200,
                    onFailureAction = "QUEUE",
                    queueTimeoutMinutes = 1,
                    retryIntervalSeconds = 10,
                ),
            ),
        )
        val workflow = Workflow(
            id = "WFTOUT01",
            name = "Queue Timeout WF",
            isEnabled = true,
            nodes = listOf(trigger, action),
            connections = listOf(WorkflowConnection("t1", "a1")),
        )

        val ctx = ExecutionContext(mapOf("_queue_deadline_ms" to (fakeWallMs + 30_000L)))
        val result = engine.executeWorkflow(workflow, ctx, null)

        assertTrue(result is WorkflowExecutionResult.PreconditionFailed)
        val pf = result as WorkflowExecutionResult.PreconditionFailed
        assertTrue("isQueueTimeout must be true when deadline exceeded", pf.isQueueTimeout)
        assertTrue("Execution logs must not stay marked queued on timeout", pf.logs.none { it.contains("⏸️ Precondition Queued") })
    }
}
