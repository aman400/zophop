package dev.zophop.domain.engine

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PreconditionEvaluatorExtendedTest {
    @Test
    fun testChainedPreconditionPipelineSuccess() =
        runTest {
            val triggerId = UUID.randomUUID().toString()
            val parserId = UUID.randomUUID().toString()
            val condId = UUID.randomUUID().toString()

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "Precondition Test Data",
                    type = NodeType.MANUAL_TRIGGER,
                    configJson = """{"testBody":"{\"status\":\"OK\",\"code\":200}"}""",
                )

            val parserNode =
                WorkflowNode(
                    id = parserId,
                    name = "JSON Parser",
                    type = NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"status","outputKey":"parsedStatus"}""",
                )

            val condNode =
                WorkflowNode(
                    id = condId,
                    name = "Condition Check",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"parsedStatus","operator":"EQUALS","value":"OK"}""",
                )

            val chainCheck =
                Precondition.ChainCheck(
                    isEnabled = true,
                    nodes = listOf(triggerNode, parserNode, condNode),
                    connections =
                        listOf(
                            WorkflowConnection(fromNodeId = triggerId, toNodeId = parserId),
                            WorkflowConnection(fromNodeId = parserId, toNodeId = condId),
                        ),
                )

            val evaluator = PreconditionEvaluator(context = null)
            var receivedSteps = emptyList<NodeExecutionStep>()

            val result =
                evaluator.evaluate(chainCheck) { steps ->
                    receivedSteps = steps
                }

            assertTrue(result is PreconditionResult.Passed)
            assertTrue(receivedSteps.isNotEmpty())
            assertEquals(NodeStepState.SUCCESS, receivedSteps.last().state)
        }

    @Test
    fun testPreconditionRetryPolicyRecovery() =
        runTest {
            var attempts = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        attempts++
                        if (attempts == 1) {
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(503)
                                .message("Service Unavailable")
                                .body("error".toResponseBody())
                                .build()
                        } else {
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body("ok".toResponseBody())
                                .build()
                        }
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

            assertTrue(result is PreconditionResult.Passed)
            assertEquals(2, attempts)
        }

    @Test
    fun testPreconditionRetryExhaustionFails() =
        runTest {
            var attempts = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        attempts++
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(500)
                            .message("Server Error")
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
            val failed = result as PreconditionResult.Failed
            assertTrue(failed.reason.contains("Failed after 2 retries"))
            assertEquals(3, attempts) // 1 initial + 2 retries
        }

    @Test
    fun testBatteryCheckDisabledPassesImmediately() =
        runTest {
            val evaluator = PreconditionEvaluator(context = null)
            val batteryCheck =
                Precondition.BatteryCheck(
                    isEnabled = false,
                    minBatteryPercent = 80,
                    requireCharging = true,
                )
            val result = evaluator.evaluate(batteryCheck)
            assertTrue(result is PreconditionResult.Passed)
        }

    @Test
    fun testBatteryCheckWithNullContextPassesFailSafe() =
        runTest {
            val evaluator = PreconditionEvaluator(context = null)
            val batteryCheck =
                Precondition.BatteryCheck(
                    isEnabled = true,
                    minBatteryPercent = 50,
                    requireCharging = false,
                )
            var receivedSteps = emptyList<NodeExecutionStep>()
            val result =
                evaluator.evaluate(batteryCheck) { steps ->
                    receivedSteps = steps
                }
            assertTrue(result is PreconditionResult.Passed)
            assertTrue(receivedSteps.isNotEmpty())
            assertEquals("battery_check", receivedSteps.first().nodeId)
            assertEquals(NodeStepState.SUCCESS, receivedSteps.first().state)
        }

    @Test
    fun testCircularPreconditionChainLoopDetected() =
        runTest {
            val chainId = "chain_circular_1"
            val triggerId = UUID.randomUUID().toString()
            val actionId = UUID.randomUUID().toString()

            val selfReferencingChain =
                Precondition.ChainCheck(
                    id = chainId,
                    isEnabled = true,
                    nodes = emptyList(),
                    connections = emptyList(),
                )

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "Trigger",
                    type = NodeType.MANUAL_TRIGGER,
                )

            // Action node has a precondition pointing back to the chain itself!
            val actionNode =
                WorkflowNode(
                    id = actionId,
                    name = "Nested Action",
                    type = NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://example.com"}""",
                    preconditions = listOf(selfReferencingChain),
                )

            val outerChain =
                Precondition.ChainCheck(
                    id = chainId,
                    isEnabled = true,
                    nodes = listOf(triggerNode, actionNode),
                    connections = listOf(WorkflowConnection(fromNodeId = triggerId, toNodeId = actionId)),
                )

            val evaluator = PreconditionEvaluator(context = null)
            val result = evaluator.evaluate(outerChain)

            assertTrue("Expected failure for circular loop", result is PreconditionResult.Failed)
            val failed = result as PreconditionResult.Failed
            assertTrue(
                "Expected 'Circular precondition loop detected' but got: ${failed.reason}",
                failed.reason.contains("Circular precondition loop detected"),
            )
        }

    @Test
    fun testConditionalNodesInChainHavePreconditionsSanitized() =
        runTest {
            val triggerId = UUID.randomUUID().toString()
            val condId = UUID.randomUUID().toString()

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "Trigger",
                    type = NodeType.MANUAL_TRIGGER,
                    configJson = """{"testValue":"HELLO"}""",
                )

            // Conditional node carrying a precondition (which should be stripped to avoid circular dependencies)
            val badPrecondition =
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "", // Empty URL normally fails if evaluated
                )

            val condNode =
                WorkflowNode(
                    id = condId,
                    name = "Condition Node",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"value","operator":"EQUALS","value":"HELLO"}""",
                    preconditions = listOf(badPrecondition),
                )

            val chain =
                Precondition.ChainCheck(
                    id = "chain_sanitized",
                    isEnabled = true,
                    nodes = listOf(triggerNode, condNode),
                    connections = listOf(WorkflowConnection(fromNodeId = triggerId, toNodeId = condId)),
                )

            val evaluator = PreconditionEvaluator(context = null)
            val result = evaluator.evaluate(chain)

            // The chain must pass because the bad precondition on the condition node was sanitized/stripped!
            assertTrue("Expected chain to pass because conditional node preconditions were sanitized", result is PreconditionResult.Passed)
        }

    @Test
    fun testSingleNodeChainedPreconditionExecution() =
        runTest {
            val singleNode =
                WorkflowNode(
                    id = "single_internet",
                    name = "Internet Check",
                    type = NodeType.INTERNET_CHECK,
                    configJson = """{"requireConnected":"false"}""",
                )

            val chain =
                Precondition.ChainCheck(
                    id = "single_node_chain",
                    isEnabled = true,
                    nodes = listOf(singleNode),
                    connections = emptyList(),
                )

            val evaluator = PreconditionEvaluator(context = null)
            var receivedSteps = emptyList<NodeExecutionStep>()

            val result =
                evaluator.evaluate(chain) { steps ->
                    receivedSteps = steps
                }

            assertTrue("Expected single-node chain check to pass", result is PreconditionResult.Passed)
            assertEquals(1, receivedSteps.size)
            assertEquals(NodeStepState.SUCCESS, receivedSteps[0].state)
        }

    @Test
    fun testChainedPreconditionConditionFailedHalt() =
        runTest {
            val triggerId = UUID.randomUUID().toString()
            val condId = UUID.randomUUID().toString()

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "Trigger Data",
                    type = NodeType.MANUAL_TRIGGER,
                    configJson = """{"testStatus":"INACTIVE"}""",
                )

            val condNode =
                WorkflowNode(
                    id = condId,
                    name = "Active Status Guard",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"status","operator":"EQUALS","value":"ACTIVE"}""",
                )

            val chain =
                Precondition.ChainCheck(
                    id = "condition_check_fail_chain",
                    isEnabled = true,
                    nodes = listOf(triggerNode, condNode),
                    connections = listOf(WorkflowConnection(fromNodeId = triggerId, toNodeId = condId)),
                )

            val evaluator = PreconditionEvaluator(context = null)
            var receivedSteps = emptyList<NodeExecutionStep>()

            val result =
                evaluator.evaluate(chain) { steps ->
                    receivedSteps = steps
                }

            assertTrue("Expected condition evaluation to fail precondition check", result is PreconditionResult.Failed)
            val failed = result as PreconditionResult.Failed
            assertTrue(failed.reason.contains("Condition") || failed.reason.contains("FALSE"))
            val failedStep = receivedSteps.find { it.nodeId == condId }
            assertEquals(NodeStepState.FAILED, failedStep?.state)
        }
}
