package dev.zophop.domain.engine

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionFailurePolicy
import dev.zophop.domain.model.RetryStrategy
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Rigorous test suite verifying that Queue Mode (onFailureAction = "QUEUE")
 * works properly across PreconditionEvaluator, retry backoff calculation,
 * countdown progress streaming, recovery, timeout, and WorkflowEngine execution.
 */
class QueueModeVerificationTest {

    @Test
    fun testQueueMode_recoversOnSecondAttempt_workflowResumesAndCompletes() = runTest {
        val preconditionAttempts = AtomicInteger(0)
        val actionDispatches = AtomicInteger(0)

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("health")) {
                    val count = preconditionAttempts.incrementAndGet()
                    val code = if (count >= 2) 200 else 503
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code == 200) "OK" else "Service Unavailable")
                        .body("{}".toResponseBody())
                        .build()
                } else {
                    actionDispatches.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"status":"dispatched"}""".toResponseBody())
                        .build()
                }
            }
            .build()

        val evaluator = PreconditionEvaluator(
            context = null,
            okHttpClient = mockHttpClient,
            delayDuration = { /* fast test execution */ },
        )
        val executorFactory = NodeExecutorFactory(okHttpClient = mockHttpClient)
        val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = executorFactory)

        val trigger = WorkflowNode(
            id = "trig_1",
            name = "Manual Trigger",
            type = NodeType.MANUAL_TRIGGER,
        )
        val actionNode = WorkflowNode(
            id = "action_1",
            name = "Webhook Dispatch",
            type = NodeType.HTTP_ACTION,
            configJson = """{"url":"https://api.example.com/send","method":"POST","body":"hello"}""",
            preconditions = listOf(
                Precondition.HealthCheck(
                    id = "queue_health_check",
                    isEnabled = true,
                    targetUrl = "https://health.example.com/status",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.QUEUE,
                    queueTimeoutMinutes = 5,
                    retryIntervalSeconds = 10,
                    retryStrategy = RetryStrategy.FIXED,
                ),
            ),
        )

        val workflow = Workflow(
            id = "wf_queue_recovery",
            name = "Queue Recovery Workflow",
            isEnabled = true,
            nodes = listOf(trigger, actionNode),
            connections = listOf(WorkflowConnection("trig_1", "action_1")),
        )

        val context = ExecutionContext()
        val recordedStepHistory = mutableListOf<List<NodeExecutionStep>>()

        val result = engine.executeWorkflowWithProgress(
            workflow = workflow,
            context = context,
            appContext = null,
            startNodeId = null,
            onStepUpdate = { recordedStepHistory.add(it) },
        )

        // 1. Verify workflow succeeded after queue recovery
        assertTrue("Workflow must succeed after precondition recovers in queue", result is WorkflowExecutionResult.Success)
        assertEquals(2, preconditionAttempts.get())
        assertEquals(1, actionDispatches.get())

        // 2. Verify steps reflect queue recovery
        val success = result as WorkflowExecutionResult.Success
        val actionStep = success.steps.firstOrNull { it.nodeId == "action_1" }
        assertNotNull("Action node step must exist", actionStep)
        assertEquals(NodeStepState.SUCCESS, actionStep?.state)

        // 3. Verify logs indicate queue recovery
        assertTrue(
            "Logs must contain completion info",
            success.logs.any { it.contains("Completed workflow", ignoreCase = true) },
        )
    }

    @Test
    fun testQueueMode_recoversOnThirdAttemptWithExponentialBackoff() = runTest {
        val attempts = AtomicInteger(0)
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val a = attempts.incrementAndGet()
                val code = if (a >= 3) 200 else 500
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        val evaluator = PreconditionEvaluator(
            context = null,
            okHttpClient = mockHttpClient,
            delayDuration = { /* fast test execution */ },
        )

        val healthCheck = Precondition.HealthCheck(
            id = "exp_queue_check",
            isEnabled = true,
            targetUrl = "https://server.example.com/health",
            expectedStatus = 200,
            onFailureAction = PreconditionFailurePolicy.QUEUE,
            queueTimeoutMinutes = 10,
            retryIntervalSeconds = 5,
            retryStrategy = RetryStrategy.EXPONENTIAL,
        )

        val progressUpdates = mutableListOf<List<NodeExecutionStep>>()
        val result = evaluator.evaluate(healthCheck) { steps ->
            progressUpdates.add(steps)
        }

        assertTrue("Result must be Passed after 3 attempts in queue", result is PreconditionResult.Passed)
        assertEquals(3, attempts.get())

        val passed = result as PreconditionResult.Passed
        assertTrue(
            "Steps must contain 'Precondition Satisfied in Queue'",
            passed.steps.any { it.nodeName.contains("Precondition Satisfied in Queue") },
        )
    }

    @Test
    fun testQueueMode_timesOutWhenConditionNeverSatisfied() = runTest {
        val callCount = AtomicInteger(0)
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("error".toResponseBody())
                    .build()
            }
            .build()

        val evaluator = PreconditionEvaluator(
            context = null,
            okHttpClient = failingClient,
            delayDuration = { /* skip real delay */ },
        )
        val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(failingClient))

        val trigger = WorkflowNode("t1", "Trigger", NodeType.MANUAL_TRIGGER)
        val failingNode = WorkflowNode(
            id = "queue_node",
            name = "Guarded Node",
            type = NodeType.HTTP_ACTION,
            configJson = """{"url":"https://api.example.com","method":"GET"}""",
            preconditions = listOf(
                Precondition.HealthCheck(
                    id = "always_fail_health",
                    isEnabled = true,
                    targetUrl = "https://failing.example.com/check",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.QUEUE,
                    queueTimeoutMinutes = 1, // 60s
                    retryIntervalSeconds = 20,
                    retryStrategy = RetryStrategy.FIXED,
                ),
            ),
        )
        val downstreamNode = WorkflowNode("downstream", "Downstream Action", NodeType.HTTP_ACTION)

        val workflow = Workflow(
            id = "wf_queue_timeout",
            name = "Queue Timeout Workflow",
            isEnabled = true,
            nodes = listOf(trigger, failingNode, downstreamNode),
            connections = listOf(
                WorkflowConnection("t1", "queue_node"),
                WorkflowConnection("queue_node", "downstream"),
            ),
        )

        val result = engine.executeWorkflow(workflow, ExecutionContext(), null)

        // 1. Must result in PreconditionFailed with shouldQueue = true
        assertTrue("Result must be PreconditionFailed", result is WorkflowExecutionResult.PreconditionFailed)
        val pf = result as WorkflowExecutionResult.PreconditionFailed
        assertEquals("queue_node", pf.nodeId)
        assertTrue("shouldQueue flag must be true", pf.shouldQueue)
        assertTrue("Reason must mention queue timeout", pf.reason.contains("queue timeout", ignoreCase = true))

        // 2. Downstream node must be SKIPPED
        val downstreamStep = pf.steps.firstOrNull { it.nodeId == "downstream" }
        assertNotNull("Downstream step must be present", downstreamStep)
        assertEquals("Downstream node must be SKIPPED", NodeStepState.SKIPPED, downstreamStep?.state)
    }

    @Test
    fun testQueueMode_cancellationWhileWaitingInQueue() = runTest {
        // Test that if coroutine is cancelled while waiting in queue, it cancels cleanly
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("error".toResponseBody())
                    .build()
            }
            .build()

        var enteredQueueWait = false
        val evaluator = PreconditionEvaluator(
            context = null,
            okHttpClient = failingClient,
            delayDuration = {
                enteredQueueWait = true
                delay(it) // Will be cancelled here
            },
        )
        val engine = WorkflowEngine(preconditionEvaluator = evaluator, executorFactory = NodeExecutorFactory(failingClient))

        val trigger = WorkflowNode("t1", "Trigger", NodeType.MANUAL_TRIGGER)
        val queueNode = WorkflowNode(
            id = "queue_node",
            name = "Queue Node",
            type = NodeType.HTTP_ACTION,
            configJson = """{"url":"https://api.example.com","method":"GET"}""",
            preconditions = listOf(
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "https://failing.example.com/check",
                    expectedStatus = 200,
                    onFailureAction = PreconditionFailurePolicy.QUEUE,
                    queueTimeoutMinutes = 10,
                    retryIntervalSeconds = 30,
                ),
            ),
        )

        val workflow = Workflow(
            id = "wf_queue_cancel",
            name = "Queue Cancel Workflow",
            isEnabled = true,
            nodes = listOf(trigger, queueNode),
            connections = listOf(WorkflowConnection("t1", "queue_node")),
        )

        var recordedSteps: List<NodeExecutionStep> = emptyList()
        val job = launch {
            engine.executeWorkflowWithProgress(workflow, ExecutionContext(), null) { steps ->
                recordedSteps = steps
            }
        }

        // Wait until it enters queue wait loop
        while (!enteredQueueWait) {
            delay(10.milliseconds)
        }

        // Cancel the execution job
        job.cancelAndJoin()

        // Verify coroutine was cancelled cleanly and step states updated
        assertTrue("Job must be cancelled", job.isCancelled)
        val queueStep = recordedSteps.firstOrNull { it.nodeId == "queue_node" }
        assertNotNull("Queue step must be recorded", queueStep)
        assertEquals(NodeStepState.FAILED, queueStep?.state)
        assertTrue(
            "Step message must indicate cancellation",
            queueStep?.logMessage?.contains("cancelled", ignoreCase = true) == true,
        )
    }

    @Test
    fun testQueueMode_progressStepsStreamCorrectRemainingTimes() = runTest {
        val attempts = AtomicInteger(0)
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val a = attempts.incrementAndGet()
                val code = if (a >= 2) 200 else 500
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Err")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        val streamedSteps = mutableListOf<NodeExecutionStep>()
        val evaluator = PreconditionEvaluator(
            context = null,
            okHttpClient = mockHttpClient,
            delayDuration = { /* fast test execution */ },
        )

        val healthCheck = Precondition.HealthCheck(
            id = "stream_queue_check",
            isEnabled = true,
            targetUrl = "https://api.example.com/health",
            expectedStatus = 200,
            onFailureAction = PreconditionFailurePolicy.QUEUE,
            queueTimeoutMinutes = 5,
            retryIntervalSeconds = 3,
            retryStrategy = RetryStrategy.FIXED,
        )

        val result = evaluator.evaluate(healthCheck) { steps ->
            steps.lastOrNull()?.let { streamedSteps.add(it) }
        }

        assertTrue("Must pass on second attempt", result is PreconditionResult.Passed)

        // Verify streamed steps contain waiting status with countdown details
        val queueWaitingSteps = streamedSteps.filter { it.nodeName.contains("Queued") }
        assertTrue("Must have emitted Queued waiting steps during countdown", queueWaitingSteps.isNotEmpty())
        assertTrue(
            "Waiting step must mention backoff and remaining timeout",
            queueWaitingSteps.any { it.logMessage.contains("timeout remaining") },
        )
    }

    @Test
    fun testQueueMode_chainedPrecondition_recoversInQueue() = runTest {
        val attempts = AtomicInteger(0)
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val a = attempts.incrementAndGet()
                val code = if (a >= 2) 200 else 503
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Unavailable")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        val evaluator = PreconditionEvaluator(
            context = null,
            okHttpClient = mockHttpClient,
            delayDuration = { /* fast */ },
        )

        // Create a chained check with an HTTP check node
        val chainCheckNode = WorkflowNode(
            id = "chain_http_check",
            name = "Chain HTTP Probe",
            type = NodeType.HTTP_ACTION,
            configJson = """{"url":"https://api.example.com/ping","method":"GET"}""",
        )
        val chain = Precondition.ChainCheck(
            id = "prec_chained_queue",
            name = "Chained Queue Precondition",
            isEnabled = true,
            nodes = listOf(chainCheckNode),
            connections = emptyList(),
            onFailureAction = PreconditionFailurePolicy.QUEUE,
            queueTimeoutMinutes = 2,
            retryIntervalSeconds = 5,
            retryStrategy = RetryStrategy.LINEAR,
        )

        val result = evaluator.evaluate(chain)

        assertTrue("Chained precondition must pass after recovering in queue", result is PreconditionResult.Passed)
        assertEquals(2, attempts.get())
    }

    @Test
    fun testQueueMode_internetCheck_recoversImmediatelyDuringDelayCountdownWhenInternetReturns() = runTest {
        var internetAvailable = false
        var ticksWaited = 0

        val evaluator = object : PreconditionEvaluator(
            context = null,
            delayDuration = {
                ticksWaited++
                if (ticksWaited == 2) {
                    // Internet connection becomes active at second 2 of a 60-second backoff delay!
                    internetAvailable = true
                }
            },
        ) {
            override fun isInternetAvailable(): Boolean = internetAvailable
        }

        val check = Precondition.InternetCheck(
            id = "immediate_recovery_internet",
            isEnabled = true,
            onFailureAction = PreconditionFailurePolicy.QUEUE,
            queueTimeoutMinutes = 5,
            retryIntervalSeconds = 60,
            retryStrategy = RetryStrategy.FIXED,
        )

        val stepUpdates = mutableListOf<List<NodeExecutionStep>>()
        val result = evaluator.evaluate(check) { steps ->
            stepUpdates.add(steps)
        }

        assertTrue("Must pass immediately when internet becomes available", result is PreconditionResult.Passed)
        assertTrue("Ticks waited must be around 2, not 60: $ticksWaited", ticksWaited in 1..5)
        val passed = result as PreconditionResult.Passed
        assertTrue("Must contain resolved step", passed.steps.any { it.nodeName.contains("Precondition Satisfied in Queue") })
    }

    @Test
    fun testQueueMode_exponentialBackoff_doesNotPerformConsecutiveRetriesEverySecond() = runTest {
        val httpAttempts = AtomicInteger(0)
        var conditionSatisfied = false

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                httpAttempts.incrementAndGet()
                val code = if (conditionSatisfied) 200 else 503
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Unavailable")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        var ticksWaited = 0
        val evaluator = PreconditionEvaluator(
            context = null,
            okHttpClient = mockHttpClient,
            delayDuration = {
                ticksWaited++
                // After 10s delay countdown finishes, condition is satisfied on next scheduled attempt
                if (ticksWaited >= 10) {
                    conditionSatisfied = true
                }
            },
        )

        val check = Precondition.HealthCheck(
            id = "exponential_backoff_health_check",
            isEnabled = true,
            targetUrl = "https://server.example.com/health",
            expectedStatus = 200,
            onFailureAction = PreconditionFailurePolicy.QUEUE,
            queueTimeoutMinutes = 10,
            retryIntervalSeconds = 10,
            retryStrategy = RetryStrategy.EXPONENTIAL,
        )

        val result = evaluator.evaluate(check)

        assertTrue("Must pass after scheduled interval check succeeds", result is PreconditionResult.Passed)
        // Ensure HTTP was NOT called 10 times consecutively during the 10-second countdown!
        // Exactly 2 attempts: attempt 1 (initial failure) + attempt 2 (after 10s countdown)
        assertEquals("Must only evaluate at interval boundaries, not every second", 2, httpAttempts.get())
        assertEquals("Must have counted 10 seconds of delay ticks", 10, ticksWaited)
    }
}
