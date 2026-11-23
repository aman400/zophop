package dev.zophop.domain.engine

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionFailurePolicy
import dev.zophop.domain.model.RetryStrategy
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.calculateRetryDelaySeconds
import dev.zophop.plugin.api.ExecutionContext
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

    @Test
    fun testPreconditionQueuePolicyReturnsFailedWithShouldQueueTrue() =
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
                            .message("Service Unavailable")
                            .body("down".toResponseBody())
                            .build()
                    }.build()

            val evaluator =
                PreconditionEvaluator(
                    context = null,
                    okHttpClient = failingClient,
                    delayDuration = { /* skip real delay in test */ },
                )
            val healthCheck =
                Precondition.HealthCheck(
                    id = "queue_health_check",
                    isEnabled = true,
                    targetUrl = "https://example.com/health",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.QUEUE,
                    queueTimeoutMinutes = 1,
                    retryIntervalSeconds = 30,
                )

            var progressSteps = emptyList<NodeExecutionStep>()
            val result =
                evaluator.evaluate(healthCheck) { steps ->
                    progressSteps = steps
                }

            assertTrue(result is PreconditionResult.Failed)
            val failed = result as PreconditionResult.Failed
            assertTrue("shouldQueue must be true", failed.shouldQueue)
            assertTrue(progressSteps.any { it.nodeName.contains("Queued") || it.nodeName.contains("Timeout") })
        }

    @Test
    fun testPreconditionQueuePolicyInWorkflowEngine() =
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
                            .message("Unavailable")
                            .body("down".toResponseBody())
                            .build()
                    }.build()

            val evaluator =
                PreconditionEvaluator(
                    context = null,
                    okHttpClient = failingClient,
                    delayDuration = { /* skip real delay in test */ },
                )
            val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(failingClient))

            val trigger = WorkflowNode("trig", "Trigger", NodeType.MANUAL_TRIGGER)
            val queuedNode =
                WorkflowNode(
                    id = "queued_action",
                    name = "Queued Action",
                    type = NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"test"}""",
                    preconditions =
                        listOf(
                            Precondition.HealthCheck(
                                isEnabled = true,
                                targetUrl = "https://health.example.com/check",
                                expectedStatus = 200,
                                onFailureAction = "QUEUE",
                                queueTimeoutMinutes = 1,
                                retryIntervalSeconds = 30,
                            ),
                        ),
                )

            val workflow =
                Workflow(
                    id = "wf_queue_test",
                    name = "Queue Test Workflow",
                    isEnabled = true,
                    nodes = listOf(trigger, queuedNode),
                    connections = listOf(WorkflowConnection("trig", "queued_action")),
                )

            val ctx = ExecutionContext(mapOf("body" to "payload"))
            val result = engine.executeWorkflow(workflow, ctx, null)

            assertTrue(result is WorkflowExecutionResult.PreconditionFailed)
            val pf = result as WorkflowExecutionResult.PreconditionFailed
            assertEquals("queued_action", pf.nodeId)
            assertTrue("pf.shouldQueue must be true", pf.shouldQueue)
        }

    @Test
    fun testCalculateRetryDelayFixedStrategy() {
        val base = 10
        assertEquals(10, calculateRetryDelaySeconds(RetryStrategy.FIXED, base, attempt = 1))
        assertEquals(10, calculateRetryDelaySeconds(RetryStrategy.FIXED, base, attempt = 2))
        assertEquals(10, calculateRetryDelaySeconds(RetryStrategy.FIXED, base, attempt = 5))
        assertEquals(10, calculateRetryDelaySeconds("unknown_strategy", base, attempt = 3))
    }

    @Test
    fun testCalculateRetryDelayExponentialStrategy() {
        val base = 5
        assertEquals(5, calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, base, attempt = 1))
        assertEquals(10, calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, base, attempt = 2))
        assertEquals(20, calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, base, attempt = 3))
        assertEquals(40, calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, base, attempt = 4))
        assertEquals(80, calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, base, attempt = 5))

        // Clamped to max cap
        assertEquals(3600, calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, 500, attempt = 10, maxCapSeconds = 3600))
    }

    @Test
    fun testCalculateRetryDelayLinearStrategy() {
        val base = 10
        assertEquals(10, calculateRetryDelaySeconds(RetryStrategy.LINEAR, base, attempt = 1))
        assertEquals(20, calculateRetryDelaySeconds(RetryStrategy.LINEAR, base, attempt = 2))
        assertEquals(30, calculateRetryDelaySeconds(RetryStrategy.LINEAR, base, attempt = 3))
        assertEquals(40, calculateRetryDelaySeconds(RetryStrategy.LINEAR, base, attempt = 4))
        assertEquals(100, calculateRetryDelaySeconds(RetryStrategy.LINEAR, base, attempt = 10))

        // Clamped to max cap
        assertEquals(25, calculateRetryDelaySeconds(RetryStrategy.LINEAR, base, attempt = 5, maxCapSeconds = 25))
    }

    @Test
    fun testCalculateRetryDelayRandomizedStrategy() {
        val base = 10
        val fixedMockRand = { min: Int, max: Int -> (min + max) / 2 }
        val delay1 = calculateRetryDelaySeconds(RetryStrategy.RANDOMIZED, base, attempt = 1, randomGenerator = fixedMockRand)
        assertEquals((1 + 10) / 2, delay1)

        val delay2 = calculateRetryDelaySeconds(RetryStrategy.RANDOMIZED, base, attempt = 2, randomGenerator = fixedMockRand)
        assertEquals((1 + 20) / 2, delay2)

        // With default random generator, assert bounds
        for (attempt in 1..4) {
            val ceiling = base * (1 shl (attempt - 1))
            val delay = calculateRetryDelaySeconds(RetryStrategy.RANDOMIZED, base, attempt)
            assertTrue("Delay $delay must be >= 1", delay >= 1)
            assertTrue("Delay $delay must be <= ceiling $ceiling", delay <= ceiling)
        }
    }

    @Test
    fun testCalculateRetryDelayRobustnessAgainstOverflowAndInvalidCaps() {
        // Zero or negative maxCapSeconds should not throw IllegalArgumentException and return at least 1
        assertEquals(1, calculateRetryDelaySeconds(RetryStrategy.FIXED, baseIntervalSeconds = 10, attempt = 1, maxCapSeconds = 0))
        assertEquals(1, calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, baseIntervalSeconds = 10, attempt = 1, maxCapSeconds = -5))

        // Large base with exponential shift should not overflow to negative number
        val largeBase = 5_000_000
        val delay = calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, baseIntervalSeconds = largeBase, attempt = 10, maxCapSeconds = 3600)
        assertEquals(3600, delay)

        // Negative or zero attempt should safely clamp to shift 0
        assertEquals(10, calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, baseIntervalSeconds = 10, attempt = -5))
        assertEquals(10, calculateRetryDelaySeconds(RetryStrategy.EXPONENTIAL, baseIntervalSeconds = 10, attempt = 0))
    }

    @Test
    fun testPreconditionRetryWithExponentialBackoffRecovers() =
        runTest {
            var attempts = 0
            val recordedTicks = mutableListOf<String>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        attempts++
                        if (attempts < 3) {
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(503)
                                .message("Unavailable")
                                .body("error".toResponseBody())
                                .build()
                        } else {
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body("success".toResponseBody())
                                .build()
                        }
                    }.build()

            val evaluator =
                PreconditionEvaluator(
                    context = null,
                    okHttpClient = client,
                    delayDuration = { /* skip delay in test */ },
                )

            val healthCheck =
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "https://example.com/health",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.RETRY,
                    maxRetries = 3,
                    retryIntervalSeconds = 2,
                    retryStrategy = RetryStrategy.EXPONENTIAL,
                )

            val result =
                evaluator.evaluate(healthCheck) { steps ->
                    steps.lastOrNull()?.let { recordedTicks.add(it.logMessage) }
                }

            assertTrue(result is PreconditionResult.Passed)
            assertEquals(3, attempts)
            assertTrue(recordedTicks.any { it.contains("EXPONENTIAL backoff") })
        }

    @Test
    fun testPreconditionRetryWithRandomizedBackoffRecovers() =
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
                                .code(500)
                                .message("Server Error")
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

            val evaluator =
                PreconditionEvaluator(
                    context = null,
                    okHttpClient = client,
                    delayDuration = { /* skip delay in test */ },
                    randomGenerator = { min, _ -> min },
                )

            val healthCheck =
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "https://example.com/health",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.RETRY,
                    maxRetries = 2,
                    retryIntervalSeconds = 3,
                    retryStrategy = RetryStrategy.RANDOMIZED,
                )

            val result = evaluator.evaluate(healthCheck)
            assertTrue(result is PreconditionResult.Passed)
            assertEquals(2, attempts)
        }

    @Test
    fun testQueueModeWhenPreconditionPassesDoesNotQueue() =
        runTest {
            val passingClient =
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

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = passingClient)
            val healthCheck =
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "https://example.com/health",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.QUEUE,
                    queueTimeoutMinutes = 15,
                )

            val result = evaluator.evaluate(healthCheck)
            assertTrue(result is PreconditionResult.Passed)
            val passed = result as PreconditionResult.Passed
            assertTrue(passed.steps.none { it.nodeName.contains("Queued") })
        }

    @Test
    fun testQueueModeChainedPreconditionHalt() =
        runTest {
            val trigger = WorkflowNode("trig", "Trigger", NodeType.MANUAL_TRIGGER, configJson = """{"val":"fail"}""")
            val cond = WorkflowNode("cond", "Check", NodeType.CONDITION, configJson = """{"variable":"val","operator":"EQUALS","value":"pass"}""")
            val chain =
                Precondition.ChainCheck(
                    id = "queue_chain_test",
                    isEnabled = true,
                    nodes = listOf(trigger, cond),
                    connections = listOf(WorkflowConnection("trig", "cond")),
                    onFailureAction = PreconditionFailurePolicy.QUEUE,
                    queueTimeoutMinutes = 1,
                    retryIntervalSeconds = 30,
                )

            val evaluator = PreconditionEvaluator(context = null, delayDuration = {})
            var recordedSteps = emptyList<NodeExecutionStep>()
            val result =
                evaluator.evaluate(chain) { steps ->
                    recordedSteps = steps
                }

            assertTrue(result is PreconditionResult.Failed)
            val failed = result as PreconditionResult.Failed
            assertTrue("shouldQueue must be true for failing chain in QUEUE mode", failed.shouldQueue)
            assertTrue(recordedSteps.any { it.nodeName.contains("Queued") || it.nodeName.contains("Timeout") })
        }

    @Test
    fun testQueueModeRecoversWhenConditionSatisfiesBeforeTimeoutWithLinearBackoff() =
        runTest {
            var callCount = 0
            val recordedSteps = mutableListOf<String>()
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        callCount++
                        val statusCode = if (callCount >= 3) 200 else 503
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(statusCode)
                            .message(if (statusCode == 200) "OK" else "Service Unavailable")
                            .body("{}".toResponseBody())
                            .build()
                    }.build()

            val evaluator =
                PreconditionEvaluator(
                    context = null,
                    okHttpClient = okHttpClient,
                    delayDuration = { /* fast test execution */ },
                )

            val healthCheck =
                Precondition.HealthCheck(
                    id = "prec_queue_linear_recovery",
                    isEnabled = true,
                    targetUrl = "https://backend.example.com/health",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.QUEUE,
                    queueTimeoutMinutes = 5,
                    retryIntervalSeconds = 10,
                    retryStrategy = RetryStrategy.LINEAR,
                )

            val result =
                evaluator.evaluate(healthCheck) { steps ->
                    steps.lastOrNull()?.let { recordedSteps.add(it.nodeName) }
                }

            assertTrue("Result must be Passed after recovering in queue", result is PreconditionResult.Passed)
            val passed = result as PreconditionResult.Passed
            assertEquals(3, callCount)
            assertTrue(passed.steps.any { it.nodeName.contains("Precondition Satisfied in Queue") })
        }

    @Test
    fun testQueueModeRecoversWhenConditionSatisfiesBeforeTimeoutWithExponentialBackoff() =
        runTest {
            var callCount = 0
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        callCount++
                        val statusCode = if (callCount >= 2) 200 else 500
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(statusCode)
                            .message(if (statusCode == 200) "OK" else "Internal Server Error")
                            .body("{}".toResponseBody())
                            .build()
                    }.build()

            val evaluator =
                PreconditionEvaluator(
                    context = null,
                    okHttpClient = okHttpClient,
                    delayDuration = { /* fast test execution */ },
                )

            val healthCheck =
                Precondition.HealthCheck(
                    id = "prec_queue_exp_recovery",
                    isEnabled = true,
                    targetUrl = "https://backend.example.com/health",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.QUEUE,
                    queueTimeoutMinutes = 10,
                    retryIntervalSeconds = 5,
                    retryStrategy = RetryStrategy.EXPONENTIAL,
                )

            val result = evaluator.evaluate(healthCheck)

            assertTrue("Result must be Passed after recovering in queue", result is PreconditionResult.Passed)
            val passed = result as PreconditionResult.Passed
            assertEquals(2, callCount)
            assertTrue(passed.steps.any { it.nodeName.contains("Precondition Satisfied in Queue") })
        }

    @Test
    fun testQueueModeHonorsQueueTimeoutAndFailsWhenConditionNeverSatisfies() =
        runTest {
            var callCount = 0
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        callCount++
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(500)
                            .message("Down")
                            .body("{}".toResponseBody())
                            .build()
                    }.build()

            val evaluator =
                PreconditionEvaluator(
                    context = null,
                    okHttpClient = okHttpClient,
                    delayDuration = { /* fast test execution */ },
                )

            val healthCheck =
                Precondition.HealthCheck(
                    id = "prec_queue_timeout_test",
                    isEnabled = true,
                    targetUrl = "https://backend.example.com/health",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.QUEUE,
                    queueTimeoutMinutes = 1, // 60 seconds timeout
                    retryIntervalSeconds = 25,
                    retryStrategy = RetryStrategy.FIXED,
                )

            val result = evaluator.evaluate(healthCheck)

            assertTrue("Result must be Failed when queue timeout reached", result is PreconditionResult.Failed)
            val failed = result as PreconditionResult.Failed
            assertTrue("shouldQueue must remain true on queue timeout", failed.shouldQueue)
            assertTrue("Reason must mention queue timeout", failed.reason.contains("queue timeout"))
            assertTrue(failed.steps.any { it.nodeName.contains("Queue Timeout Exceeded") })
        }
}
