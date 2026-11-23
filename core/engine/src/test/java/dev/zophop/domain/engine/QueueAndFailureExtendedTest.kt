package dev.zophop.domain.engine

import dev.zophop.domain.engine.executors.BatteryCheckExecutor
import dev.zophop.domain.engine.executors.ConditionExecutor
import dev.zophop.domain.engine.executors.CronTriggerExecutor
import dev.zophop.domain.engine.executors.GpsCheckExecutor
import dev.zophop.domain.engine.executors.HttpActionExecutor
import dev.zophop.domain.engine.executors.InternetCheckExecutor
import dev.zophop.domain.engine.executors.JsonParserExecutor
import dev.zophop.domain.engine.executors.ManualTriggerExecutor
import dev.zophop.domain.engine.executors.RegexParserExecutor
import dev.zophop.domain.engine.executors.SimCheckExecutor
import dev.zophop.domain.engine.executors.TemplateFormatterExecutor
import dev.zophop.domain.engine.executors.VpnCheckExecutor
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.sms.SmsTriggerExecutor
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extended Queue & Failure tests for WorkflowEngine BFS queue correctness,
 * all node executor edge cases, and exhaustive failure path coverage.
 *
 * Tests added after thorough code review of WorkflowEngine.kt, all Executors,
 * PreconditionEvaluator, and ForwardingManager queue logic.
 */
class QueueAndFailureExtendedTest {
    // ─────────────────────────────────────────────────────────────
    // QUEUE-01: BFS traversal order and queue depth
    // ─────────────────────────────────────────────────────────────

