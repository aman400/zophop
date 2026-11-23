package dev.zophop.domain.engine

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchingAndComplexWorkflowIntegrationTest {
    @Test
    fun testConditionBranchingTruePathExecutesTargetNode() =
        runTest {
            val requestedUrls = mutableListOf<String>()

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requestedUrls.add(chain.request().url.toString())
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = okHttpClient)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(okHttpClient),
                )

            val triggerId = "node_trigger"
            val conditionId = "node_condition"
            val actionId = "node_action_urgent"

            val triggerNode = WorkflowNode(id = triggerId, name = "SMS Trigger", type = NodeType.SMS_TRIGGER)
            val conditionNode =
                WorkflowNode(
                    id = conditionId,
                    name = "Check Priority",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"body","operator":"CONTAINS","value":"URGENT"}""",
                )
            val urgentActionNode =
                WorkflowNode(
                    id = actionId,
                    name = "Urgent Webhook",
                    type = NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"urgent-alerts"}""",
                )

            val workflow =
                Workflow(
                    id = "w_branching",
                    name = "Branching Priority Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, conditionNode, urgentActionNode),
                    connections =
                        listOf(
                            WorkflowConnection(fromNodeId = triggerId, toNodeId = conditionId),
                            // After BUG-01 fix: ConditionExecutor emits "true" on match, "false" on no-match
                            WorkflowConnection(fromNodeId = conditionId, toNodeId = actionId, outputPort = "true"),
                        ),
                )

            // Test URGENT payload -> Should trigger Urgent Webhook (topic: urgent-alerts)
            val urgentCtx = ExecutionContext(mapOf("sender" to "Admin", "body" to "URGENT: Server is down!"))
            val resultUrgent = engine.executeWorkflow(workflow, urgentCtx, appContext = null)

            assertTrue(resultUrgent is WorkflowExecutionResult.Success)
            assertEquals(1, requestedUrls.size)
            assertTrue(requestedUrls[0].contains("urgent-alerts"))

            // Reset tracking list
            requestedUrls.clear()

            // Test NORMAL payload -> Condition evaluates false; no "false" branch connected, so
            // workflow still completes as Success (condition false does not halt the workflow)
            val normalCtx = ExecutionContext(mapOf("sender" to "Admin", "body" to "Routine report: All good."))
            val resultNormal = engine.executeWorkflow(workflow, normalCtx, appContext = null)

            assertTrue(resultNormal is WorkflowExecutionResult.Success)
            assertEquals(0, requestedUrls.size)
        }

    @Test
    fun testEngineLoopSafetyHaltsCycleExecution() =
        runTest {
            val okHttpClient = OkHttpClient.Builder().build()
            val evaluator = PreconditionEvaluator(context = null, okHttpClient = okHttpClient)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(okHttpClient),
                )

            val nodeAId = "node_a"
            val nodeBId = "node_b"

            val nodeA =
                WorkflowNode(
                    id = nodeAId,
                    name = "Formatter A",
                    type = NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"Formatted A"}""",
                )
            val nodeB =
                WorkflowNode(
                    id = nodeBId,
                    name = "Formatter B",
                    type = NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"Formatted B"}""",
                )

            // Circular graph A -> B -> A
            val workflow =
                Workflow(
                    id = "w_cycle",
                    name = "Circular Workflow",
                    isEnabled = true,
                    nodes = listOf(nodeA, nodeB),
                    connections =
                        listOf(
                            WorkflowConnection(fromNodeId = nodeAId, toNodeId = nodeBId),
                            WorkflowConnection(fromNodeId = nodeBId, toNodeId = nodeAId),
                        ),
                )

            val context = ExecutionContext(mapOf("body" to "Test"))

            var stepCount = 0
            val result =
                engine.executeWorkflowWithProgress(
                    workflow = workflow,
                    context = context,
                    appContext = null,
                    onStepUpdate = { steps -> stepCount = steps.size },
                )

            // Engine must terminate cleanly when hitting max steps limit (100 steps)
            assertTrue(result is WorkflowExecutionResult.Success || result is WorkflowExecutionResult.Failed)
            assertTrue(stepCount <= 100)
        }

    @Test
    fun testChainedVariablesExtractionAndInterpolation() =
        runTest {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"status":"VERIFIED","token":"xyz_token_999"}""".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = okHttpClient)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(okHttpClient),
                )

            val triggerNode = WorkflowNode("n1", "Trigger", NodeType.SMS_TRIGGER)
            val regexNode =
                WorkflowNode("n2", "OTP Parser", NodeType.REGEX_PARSER, configJson = """{"regex":"\\b\\d{6}\\b","outputKey":"otp"}""")
            val httpNode =
                WorkflowNode(
                    "n3",
                    "Verify API",
                    NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://auth.example.com/verify\",\"method\":\"POST\",\"body\":\"{\\\"code\\\":\\\"\${'$'}{otp}\\\"}\"}",
                )
            val jsonNode =
                WorkflowNode("n4", "Token Extractor", NodeType.JSON_PARSER, configJson = """{"jsonKey":"token","outputKey":"auth_token"}""")

            val workflow =
                Workflow(
                    id = "w_chain",
                    name = "Full Chain Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, regexNode, httpNode, jsonNode),
                    connections =
                        listOf(
                            WorkflowConnection("n1", "n2"),
                            WorkflowConnection("n2", "n3"),
                            WorkflowConnection("n3", "n4"),
                        ),
                )

            val context = ExecutionContext(mapOf("sender" to "2FA", "body" to "Your login code is 882910"))
            val result = engine.executeWorkflow(workflow, context, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals("882910", context.getString("otp"))
            assertEquals("xyz_token_999", context.getString("auth_token"))
        }
}