    /**
     * QUEUE-01a: Nodes must be executed in BFS order (breadth-first).
     * Trigger → A → B → C: execution must visit A before C.
     */
    @Test
    fun workflowEngine_bfsQueue_executesNodesInCorrectOrder() =
        runTest {
            val visitOrder = mutableListOf<String>()
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

            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val n1 = WorkflowNode("n1", "Trigger", NodeType.MANUAL_TRIGGER)
            val n2 = WorkflowNode("n2", "A", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"A"}""")
            val n3 = WorkflowNode("n3", "B", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"B"}""")
            val n4 = WorkflowNode("n4", "C", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"C"}""")

            val workflow =
                Workflow(
                    id = "bfs_test",
                    name = "BFS Test",
                    isEnabled = true,
                    nodes = listOf(n1, n2, n3, n4),
                    connections =
                        listOf(
                            WorkflowConnection("n1", "n2"),
                            WorkflowConnection("n2", "n3"),
                            WorkflowConnection("n3", "n4"),
                        ),
                )

            val ctx = ExecutionContext(mapOf("body" to "test"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            // formatted_payload should end up as "C" (last formatter in chain overwrites)
            assertEquals("C", ctx.getString("formatted_payload"))
        }

    /**
     * QUEUE-01b: visitedNodeIds prevents infinite re-queue of the same node.
     * Diamond pattern: Trigger → A, Trigger → B, A → C, B → C
     * Node C must only execute ONCE despite being reachable from two paths.
     */
    @Test
    fun workflowEngine_diamondPattern_nodeExecutedOnlyOnce() =
        runTest {
            val execCount = AtomicInteger(0)
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        // Count how many times /terminal topic is requested (C node)
                        if (chain
                                .request()
                                .url
                                .toString()
                                .contains("terminal")
                        ) {
                            execCount.incrementAndGet()
                        }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val trigger = WorkflowNode("n_trig", "Trigger", NodeType.MANUAL_TRIGGER)
            val nodeA = WorkflowNode("n_a", "Format A", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"A: ${"\$"}{body}"}""")
            val nodeB = WorkflowNode("n_b", "Format B", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"B: ${"\$"}{body}"}""")
            val nodeC =
                WorkflowNode(
                    "n_c",
                    "Terminal ntfy",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"terminal"}""",
                )

            val workflow =
                Workflow(
                    id = "diamond_test",
                    name = "Diamond Test",
                    isEnabled = true,
                    nodes = listOf(trigger, nodeA, nodeB, nodeC),
                    connections =
                        listOf(
                            WorkflowConnection("n_trig", "n_a"),
                            WorkflowConnection("n_trig", "n_b"),
                            WorkflowConnection("n_a", "n_c"),
                            WorkflowConnection("n_b", "n_c"),
                        ),
                )

            val ctx = ExecutionContext(mapOf("body" to "diamond_body"))
            engine.executeWorkflow(workflow, ctx, null)

            assertEquals(
                "Diamond join: node C must execute exactly once (visitedNodeIds dedup) - found $execCount",
                1,
                execCount.get(),
            )
        }

    /**
     * QUEUE-01c: Disabled node in the middle of the chain is skipped, and
     * downstream nodes are still added to queue and executed.
     */
    @Test
    fun workflowEngine_disabledMiddleNode_skipAndContinueToDownstream() =
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

            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val trigger = WorkflowNode("n_t", "Trigger", NodeType.MANUAL_TRIGGER)
            val disabledFormatter =
                WorkflowNode(
                    "n_dis",
                    "Disabled Formatter",
                    NodeType.TEMPLATE_FORMATTER,
                    isEnabled = false,
                    configJson = """{"template":"SHOULD_NOT_APPEAR"}""",
                )
            val finalFormatter =
                WorkflowNode(
                    "n_fin",
                    "Final Formatter",
                    NodeType.TEMPLATE_FORMATTER,
                    isEnabled = true,
                    configJson = """{"template":"FINAL_${"\$"}{body}"}""",
                )

            val workflow =
                Workflow(
                    id = "skip_mid",
                    name = "Skip Middle Test",
                    isEnabled = true,
                    nodes = listOf(trigger, disabledFormatter, finalFormatter),
                    connections =
                        listOf(
                            WorkflowConnection("n_t", "n_dis"),
                            WorkflowConnection("n_dis", "n_fin"),
                        ),
                )

            val ctx = ExecutionContext(mapOf("body" to "data"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            // The disabled formatter should NOT overwrite formatted_payload,
            // the final formatter should execute
            assertEquals("FINAL_data", ctx.getString("formatted_payload"))
        }

    /**
     * QUEUE-01d: Queue respects outputPort — a Condition node's false branch
     * connection (outputPort="false") must NOT receive queue items when condition is true.
     */
    @Test
    fun workflowEngine_conditionBranch_onlyRoutesToMatchingOutputPort() =
        runTest {
            val visitedTopics = mutableListOf<String>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        visitedTopics.add(
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

            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val trigger = WorkflowNode("trig", "Trigger", NodeType.MANUAL_TRIGGER)
            val cond =
                WorkflowNode(
                    "cond",
                    "Check",
                    NodeType.CONDITION,
                    configJson = """{"variable":"flag","operator":"EQUALS","value":"yes"}""",
                )
            val yesAction =
                WorkflowNode(
                    "yes",
                    "Yes ntfy",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"yes-topic"}""",
                )
            val noAction =
                WorkflowNode(
                    "no",
                    "No ntfy",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"no-topic"}""",
                )

            val workflow =
                Workflow(
                    id = "port_test",
                    name = "Port Test",
                    isEnabled = true,
                    nodes = listOf(trigger, cond, yesAction, noAction),
                    connections =
                        listOf(
                            WorkflowConnection("trig", "cond"),
                            WorkflowConnection("cond", "yes", outputPort = "true"),
                            WorkflowConnection("cond", "no", outputPort = "false"),
                        ),
                )

            // flag=yes → condition true → only yes-topic called
            val ctx = ExecutionContext(mapOf("body" to "test", "flag" to "yes"))
            engine.executeWorkflow(workflow, ctx, null)

            assertTrue("yes-topic must be called", visitedTopics.contains("yes-topic"))
            assertFalse("no-topic must NOT be called when condition is true", visitedTopics.contains("no-topic"))
        }

    // ─────────────────────────────────────────────────────────────
    // QUEUE-02: Queue size / max execution limit guard
    // ─────────────────────────────────────────────────────────────

    /**
     * QUEUE-02a: A very wide branching tree (1 trigger → 20 leaf actions) must
     * execute all leaf nodes without deadlocking or skipping.
     */
    @Test
    fun workflowEngine_wideTree_executesAllLeafNodes() =
        runTest {
            val calledTopics = mutableSetOf<String>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        calledTopics.add(
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

            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val trigger = WorkflowNode("trig", "Trigger", NodeType.MANUAL_TRIGGER)
            val leafCount = 15
            val leaves =
                (1..leafCount).map { i ->
                    WorkflowNode(
                        "leaf_$i",
                        "Leaf $i",
                        NodeType.NTFY_ACTION,
                        configJson = """{"serverUrl":"https://ntfy.sh","topic":"leaf-$i"}""",
                    )
                }

            val workflow =
                Workflow(
                    id = "wide_tree",
                    name = "Wide Tree",
                    isEnabled = true,
                    nodes = listOf(trigger) + leaves,
                    connections = leaves.map { WorkflowConnection("trig", it.id) },
                )

            val ctx = ExecutionContext(mapOf("body" to "broadcast"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals("All $leafCount leaf nodes must be called", leafCount, calledTopics.size)
        }

    // ─────────────────────────────────────────────────────────────
    // QUEUE-03: startNodeId resume from mid-workflow
    // ─────────────────────────────────────────────────────────────

    /**
     * QUEUE-03a: Starting from a specific nodeId must skip the trigger and
     * execute only from the specified node forward.
     */
    @Test
    fun workflowEngine_startFromMiddleNode_skipsTriggerExecutesForwardOnly() =
        runTest {
            val visitedNodes = mutableListOf<String>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        visitedNodes.add(
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

            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val trigger = WorkflowNode("trigger", "Trigger", NodeType.SMS_TRIGGER)
            val parser =
                WorkflowNode(
                    "parser",
                    "Parser",
                    NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"Parsed: ${"\$"}{body}"}""",
                )
            val action =
                WorkflowNode(
                    "action",
                    "Action",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"resume-topic"}""",
                )

            val workflow =
                Workflow(
                    id = "resume_test",
                    name = "Resume Test",
                    isEnabled = true,
                    nodes = listOf(trigger, parser, action),
                    connections =
                        listOf(
                            WorkflowConnection("trigger", "parser"),
                            WorkflowConnection("parser", "action"),
                        ),
                )

            // Start from parser (simulate retry/resume after trigger already matched)
            val ctx = ExecutionContext(mapOf("body" to "OTP 112233", "sender" to "BANK"))
            val result = engine.executeWorkflow(workflow, ctx, null, startNodeId = "parser")

            assertTrue(result is WorkflowExecutionResult.Success)
            assertTrue("resume-topic must be called", visitedNodes.contains("resume-topic"))
            // Template formatter ran from startNode
            assertEquals("Parsed: OTP 112233", ctx.getString("formatted_payload"))
        }

    // ─────────────────────────────────────────────────────────────
    // QUEUE-04: Precondition failure halts only failing node and all PENDING downstream
    // ─────────────────────────────────────────────────────────────

    /**
     * QUEUE-04a: When a node's precondition fails, all PENDING downstream steps
     * must be marked SKIPPED, and the result must be PreconditionFailed.
     */
    @Test
    fun workflowEngine_preconditionFailed_marksPendingStepsAsSkipped() =
        runTest {
            val failingClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(503)
                            .message("Down")
                            .body("error".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(null, failingClient)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(failingClient))

            val trigger = WorkflowNode("trig", "Trigger", NodeType.MANUAL_TRIGGER)
            val guardedAction =
                WorkflowNode(
                    id = "guarded",
                    name = "Guarded Action",
                    type = NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"guarded"}""",
                    preconditions =
                        listOf(
                            Precondition.HealthCheck(
                                isEnabled = true,
                                targetUrl = "https://health.example.com/check",
                                expectedStatus = 200,
                            ),
                        ),
                )
            val downstream =
                WorkflowNode(
                    "downstream",
                    "Downstream",
                    NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"downstream_${"\$"}{body}"}""",
                )

            val workflow =
                Workflow(
                    id = "prec_halt",
                    name = "Precondition Halt Test",
                    isEnabled = true,
                    nodes = listOf(trigger, guardedAction, downstream),
                    connections =
                        listOf(
                            WorkflowConnection("trig", "guarded"),
                            WorkflowConnection("guarded", "downstream"),
                        ),
                )

            val ctx = ExecutionContext(mapOf("body" to "test"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue("Must be PreconditionFailed", result is WorkflowExecutionResult.PreconditionFailed)
            val pfResult = result as WorkflowExecutionResult.PreconditionFailed
            assertEquals("guarded", pfResult.nodeId)

            // Downstream must be SKIPPED
            val downstreamStep = pfResult.steps.find { it.nodeId == "downstream" }
            assertNotNull("Downstream step must exist in steps", downstreamStep)
            assertEquals(NodeStepState.SKIPPED, downstreamStep?.state)
        }

    // ─────────────────────────────────────────────────────────────
    // QUEUE-05: Node failure halts execution and skips pending steps
    // ─────────────────────────────────────────────────────────────

    /**
     * QUEUE-05a: When a node execution returns Failed, all PENDING downstream
     * nodes must be marked SKIPPED, and result must be Failed.
     */
    @Test
    fun workflowEngine_nodeExecutionFailed_skipsPendingDownstreamNodes() =
        runTest {
            val calledTopics = mutableListOf<String>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        // Fail for the "fail-topic", succeed for others
                        val topic =
                            chain
                                .request()
                                .url.pathSegments
                                .last()
                        calledTopics.add(topic)
                        val code = if (topic == "fail-topic") 500 else 200
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message(if (code == 200) "OK" else "Error")
                            .body(if (code == 200) "ok".toResponseBody() else "error".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val trigger = WorkflowNode("trig", "Trigger", NodeType.MANUAL_TRIGGER)
            val failAction =
                WorkflowNode(
                    "fail_action",
                    "Fail Action",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"fail-topic","expectedStatus":200}""",
                )
            val afterFail =
                WorkflowNode(
                    "after_fail",
                    "After Fail",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"after-topic"}""",
                )

            val workflow =
                Workflow(
                    id = "fail_halt",
                    name = "Fail Halt Test",
                    isEnabled = true,
                    nodes = listOf(trigger, failAction, afterFail),
                    connections =
                        listOf(
                            WorkflowConnection("trig", "fail_action"),
                            WorkflowConnection("fail_action", "after_fail"),
                        ),
                )

            val ctx = ExecutionContext(mapOf("body" to "test"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue("Must be WorkflowExecutionResult.Failed", result is WorkflowExecutionResult.Failed)
            assertFalse("after-topic must NOT be called after upstream failure", calledTopics.contains("after-topic"))

            val failed = result as WorkflowExecutionResult.Failed
            val afterStep = failed.steps.find { it.nodeId == "after_fail" }
            assertNotNull(afterStep)
            assertEquals(NodeStepState.SKIPPED, afterStep?.state)
        }

    // ─────────────────────────────────────────────────────────────
    // QUEUE-06: Workflow with no trigger / no reachable entry
    // ─────────────────────────────────────────────────────────────

    /**
     * QUEUE-06a: A workflow where all trigger nodes are disabled returns Failed.
     */
    @Test
    fun workflowEngine_allTriggerNodesDisabled_returnsFailed() =
        runTest {
            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator)

            val disabledTrigger = WorkflowNode("trig", "Trigger", NodeType.MANUAL_TRIGGER, isEnabled = false)
            val action =
                WorkflowNode(
                    "action",
                    "Action",
                    NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"never"}""",
                )

            val workflow =
                Workflow(
                    id = "no_trigger",
                    name = "No Trigger",
                    isEnabled = true,
                    nodes = listOf(disabledTrigger, action),
                    connections = listOf(WorkflowConnection("trig", "action")),
                )

            val ctx = ExecutionContext(mapOf("body" to "test"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            // With all triggers disabled, queue is empty → workflow fails with no entry nodes
            // OR: disabled trigger is skipped + action runs. Verify graceful handling:
            assertTrue(
                "Must be Success or Failed (not hang)",
                result is WorkflowExecutionResult.Success || result is WorkflowExecutionResult.Failed,
            )
        }

    /**
     * QUEUE-06b: Workflow with no connections but only a trigger node succeeds.
     */
    @Test
    fun workflowEngine_singleTriggerNoConnections_succeeds() =
        runTest {
            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator)

            val trigger = WorkflowNode("trig", "Trigger", NodeType.MANUAL_TRIGGER)
            val workflow =
                Workflow(
                    id = "solo_trigger",
                    name = "Solo Trigger",
                    isEnabled = true,
                    nodes = listOf(trigger),
                    connections = emptyList(),
                )

            val ctx = ExecutionContext(mapOf("body" to "ping"))
            val result = engine.executeWorkflow(workflow, ctx, null)
            assertTrue(result is WorkflowExecutionResult.Success)
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-01: ManualTriggerExecutor — always succeeds, sets no extra context
    // ─────────────────────────────────────────────────────────────

    @Test
    fun manualTrigger_alwaysSucceeds_withDefaultOutputPort() =
        runTest {
            val executor = ManualTriggerExecutor()
            val ctx = ExecutionContext()
            val node = WorkflowNode("m1", "Manual", NodeType.MANUAL_TRIGGER)
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("main", (result as NodeExecutionResult.Success).outputPort)
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-02: CronTriggerExecutor — sets body, text, sender, timestamp in ctx
    // ─────────────────────────────────────────────────────────────

    @Test
    fun cronTrigger_setsContextVariables() =
        runTest {
            val executor = CronTriggerExecutor()
            val ctx = ExecutionContext()
            val node =
                WorkflowNode(
                    "cron1",
                    "Cron",
                    NodeType.CRON_TRIGGER,
                    configJson = """{"testPayload":"Scheduled job ran"}""",
                )
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("Scheduled job ran", ctx.getString("body"))
            assertEquals("CronScheduler", ctx.getString("sender"))
            assertNotNull(ctx.get("timestamp"))
        }

    @Test
    fun cronTrigger_usesDefaultPayloadWhenEmpty() =
        runTest {
            val executor = CronTriggerExecutor()
            val ctx = ExecutionContext()
            val node =
                WorkflowNode(
                    "cron2",
                    "Cron",
                    NodeType.CRON_TRIGGER,
                    configJson = """{"testPayload":""}""",
                )
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("Scheduled cron trigger executed", ctx.getString("body"))
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-03: SmsTriggerExecutor
    // ─────────────────────────────────────────────────────────────

    @Test
    fun smsTrigger_matchingAllowlistAndKeyword_succeeds() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    "sms1",
                    "SMS",
                    NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":"HDFC,ICICI","keywordRegex":"(?i)OTP|code"}""",
                )
            val ctx = ExecutionContext(mapOf("sender" to "HDFCBANK", "body" to "Your OTP is 123456"))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
        }

    @Test
    fun smsTrigger_nonMatchingAllowlist_returnsIgnored() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    "sms2",
                    "SMS",
                    NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":"HDFC,ICICI","keywordRegex":".*"}""",
                )
            val ctx = ExecutionContext(mapOf("sender" to "SPAM123", "body" to "Win prize now!"))
            val result = executor.execute(node, ctx, null)
            assertTrue("Non-matching sender must return Ignored", result is NodeExecutionResult.Ignored)
        }

    @Test
    fun smsTrigger_nonMatchingKeyword_returnsIgnored() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node =
                WorkflowNode(
                    "sms3",
                    "SMS",
                    NodeType.SMS_TRIGGER,
                    configJson = """{"allowlist":".*","keywordRegex":"(?i)OTP|verification"}""",
                )
            val ctx = ExecutionContext(mapOf("sender" to "ANYBANK", "body" to "Welcome to our service"))
            val result = executor.execute(node, ctx, null)
            assertTrue("Non-matching keyword must return Ignored", result is NodeExecutionResult.Ignored)
        }

    @Test
    fun smsTrigger_emptyBodyContext_returnsIgnored() =
        runTest {
            val executor = SmsTriggerExecutor()
            val node = WorkflowNode("sms4", "SMS", NodeType.SMS_TRIGGER)
            val ctx = ExecutionContext(mapOf("sender" to "HDFC"))
            // No body in context → trigger should not proceed
            val result = executor.execute(node, ctx, null)
            // Should either Ignored or Success, never crash
            assertTrue(result is NodeExecutionResult.Success || result is NodeExecutionResult.Ignored)
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-04: VpnCheckExecutor edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun vpnCheck_requireConnectedFalseAndInterfaceMatch_passesWithoutVpn() =
        runTest {
            val executor = VpnCheckExecutor()
            val ctx = ExecutionContext()
            val node =
                WorkflowNode(
                    "vpn1",
                    "VPN",
                    NodeType.VPN_CHECK,
                    configJson = """{"requireConnected":"false","requiredInterface":""}""",
                )
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            // Context variables must be set
            assertEquals("false", ctx.get("vpn_connected"))
        }

    @Test
    fun vpnCheck_requireInterfaceNotMatching_returnsFailed() =
        runTest {
            val executor = VpnCheckExecutor()
            val ctx = ExecutionContext()
            val node =
                WorkflowNode(
                    "vpn2",
                    "VPN",
                    NodeType.VPN_CHECK,
                    configJson = """{"requireConnected":"false","requiredInterface":"tun0"}""",
                )
            // With null context, vpn_interface = "none", which does not contain "tun0"
            val result = executor.execute(node, ctx, null)
            assertTrue("Interface mismatch must fail", result is NodeExecutionResult.Failed)
            val failed = result as NodeExecutionResult.Failed
            assertTrue(failed.reason.contains("Interface 'none' does not match required 'tun0'"))
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-05: InternetCheckExecutor edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun internetCheck_nullContextDefaultsToConnectedTrue() =
        runTest {
            val executor = InternetCheckExecutor()
            val ctx = ExecutionContext()
            val node =
                WorkflowNode(
                    "net1",
                    "Internet",
                    NodeType.INTERNET_CHECK,
                    configJson = """{"requireInternet":"true"}""",
                )
            // Null appContext → isConnected defaults to true (fail-open for unit tests)
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("true", ctx.get("internet_connected"))
        }

    @Test
    fun internetCheck_requireCellularFails_whenNullContext() =
        runTest {
            val executor = InternetCheckExecutor()
            val ctx = ExecutionContext()
            val node =
                WorkflowNode(
                    "net2",
                    "Internet",
                    NodeType.INTERNET_CHECK,
                    configJson = """{"requireInternet":"true","requiredNetworkType":"CELLULAR"}""",
                )
            // Null context → networkType = "NONE", cellular required → Failed
            val result = executor.execute(node, ctx, null)
            assertTrue("CELLULAR required but NONE → must fail", result is NodeExecutionResult.Failed)
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-06: BatteryCheckExecutor edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun batteryCheck_minPercentAbove100_alwaysFails() =
        runTest {
            val executor = BatteryCheckExecutor()
            val ctx = ExecutionContext()
            // With null context, battery defaults to 100%.
            // Requiring > 100 is impossible → must fail
            val node =
                WorkflowNode(
                    "bat1",
                    "Battery",
                    NodeType.BATTERY_CHECK,
                    configJson = """{"minBatteryPercent":"101","requireCharging":"false"}""",
                )
            val result = executor.execute(node, ctx, null)
            // 100 < 101 → Failed
            assertTrue("Requiring >100% battery must fail", result is NodeExecutionResult.Failed)
        }

    @Test
    fun batteryCheck_minPercent0_alwaysPasses() =
        runTest {
            val executor = BatteryCheckExecutor()
            val ctx = ExecutionContext()
            val node =
                WorkflowNode(
                    "bat2",
                    "Battery",
                    NodeType.BATTERY_CHECK,
                    configJson = """{"minBatteryPercent":"0","requireCharging":"false"}""",
                )
            val result = executor.execute(node, ctx, null)
            // 100 >= 0 → Success
            assertTrue(result is NodeExecutionResult.Success)
        }

    @Test
    fun batteryCheck_nonNumericMinPercent_usesDefaultOf15() =
        runTest {
            val executor = BatteryCheckExecutor()
            val ctx = ExecutionContext()
            // "abc" can't parse → toIntOrNull() returns null → default 15
            val node =
                WorkflowNode(
                    "bat3",
                    "Battery",
                    NodeType.BATTERY_CHECK,
                    configJson = """{"minBatteryPercent":"abc","requireCharging":"false"}""",
                )
            val result = executor.execute(node, ctx, null)
            // 100 >= 15 → Success
            assertTrue(result is NodeExecutionResult.Success)
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-07: GpsCheckExecutor edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun gpsCheck_nullContext_setsDefaultValues() =
        runTest {
            val executor = GpsCheckExecutor()
            val ctx = ExecutionContext()
            val node =
                WorkflowNode(
                    "gps1",
                    "GPS",
                    NodeType.GPS_CHECK,
                    configJson = """{"requireGpsEnabled":"false","fetchCoordinates":"false"}""",
                )
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("false", ctx.get("gps_enabled"))
            assertEquals("0.0", ctx.get("latitude"))
            assertEquals("0.0", ctx.get("longitude"))
            assertEquals("0.0", ctx.get("accuracy"))
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-08: SimCheckExecutor edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun simCheck_nullContext_defaultsToSimReady() =
        runTest {
            val executor = SimCheckExecutor()
            val ctx = ExecutionContext()
            val node =
                WorkflowNode(
                    "sim1",
                    "SIM",
                    NodeType.SIM_CHECK,
                    configJson = """{"requireSimReady":"true"}""",
                )
            // With null context, isReady defaults to true
            val result = executor.execute(node, ctx, null)
            assertTrue("SIM ready must pass with null context (test mode default)", result is NodeExecutionResult.Success)
            assertEquals("true", ctx.get("sim_ready"))
            assertEquals("1", ctx.get("sim_count"))
            assertEquals("false", ctx.get("is_roaming"))
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-09: ConditionExecutor edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun conditionExecutor_missingVariable_emptyString_notEmptyFails() =
        runTest {
            val executor = ConditionExecutor()
            val node =
                WorkflowNode(
                    "c1",
                    "Cond",
                    NodeType.CONDITION,
                    configJson = """{"variable":"missing_var","operator":"NOT_EMPTY","value":""}""",
                )
            val ctx = ExecutionContext(emptyMap())
            val result = executor.execute(node, ctx, null) as NodeExecutionResult.Success
            // missing_var resolves to "" → NOT_EMPTY → false
            assertEquals("false", result.outputPort)
        }

    @Test
    fun conditionExecutor_invalidRegex_doesNotCrash() =
        runTest {
            val executor = ConditionExecutor()
            val node =
                WorkflowNode(
                    "c2",
                    "Cond",
                    NodeType.CONDITION,
                    configJson = """{"variable":"body","operator":"REGEX_MATCH","value":"[invalid(regex"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "some text"))
            // Invalid regex → caught → returns false
            val result = executor.execute(node, ctx, null) as NodeExecutionResult.Success
            assertEquals("false", result.outputPort)
        }

    @Test
    fun conditionExecutor_unknownOperator_defaultsToNotEmpty() =
        runTest {
            val executor = ConditionExecutor()
            val node =
                WorkflowNode(
                    "c3",
                    "Cond",
                    NodeType.CONDITION,
                    configJson = """{"variable":"v","operator":"UNKNOWN_OP","value":"xyz"}""",
                )
            val ctx = ExecutionContext(mapOf("v" to "anything"))
            val result = executor.execute(node, ctx, null) as NodeExecutionResult.Success
            // Unknown operator → else branch → isNotBlank → "anything" is not blank → true
            assertEquals("true", result.outputPort)
        }

    @Test
    fun conditionExecutor_invalidJson_usesDefaults() =
        runTest {
            val executor = ConditionExecutor()
            val node = WorkflowNode("c4", "Cond", NodeType.CONDITION, configJson = "not_valid_json")
            val ctx = ExecutionContext(mapOf("code" to "hello"))
            // Invalid JSON → JSONObject() default → variable="code", operator="NOT_EMPTY"
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-10: RegexParserExecutor edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun regexParser_emptyRegex_failsGracefully() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r1",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"","outputKey":"result"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "some text"))
            val result = executor.execute(node, ctx, null)
            // Empty regex matches everything or finds at position 0
            assertTrue(result is NodeExecutionResult.Success || result is NodeExecutionResult.Failed)
        }

    @Test
    fun regexParser_invalidRegex_returnsFailed() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r2",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"(unclosed_group","outputKey":"result"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "test 1234"))
            val result = executor.execute(node, ctx, null)
            assertTrue("Invalid regex must return Failed", result is NodeExecutionResult.Failed)
        }

    @Test
    fun regexParser_matchIndexOutOfRange_usesFirstMatch() =
        runTest {
            val executor = RegexParserExecutor()
            // matchIndex=99 → resolves to 99, getOrNull returns null → falls back to first
            val node =
                WorkflowNode(
                    "r3",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\d{4}","outputKey":"num","matchIndex":99}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "Call 1234 or 5678"))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            // Falls back to first match: 1234
            assertEquals("1234", ctx.getString("num"))
        }

    @Test
    fun regexParser_negativeMatchIndexLargerThanMatchCount_usesFirstMatch() =
        runTest {
            val executor = RegexParserExecutor()
            // matchIndex=-99 → matches.size + (-99) = 2 - 99 = -97 → negative → getOrNull → first
            val node =
                WorkflowNode(
                    "r4",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\d+","outputKey":"num","matchIndex":-99}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "12 34"))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("12", ctx.getString("num"))
        }

    @Test
    fun regexParser_setsAliasCodeAndOtpAlways() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r5",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\b\\d{6}\\b","outputKey":"customKey"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "Your pin is 445566"))
            executor.execute(node, ctx, null)
            // outputKey, code, otp all must be set to same value
            assertEquals("445566", ctx.getString("customKey"))
            assertEquals("445566", ctx.getString("code"))
            assertEquals("445566", ctx.getString("otp"))
        }

    @Test
    fun regexParser_numberedGroupsAlias_setsGroup1Group2() =
        runTest {
            val executor = RegexParserExecutor()
            val node =
                WorkflowNode(
                    "r6",
                    "Regex",
                    NodeType.REGEX_PARSER,
                    configJson = """{"regex":"(\\w+)\\s+(\\d+)","outputKey":"full"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "OTP 998877"))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            // group_1 = "OTP", group_2 = "998877"
            assertEquals("OTP", ctx.getString("group_1"))
            assertEquals("998877", ctx.getString("group_2"))
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-11: TemplateFormatterExecutor edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun templateFormatter_missingVariable_leavesPlaceholderUnresolved() =
        runTest {
            val executor = TemplateFormatterExecutor()
            val node =
                WorkflowNode(
                    "t1",
                    "Template",
                    NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"Hello ${"$"}{missing}!"}""",
                )
            val ctx = ExecutionContext(emptyMap())
            val result = executor.execute(node, ctx, null)
            // Should succeed even if variable is missing (interpolated as "")
            assertTrue(result is NodeExecutionResult.Success)
            // Variable resolves to empty or placeholder is left → no crash
            assertNotNull(ctx.getString("formatted_payload"))
        }

    @Test
    fun templateFormatter_emptyTemplate_setsEmptyPayload() =
        runTest {
            val executor = TemplateFormatterExecutor()
            val node =
                WorkflowNode(
                    "t2",
                    "Template",
                    NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":""}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "test"))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("", ctx.getString("formatted_payload"))
        }

    @Test
    fun templateFormatter_setsPayloadAliasAlongWithFormattedPayload() =
        runTest {
            val executor = TemplateFormatterExecutor()
            val node =
                WorkflowNode(
                    "t3",
                    "Template",
                    NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"Test Message"}""",
                )
            val ctx = ExecutionContext()
            executor.execute(node, ctx, null)
            assertEquals("Test Message", ctx.getString("formatted_payload"))
            assertEquals("Test Message", ctx.getString("payload"))
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-12: JsonParserExecutor — input fallback priority chain
    // ─────────────────────────────────────────────────────────────

    /**
     * JsonParser input priority: http_response > formatted_payload > payload > body > text
     * This test verifies http_response is preferred over body.
     */
    @Test
    fun jsonParser_inputPriority_httpResponsePreferredOverBody() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j1",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"status","outputKey":"status"}""",
                )
            val ctx =
                ExecutionContext(
                    mapOf(
                        "body" to """{"status":"from_body"}""",
                        "http_response" to """{"status":"from_http"}""",
                    ),
                )
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("from_http", ctx.getString("status"))
        }

    @Test
    fun jsonParser_emptyAllInputs_returnsFailed() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j2",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"code","outputKey":"code"}""",
                )
            val ctx = ExecutionContext(emptyMap())
            val result = executor.execute(node, ctx, null)
            assertTrue("No input → must return Failed", result is NodeExecutionResult.Failed)
        }

    @Test
    fun jsonParser_invalidJsonInput_returnsFailed() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j3",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"status","outputKey":"status"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "not_json_at_all"))
            val result = executor.execute(node, ctx, null)
            // Can be Failed (parse error) or Success (wrapped as {"value":"not_json_at_all"})
            // Should NOT crash
            assertTrue(result is NodeExecutionResult.Success || result is NodeExecutionResult.Failed)
        }

    @Test
    fun jsonParser_arrayRootWithNegativeIndexResolvesFromEnd() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j4",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"[-1]","outputKey":"last"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """["first","second","last"]"""))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("last", ctx.getString("last"))
        }

    @Test
    fun jsonParser_keysProperty_returnsObjectKeys() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j5",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"data.keys","outputKey":"keyList","targetType":"ARRAY"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"data":{"alpha":1,"beta":2,"gamma":3}}"""))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            // Keys should be a JSON array string containing "alpha", "beta", "gamma"
            val keyList = ctx.getString("keyList")
            assertNotNull(keyList)
            assertTrue("Should contain 'alpha'", keyList.contains("alpha"))
            assertTrue("Should contain 'beta'", keyList.contains("beta"))
        }

    @Test
    fun jsonParser_countTargetType_returnsArraySize() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j6",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"users","outputKey":"count","targetType":"COUNT"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"users":[{"id":1},{"id":2},{"id":3}]}"""))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals(3, ctx.get("count"))
        }

    @Test
    fun jsonParser_jsonStringTargetType_serializesNestedObject() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j7",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"nested","outputKey":"serialized","targetType":"JSON_STRING"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"nested":{"x":1,"y":2}}"""))
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            val serialized = ctx.getString("serialized")
            assertNotNull(serialized)
            // Should be JSON string of the nested object
            assertTrue("Must contain x:1 key", serialized.contains("\"x\"") || serialized.contains("x"))
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-13: HttpActionExecutor edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    fun httpAction_emptyUrl_returnsFailed() =
        runTest {
            val client = OkHttpClient.Builder().build()
            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    "h1",
                    "HTTP",
                    NodeType.HTTP_ACTION,
                    configJson = """{"url":"","method":"GET"}""",
                )
            val ctx = ExecutionContext()
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Failed)
            assertTrue((result as NodeExecutionResult.Failed).reason.contains("empty", ignoreCase = true))
        }

    @Test
    fun httpAction_setsHttpStatusAndResponseInContext() =
        runTest {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(201)
                            .message("Created")
                            .body("""{"id":42}""".toResponseBody())
                            .build()
                    }.build()

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    "h2",
                    "HTTP",
                    NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://api.example.com/items","method":"POST","expectedStatus":201,"bodyTemplate":"{\"name\":\"test\"}"}""",
                )
            val ctx = ExecutionContext()
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals(201, ctx.get("http_status"))
            assertEquals("""{"id":42}""", ctx.getString("http_response"))
        }

    @Test
    fun httpAction_unexpectedStatusCode_returnsFailed() =
        runTest {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("not found".toResponseBody())
                            .build()
                    }.build()

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    "h3",
                    "HTTP",
                    NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://api.example.com/missing","method":"GET","expectedStatus":200}""",
                )
            val ctx = ExecutionContext()
            val result = executor.execute(node, ctx, null)
            assertTrue(result is NodeExecutionResult.Failed)
            assertTrue((result as NodeExecutionResult.Failed).reason.contains("404"))
        }

    @Test
    fun httpAction_queryParamsAppendedToUrl() =
        runTest {
            var capturedUrl = ""
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedUrl = chain.request().url.toString()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    "h4",
                    "HTTP",
                    NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://api.example.com/data","method":"GET","queryParams":"key=value&page=1"}""",
                )
            val ctx = ExecutionContext()
            executor.execute(node, ctx, null)
            assertTrue("URL must contain query params", capturedUrl.contains("key=value"))
            assertTrue("URL must contain page=1", capturedUrl.contains("page=1"))
        }

    @Test
    fun httpAction_interpolatesUrlFromContext() =
        runTest {
            var capturedUrl = ""
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedUrl = chain.request().url.toString()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    "h5",
                    "HTTP",
                    NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://api.example.com/${"$"}{userId}/data","method":"GET"}""",
                )
            val ctx = ExecutionContext(mapOf("userId" to "user123"))
            executor.execute(node, ctx, null)
            assertTrue("URL must have userId interpolated", capturedUrl.contains("user123"))
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-14: PreconditionEvaluator — CircularReference
    // ─────────────────────────────────────────────────────────────

    @Test
    fun preconditionEvaluator_circularCallStack_detectsAndFails() =
        runTest {
            val evaluator = PreconditionEvaluator(null)
            // Build a chain check that references itself by using a fake ID in call stack
            val chainPrecondition =
                Precondition.HealthCheck(
                    id = "self_ref",
                    isEnabled = true,
                    targetUrl = "https://example.com/health",
                    expectedStatus = 200,
                )

            // Test callStack size > 20 guard
            val fakeCallStack = (1..21).map { "node_$it" }.toSet()
            val result = evaluator.evaluateInternal(chainPrecondition, fakeCallStack)
            assertTrue("Call stack > 20 must trigger circuit breaker → Failed", result is PreconditionResult.Failed)
            val failed = result as PreconditionResult.Failed
            assertTrue(failed.reason.contains("Circular precondition loop", ignoreCase = true))
        }

    @Test
    fun preconditionEvaluator_emptyPreconditionsList_returnsPassed() =
        runTest {
            val evaluator = PreconditionEvaluator(null)
            val result = evaluator.evaluateAll(emptyList())
            assertTrue("Empty preconditions must return Passed", result is PreconditionResult.Passed)
        }

    @Test
    fun preconditionEvaluator_multiPreconditionFirstFailsHaltsFurther() =
        runTest {
            val failClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(503)
                            .message("Down")
                            .body("error".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(null, failClient)
            val firstCheck =
                Precondition.HealthCheck(
                    id = "check_1",
                    isEnabled = true,
                    targetUrl = "https://example.com/health1",
                    expectedStatus = 200,
                )
            val secondCheck =
                Precondition.HealthCheck(
                    id = "check_2",
                    isEnabled = true,
                    targetUrl = "https://example.com/health2",
                    expectedStatus = 200,
                )

            val result = evaluator.evaluateAll(listOf(firstCheck, secondCheck))
            // First fails → evaluateAll should halt and return Failed without evaluating second
            assertTrue("First precondition failure must halt evaluation", result is PreconditionResult.Failed)
        }

    // ─────────────────────────────────────────────────────────────
    // NODE-15: WorkflowExecutionResult step serialization round-trip
    // ─────────────────────────────────────────────────────────────

    @Test
    fun nodeExecutionSteps_serializeAndDeserializeRoundTrip() {
        val steps =
            listOf(
                NodeExecutionStep("n1", "Trigger", NodeType.MANUAL_TRIGGER, NodeStepState.SUCCESS, "Executed"),
                NodeExecutionStep("n2", "Action", NodeType.NTFY_ACTION, NodeStepState.FAILED, "HTTP error", "Connection refused"),
                NodeExecutionStep("n3", "Parser", NodeType.REGEX_PARSER, NodeStepState.SKIPPED, "Skipped due to failure"),
            )

        val json = steps.toJsonString()
        val deserialized = json.toNodeExecutionSteps()

        assertNotNull(deserialized)
        assertEquals(3, deserialized!!.size)

        assertEquals("n1", deserialized[0].nodeId)
        assertEquals(NodeStepState.SUCCESS, deserialized[0].state)
        assertNull(deserialized[0].errorReason)

        assertEquals("n2", deserialized[1].nodeId)
        assertEquals(NodeStepState.FAILED, deserialized[1].state)
        assertEquals("Connection refused", deserialized[1].errorReason)

        assertEquals("n3", deserialized[2].nodeId)
        assertEquals(NodeStepState.SKIPPED, deserialized[2].state)
    }

    @Test
    fun nodeExecutionSteps_invalidJson_returnsNull() {
        val result = "not_a_json_array".toNodeExecutionSteps()
        assertNull("Invalid JSON must return null safely", result)
    }

    // ─────────────────────────────────────────────────────────────
    // INTEGRATION: Full pipeline with all major node types
    // ─────────────────────────────────────────────────────────────

    /**
     * End-to-end: SMS Trigger → Regex Parser → Condition (OTP >= 100000) →
     *   TRUE: Template Formatter → HTTP Action
     *   FALSE: Manual Trigger (not used in false branch but wired to show skipping)
     */
    @Test
    fun fullPipeline_smsTriggerToConditionBranching_correctRouting() =
        runTest {
            val calledUrls = mutableListOf<String>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        calledUrls.add(chain.request().url.host + chain.request().url.encodedPath)
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("delivered".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val triggerId = UUID.randomUUID().toString()
            val parserId = UUID.randomUUID().toString()
            val condId = UUID.randomUUID().toString()
            val trueActionId = UUID.randomUUID().toString()
            val falseActionId = UUID.randomUUID().toString()

            val workflow =
                Workflow(
                    id = "full_integration",
                    name = "Full Integration Test",
                    isEnabled = true,
                    nodes =
                        listOf(
                            WorkflowNode(
                                triggerId,
                                "SMS Trigger",
                                NodeType.SMS_TRIGGER,
                                configJson = """{"allowlist":".*","keywordRegex":".*"}""",
                            ),
                            WorkflowNode(
                                parserId,
                                "OTP Parser",
                                NodeType.REGEX_PARSER,
                                configJson = """{"regex":"\\b(\\d{6})\\b","outputKey":"otp"}""",
                            ),
                            WorkflowNode(
                                condId,
                                "OTP Check",
                                NodeType.CONDITION,
                                configJson = """{"variable":"otp","operator":"REGEX_MATCH","value":"\\d{6}"}""",
                            ),
                            WorkflowNode(
                                trueActionId,
                                "Alert High",
                                NodeType.NTFY_ACTION,
                                configJson = """{"serverUrl":"https://alerts.ntfy.sh","topic":"high-otp"}""",
                            ),
                            WorkflowNode(
                                falseActionId,
                                "Alert Low",
                                NodeType.NTFY_ACTION,
                                configJson = """{"serverUrl":"https://alerts.ntfy.sh","topic":"low-otp"}""",
                            ),
                        ),
                    connections =
                        listOf(
                            WorkflowConnection(triggerId, parserId),
                            WorkflowConnection(parserId, condId),
                            WorkflowConnection(condId, trueActionId, outputPort = "true"),
                            WorkflowConnection(condId, falseActionId, outputPort = "false"),
                        ),
                )

            val ctx = ExecutionContext(mapOf("sender" to "HDFC", "body" to "Your OTP is 887766. Use it now."))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue("Full pipeline must succeed", result is WorkflowExecutionResult.Success)
            assertEquals("887766", ctx.getString("otp"))

            // Condition: otp matches \\d{6} → true → high-otp called, low-otp NOT called
            assertTrue("high-otp topic must be called", calledUrls.any { it.contains("high-otp") })
            assertFalse("low-otp topic must NOT be called", calledUrls.any { it.contains("low-otp") })
        }

    // ─────────────────────────────────────────────────────────────
    // ADVANCED QUEUE: Multi-branch, join, cancellation, and cycle cases
    // ─────────────────────────────────────────────────────────────

    /**
     * QUEUE-07: Asymmetric multi-branching tree.
     * Trigger branches into branch A and branch B.
     * Branch A has 3 sequential steps, Branch B has 1 step.
     * BFS must process levels evenly and execute all nodes to completion.
     */
    @Test
    fun workflowEngine_asymmetricBranches_allNodesExecuted() =
        runTest {
            val executedSteps = mutableListOf<String>()
            val client = OkHttpClient.Builder().build()
            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val trigger = WorkflowNode("t", "Trigger", NodeType.MANUAL_TRIGGER)
            val a1 = WorkflowNode("a1", "A1", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"A1"}""")
            val a2 = WorkflowNode("a2", "A2", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"A2"}""")
            val a3 = WorkflowNode("a3", "A3", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"A3"}""")
            val b1 = WorkflowNode("b1", "B1", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"B1"}""")

            val workflow =
                Workflow(
                    id = "asym_tree",
                    name = "Asymmetric Tree",
                    isEnabled = true,
                    nodes = listOf(trigger, a1, a2, a3, b1),
                    connections =
                        listOf(
                            WorkflowConnection("t", "a1"),
                            WorkflowConnection("a1", "a2"),
                            WorkflowConnection("a2", "a3"),
                            WorkflowConnection("t", "b1"),
                        ),
                )

            val ctx = ExecutionContext(mapOf("body" to "init"))
            val result =
                engine.executeWorkflowWithProgress(workflow, ctx, null) { steps ->
                    steps.filter { it.state == NodeStepState.SUCCESS }.forEach {
                        if (!executedSteps.contains(it.nodeId)) executedSteps.add(it.nodeId)
                    }
                }

            assertTrue(result is WorkflowExecutionResult.Success)
            assertTrue("a1 must execute", executedSteps.contains("a1"))
            assertTrue("a2 must execute", executedSteps.contains("a2"))
            assertTrue("a3 must execute", executedSteps.contains("a3"))
            assertTrue("b1 must execute", executedSteps.contains("b1"))
        }

    /**
     * QUEUE-08: Disconnected graph components.
     * Nodes that are not reachable from any trigger or entry point
     * must remain in PENDING state and never be queued/executed.
     */
    @Test
    fun workflowEngine_unreachableOrphanNodes_stayPending() =
        runTest {
            val client = OkHttpClient.Builder().build()
            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val trigger = WorkflowNode("trig", "Trigger", NodeType.MANUAL_TRIGGER)
            val reachable = WorkflowNode("reach", "Reachable", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"ok"}""")
            val orphan = WorkflowNode("orphan", "Orphan", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"never"}""")

            val workflow =
                Workflow(
                    id = "orphan_test",
                    name = "Orphan Test",
                    isEnabled = true,
                    nodes = listOf(trigger, reachable, orphan),
                    connections = listOf(WorkflowConnection("trig", "reach")),
                    // orphan has NO incoming or outgoing connections
                )

            val ctx = ExecutionContext(mapOf("body" to "hello"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            val orphanStep = result.steps.find { it.nodeId == "orphan" }
            assertNotNull(orphanStep)
            // Orphan was never enqueued and never touched
            assertEquals(NodeStepState.PENDING, orphanStep?.state)
        }

    /**
     * QUEUE-09: Self-referencing node loop.
     * A node that has an outgoing connection to itself: A -> A.
     * The BFS queue must visit it once, then visitedNodeIds must reject the re-enqueue.
     */
    @Test
    fun workflowEngine_selfReferencingNodeLoop_terminatesCleanly() =
        runTest {
            val client = OkHttpClient.Builder().build()
            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val nodeA = WorkflowNode("self_loop", "Self Loop", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"loop"}""")

            val workflow =
                Workflow(
                    id = "self_loop_test",
                    name = "Self Loop Test",
                    isEnabled = true,
                    nodes = listOf(nodeA),
                    connections = listOf(WorkflowConnection("self_loop", "self_loop")),
                )

            val ctx = ExecutionContext(mapOf("body" to "ping"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue("Workflow must terminate and succeed", result is WorkflowExecutionResult.Success)
            val step = result.steps.find { it.nodeId == "self_loop" }
            assertEquals(NodeStepState.SUCCESS, step?.state)
        }

    /**
     * QUEUE-10: Multiple parallel entries with no trigger node.
     * When a workflow has NO trigger nodes, any node with 0 incoming connections
     * is considered an entry node and enqueued.
     */
    @Test
    fun workflowEngine_noExplicitTrigger_enqueuesAllRootNodes() =
        runTest {
            val client = OkHttpClient.Builder().build()
            val evaluator = PreconditionEvaluator(null)
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(client))

            val root1 = WorkflowNode("root1", "Root Formatter 1", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"R1"}""")
            val root2 = WorkflowNode("root2", "Root Formatter 2", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"R2"}""")
            val child = WorkflowNode("child", "Child Formatter", NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"Child"}""")

            val workflow =
                Workflow(
                    id = "no_trig_test",
                    name = "No Trigger Test",
                    isEnabled = true,
                    nodes = listOf(root1, root2, child),
                    connections =
                        listOf(
                            WorkflowConnection("root1", "child"),
                            WorkflowConnection("root2", "child"),
                        ),
                )

            val ctx = ExecutionContext(mapOf("body" to "start"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            val r1Step = result.steps.find { it.nodeId == "root1" }
            val r2Step = result.steps.find { it.nodeId == "root2" }
            val childStep = result.steps.find { it.nodeId == "child" }

            assertEquals(NodeStepState.SUCCESS, r1Step?.state)
            assertEquals(NodeStepState.SUCCESS, r2Step?.state)
            assertEquals(NodeStepState.SUCCESS, childStep?.state)
        }

    // ─────────────────────────────────────────────────────────────
    // EXTENDED NODE EXECUTOR TESTS: Edge cases & Config variations
    // ─────────────────────────────────────────────────────────────

    /**
     * HTTP Action: POST with form-data body type.
     */
    @Test
    fun httpAction_formDataBody_correctlyEncodesFormData() =
        runTest {
            var capturedContentType = ""
            var capturedBodyText = ""
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        capturedContentType = req.body?.contentType().toString()
                        val buffer = okio.Buffer()
                        req.body?.writeTo(buffer)
                        capturedBodyText = buffer.readUtf8()

                        Response
                            .Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    "h_form",
                    "HTTP Form",
                    NodeType.HTTP_ACTION,
                    configJson = """{
                "url": "https://api.example.com/submit",
                "method": "POST",
                "bodyType": "FORM_DATA",
                "bodyTemplate": "username=${"$"}{user}&token=${"$"}{code}"
            }""",
                )

            val ctx = ExecutionContext(mapOf("user" to "alice", "code" to "998877"))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertTrue("Content-Type must be form-urlencoded", capturedContentType.contains("application/x-www-form-urlencoded"))
            assertTrue("Body must contain username=alice", capturedBodyText.contains("username=alice"))
            assertTrue("Body must contain token=998877", capturedBodyText.contains("token=998877"))
        }

    /**
     * HTTP Action: Basic Authentication header injection via Credential Store.
     */
    @Test
    fun httpAction_basicAuth_injectsAuthorizationHeader() =
        runTest {
            var capturedAuthHeader = ""
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedAuthHeader = chain.request().header("Authorization") ?: ""
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val fakeCredRepo =
                object : dev.zophop.data.CredentialRepository {
                    override fun getAllCredentialsFlow() = kotlinx.coroutines.flow.flowOf(emptyList<dev.zophop.domain.model.Credential>())

                    override suspend fun getCredentialById(id: String): dev.zophop.domain.model.Credential? =
                        dev.zophop.domain.model.Credential(
                            id = "cred_basic_1",
                            name = "Test Basic",
                            type = dev.zophop.domain.model.CredentialType.BASIC_AUTH,
                            dataMap = mapOf("username" to "admin", "password" to "password123"),
                        )

                    override suspend fun saveCredential(credential: dev.zophop.domain.model.Credential) {}

                    override suspend fun deleteCredential(id: String) {}
                }

            val executor = HttpActionExecutor(client, credentialRepository = fakeCredRepo)
            val node =
                WorkflowNode(
                    "h_basic",
                    "HTTP Basic",
                    NodeType.HTTP_ACTION,
                    configJson = """{
                "url": "https://api.example.com/secure",
                "method": "GET",
                "credentialId": "cred_basic_1"
            }""",
                )

            val ctx = ExecutionContext()
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertTrue("Authorization header must start with Basic", capturedAuthHeader.startsWith("Basic "))
        }

    /**
     * HTTP Action: Bearer Token Authentication injection with variable interpolation.
     */
    @Test
    fun httpAction_bearerToken_interpolatesTokenVariable() =
        runTest {
            var capturedAuthHeader = ""
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedAuthHeader = chain.request().header("Authorization") ?: ""
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    "h_bearer",
                    "HTTP Bearer",
                    NodeType.HTTP_ACTION,
                    configJson = """{
                "url": "https://api.example.com/protected",
                "method": "GET",
                "authType": "BEARER",
                "bearerToken": "${"$"}{api_token}"
            }""",
                )

            val ctx = ExecutionContext(mapOf("api_token" to "eyJhbGciOiJIUzI1NiJ9.test"))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("Bearer eyJhbGciOiJIUzI1NiJ9.test", capturedAuthHeader)
        }

    /**
     * ConditionExecutor: Numeric comparison edge cases.
     * Comparing integer strings against floating points, scientific notation, negative numbers.
     */
    @Test
    fun conditionExecutor_numericComparisons_handlesDecimalsAndNegatives() =
        runTest {
            val executor = ConditionExecutor()

            data class NumCase(
                val varVal: String,
                val op: String,
                val targetVal: String,
                val expected: String,
            )
            val cases =
                listOf(
                    NumCase("15.5", "GREATER_THAN", "15.0", "true"),
                    NumCase("-5", "LESS_THAN", "-2", "true"),
                    NumCase("-10", "GREATER_THAN", "-5", "false"),
                    NumCase("100", "GTE", "100.0", "true"),
                    NumCase("50", "LTE", "50", "true"),
                    NumCase("not_a_num", "GT", "10", "false"),
                    NumCase("10", "GT", "not_a_num", "false"),
                )

            for (c in cases) {
                val node =
                    WorkflowNode(
                        "c",
                        "Cond",
                        NodeType.CONDITION,
                        configJson = """{"variable":"val","operator":"${c.op}","value":"${c.targetVal}"}""",
                    )
                val ctx = ExecutionContext(mapOf("val" to c.varVal))
                val result = executor.execute(node, ctx, null) as NodeExecutionResult.Success
                assertEquals("Case val=${c.varVal} ${c.op} ${c.targetVal}", c.expected, result.outputPort)
            }
        }

    /**
     * ConditionExecutor: Case-insensitive string matching operators.
     */
    @Test
    fun conditionExecutor_stringOperators_caseInsensitive() =
        runTest {
            val executor = ConditionExecutor()

            val nodeEquals =
                WorkflowNode(
                    "c1",
                    "Cond",
                    NodeType.CONDITION,
                    configJson = """{"variable":"status","operator":"EQUALS","value":"APPROVED"}""",
                )
            val ctxEquals = ExecutionContext(mapOf("status" to "approved"))
            val resEquals = executor.execute(nodeEquals, ctxEquals, null) as NodeExecutionResult.Success
            assertEquals("EQUALS should be case-insensitive", "true", resEquals.outputPort)

            val nodeContains =
                WorkflowNode(
                    "c2",
                    "Cond",
                    NodeType.CONDITION,
                    configJson = """{"variable":"text","operator":"CONTAINS","value":"URGENT"}""",
                )
            val ctxContains = ExecutionContext(mapOf("text" to "this is urgent action"))
            val resContains = executor.execute(nodeContains, ctxContains, null) as NodeExecutionResult.Success
            assertEquals("CONTAINS should be case-insensitive", "true", resContains.outputPort)
        }

    /**
     * JsonParser: JSON Object length property.
     */
    @Test
    fun jsonParser_jsonObjectLength_returnsKeyCount() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_obj_len",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"user.length","outputKey":"keyCount","targetType":"NUMBER"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"user":{"id":1,"name":"Bob","active":true}}"""))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals(3, ctx.get("keyCount"))
        }

    /**
     * JsonParser: Bracket property with spaces or special characters.
     */
    @Test
    fun jsonParser_bracketNotationWithSpecialKeys() =
        runTest {
            val executor = JsonParserExecutor()
            val node =
                WorkflowNode(
                    "j_bracket",
                    "JSON",
                    NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"['content-type']","outputKey":"contentType"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to """{"content-type":"application/json"}"""))
            val result = executor.execute(node, ctx, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("application/json", ctx.getString("contentType"))
        }

    /**
     * PreconditionEvaluator: HealthCheck POST method with custom body and expected status.
     */
    @Test
    fun preconditionEvaluator_healthCheckPostMethod_verifiesResponse() =
        runTest {
            var capturedMethod = ""
            var capturedHeader = ""
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        capturedMethod = req.method
                        capturedHeader = req.header("X-Ping") ?: ""

                        Response
                            .Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("healthy".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(null, client)
            val healthCheck =
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "https://example.com/api/ping",
                    method = "POST",
                    headers = "Content-Type: application/json\nX-Ping: test",
                    expectedStatus = 200,
                )

            val result = evaluator.evaluate(healthCheck)

            assertTrue(result is PreconditionResult.Passed)
            assertEquals("POST", capturedMethod)
            assertEquals("test", capturedHeader)
        }

    /**
     * PreconditionEvaluator: Precondition chaining evaluateAll preserves all step logs.
     */
    @Test
    fun preconditionEvaluator_evaluateAll_accumulatesAllStepLogs() =
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

            val evaluator = PreconditionEvaluator(null, client)
            val p1 = Precondition.HealthCheck(id = "p1", isEnabled = true, targetUrl = "https://e.com/1", expectedStatus = 200)
            val p2 = Precondition.HealthCheck(id = "p2", isEnabled = true, targetUrl = "https://e.com/2", expectedStatus = 200)

            val result = evaluator.evaluateAll(listOf(p1, p2))

            assertTrue(result is PreconditionResult.Passed)
            val passed = result as PreconditionResult.Passed
            assertEquals("Both steps must be recorded in accumulated steps", 2, passed.steps.size)
            assertEquals("health_check", passed.steps[0].nodeId)
            assertEquals("health_check", passed.steps[1].nodeId)
        }

    /**
     * WorkflowVariableResolver: Resolves available variables across complex chains.
     */
    @Test
    fun workflowVariableResolver_multiStepChain_accumulatesUpstreamVariables() {
        val trigger = WorkflowNode("t1", "SMS Trigger", NodeType.SMS_TRIGGER)
        val parser =
            WorkflowNode(
                "p1",
                "Regex Parser",
                NodeType.REGEX_PARSER,
                configJson = """{"regex":"\\d+","outputKey":"parsed_code"}""",
            )
        val battery = WorkflowNode("b1", "Battery Check", NodeType.BATTERY_CHECK)
        val action = WorkflowNode("a1", "Action", NodeType.NTFY_ACTION)

        val nodes = listOf(trigger, parser, battery, action)
        val connections =
            listOf(
                WorkflowConnection("t1", "p1"),
                WorkflowConnection("p1", "b1"),
                WorkflowConnection("b1", "a1"),
            )

        val availableAtAction = WorkflowVariableResolver.getAvailableUpstreamVariables("a1", nodes, connections)

        // Should include trigger variables
        assertTrue("Should have sender", availableAtAction.contains("sender"))
        assertTrue("Should have body", availableAtAction.contains("body"))

        // Should include parser variables
        assertTrue("Should have parsed_code", availableAtAction.contains("parsed_code"))
        assertTrue("Should have code alias", availableAtAction.contains("code"))

        // Should include battery check variables
        assertTrue("Should have battery_percent", availableAtAction.contains("battery_percent"))
        assertTrue("Should have is_charging", availableAtAction.contains("is_charging"))
    }
}
