package dev.zophop.domain.engine

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WorkflowEngineTest {
    @Test
    fun testEndToEndWorkflowExecutionSuccess() =
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

            val triggerId = UUID.randomUUID().toString()
            val parserId = UUID.randomUUID().toString()
            val templateId = UUID.randomUUID().toString()
            val actionId = UUID.randomUUID().toString()

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "SMS Trigger",
                    type = NodeType.SMS_TRIGGER,
                    configJson = "{\"allowlist\":\".*\",\"keywordRegex\":\".*\"}",
                )

            val parserNode =
                WorkflowNode(
                    id = parserId,
                    name = "Regex Parser",
                    type = NodeType.REGEX_PARSER,
                    configJson = "{\"regex\":\"\\\\b\\\\d{6}\\\\b\",\"outputKey\":\"code\"}",
                )

            val templateNode =
                WorkflowNode(
                    id = templateId,
                    name = "Template Formatter",
                    type = NodeType.TEMPLATE_FORMATTER,
                    configJson = "{\"template\":\"Your code is \${code}\"}",
                )

            val actionNode =
                WorkflowNode(
                    id = actionId,
                    name = "ntfy Target",
                    type = NodeType.NTFY_ACTION,
                    configJson = "{\"serverUrl\":\"https://ntfy.sh\",\"topic\":\"test-topic\"}",
                )

            val workflow =
                Workflow(
                    id = "w1",
                    name = "Test Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, parserNode, templateNode, actionNode),
                    connections =
                        listOf(
                            WorkflowConnection(fromNodeId = triggerId, toNodeId = parserId),
                            WorkflowConnection(fromNodeId = parserId, toNodeId = templateId),
                            WorkflowConnection(fromNodeId = templateId, toNodeId = actionId),
                        ),
                )

            val execContext =
                ExecutionContext(
                    mapOf(
                        "sender" to "BANKALERT",
                        "body" to "Your OTP verification code is 654321",
                    ),
                )

            val result = engine.executeWorkflow(workflow, execContext, appContext = null)
            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals("654321", execContext.getString("code"))
            assertEquals("Your code is 654321", execContext.getString("formatted_payload"))
        }

    @Test
    fun testExecuteWorkflowWithProgressUpdates() =
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

            val triggerId = UUID.randomUUID().toString()
            val actionId = UUID.randomUUID().toString()

            val triggerNode = WorkflowNode(id = triggerId, name = "SMS Trigger", type = NodeType.SMS_TRIGGER)
            val actionNode =
                WorkflowNode(
                    id = actionId,
                    name = "ntfy Target",
                    type = NodeType.NTFY_ACTION,
                    configJson = "{\"serverUrl\":\"https://ntfy.sh\",\"topic\":\"topic\"}",
                )

            val workflow =
                Workflow(
                    id = "w-progress",
                    name = "Progress Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, actionNode),
                    connections = listOf(WorkflowConnection(fromNodeId = triggerId, toNodeId = actionId)),
                )

            val execContext = ExecutionContext(mapOf("sender" to "Test", "body" to "123456"))
            var capturedSteps = emptyList<NodeExecutionStep>()

            val result =
                engine.executeWorkflowWithProgress(
                    workflow = workflow,
                    context = execContext,
                    appContext = null,
                    onStepUpdate = { steps -> capturedSteps = steps },
                )

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals(2, capturedSteps.size)
            assertTrue(capturedSteps.all { it.state == NodeStepState.SUCCESS })
        }

    @Test
    fun testWorkflowPreconditionFailedHaltsExecution() =
        runTest {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(503)
                            .message("Service Unavailable")
                            .body("error".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = okHttpClient)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(okHttpClient),
                )

            val triggerId = UUID.randomUUID().toString()
            val actionId = UUID.randomUUID().toString()

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "SMS Trigger",
                    type = NodeType.SMS_TRIGGER,
                )

            val actionNode =
                WorkflowNode(
                    id = actionId,
                    name = "ntfy Target",
                    type = NodeType.NTFY_ACTION,
                    configJson = "{\"serverUrl\":\"https://ntfy.sh\",\"topic\":\"test-topic\"}",
                    preconditions =
                        listOf(
                            Precondition.HealthCheck(
                                isEnabled = true,
                                targetUrl = "https://ntfy.sh/health",
                                expectedStatus = 200,
                            ),
                        ),
                )

            val workflow =
                Workflow(
                    id = "w2",
                    name = "Precondition Test Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, actionNode),
                    connections =
                        listOf(
                            WorkflowConnection(fromNodeId = triggerId, toNodeId = actionId),
                        ),
                )

            val execContext =
                ExecutionContext(
                    mapOf("sender" to "Sender", "body" to "Hello World"),
                )

            val result = engine.executeWorkflow(workflow, execContext, appContext = null)
            assertTrue(result is WorkflowExecutionResult.PreconditionFailed)
        }

    @Test
    fun testWorkflowResumeFromFailedNode() =
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

            val triggerId = UUID.randomUUID().toString()
            val actionId = UUID.randomUUID().toString()

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "SMS Trigger",
                    type = NodeType.SMS_TRIGGER,
                )

            val actionNode =
                WorkflowNode(
                    id = actionId,
                    name = "ntfy Target Action",
                    type = NodeType.NTFY_ACTION,
                    configJson = "{\"serverUrl\":\"https://ntfy.sh\",\"topic\":\"test-topic\"}",
                )

            val workflow =
                Workflow(
                    id = "w3",
                    name = "Retry Test Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, actionNode),
                    connections =
                        listOf(
                            WorkflowConnection(fromNodeId = triggerId, toNodeId = actionId),
                        ),
                )

            val execContext =
                ExecutionContext(
                    mapOf("sender" to "ManualRetry", "body" to "Retried Payload 849201"),
                )

            val result = engine.executeWorkflow(workflow, execContext, appContext = null, startNodeId = actionId)
            assertTrue(result is WorkflowExecutionResult.Success)
        }

    @Test
    fun testNodeExecutionStepSerializationAndDeserialization() {
        val steps =
            listOf(
                NodeExecutionStep(
                    nodeId = "node_1",
                    nodeName = "HTTP Action",
                    nodeType = NodeType.HTTP_ACTION,
                    state = NodeStepState.SUCCESS,
                    errorReason = null,
                    executedAt = 1700000000000L,
                    durationMs = 42L,
                    retriedInExecutionId = "exec_retry_123",
                ),
                NodeExecutionStep(
                    nodeId = "node_2",
                    nodeName = "ntfy Target",
                    nodeType = NodeType.NTFY_ACTION,
                    state = NodeStepState.FAILED,
                    errorReason = "Timeout",
                    executedAt = 1700000000050L,
                    durationMs = 1250L,
                    retriedInExecutionId = null,
                ),
            )

        val json = steps.toJsonString()
        val deserialized = json.toNodeExecutionSteps()

        assertNotNull(deserialized)
        assertEquals(2, deserialized?.size)
        assertEquals("node_1", deserialized?.get(0)?.nodeId)
        assertEquals(1700000000000L, deserialized?.get(0)?.executedAt)
        assertEquals(42L, deserialized?.get(0)?.durationMs)
        assertEquals("exec_retry_123", deserialized?.get(0)?.retriedInExecutionId)

        assertEquals("node_2", deserialized?.get(1)?.nodeId)
        assertEquals(1700000000050L, deserialized?.get(1)?.executedAt)
        assertEquals(1250L, deserialized?.get(1)?.durationMs)
        assertNull(deserialized?.get(1)?.retriedInExecutionId)
    }

    @Test
    fun testWorkflowExecutionPopulatesTimestampsAndDurations() =
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

            val triggerId = UUID.randomUUID().toString()
            val actionId = UUID.randomUUID().toString()

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "SMS Trigger",
                    type = NodeType.SMS_TRIGGER,
                )

            val actionNode =
                WorkflowNode(
                    id = actionId,
                    name = "ntfy Target Action",
                    type = NodeType.NTFY_ACTION,
                    configJson = "{\"serverUrl\":\"https://ntfy.sh\",\"topic\":\"test-topic\"}",
                )

            val workflow =
                Workflow(
                    id = "w_timed",
                    name = "Timed Test Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, actionNode),
                    connections = listOf(WorkflowConnection(fromNodeId = triggerId, toNodeId = actionId)),
                )

            val execContext =
                ExecutionContext(
                    mapOf("sender" to "Sender", "body" to "Body"),
                )

            val result = engine.executeWorkflow(workflow, execContext, appContext = null)
            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals(2, result.steps.size)
            result.steps.forEach { step ->
                assertNotNull(step.executedAt)
                assertTrue((step.executedAt ?: 0L) > 0L)
                assertNotNull(step.durationMs)
                assertTrue((step.durationMs ?: -1L) >= 0L)
            }
        }

    @Test
    fun testWorkflowTriggerFilterMismatchReturnsIgnoredAndSkipsDownstream() =
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

            val triggerId = UUID.randomUUID().toString()
            val actionId = UUID.randomUUID().toString()

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "SMS Trigger",
                    type = NodeType.SMS_TRIGGER,
                    configJson = "{\"allowlist\":\"BANK.*\",\"keywordRegex\":\".*\"}",
                )

            val actionNode =
                WorkflowNode(
                    id = actionId,
                    name = "ntfy Target Action",
                    type = NodeType.NTFY_ACTION,
                    configJson = "{\"serverUrl\":\"https://ntfy.sh\",\"topic\":\"test-topic\"}",
                )

            val workflow =
                Workflow(
                    id = "w_ignored",
                    name = "Ignored Test Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, actionNode),
                    connections = listOf(WorkflowConnection(fromNodeId = triggerId, toNodeId = actionId)),
                )

            val execContext =
                ExecutionContext(
                    mapOf("sender" to "PROMO_SPAM", "body" to "Buy now!"),
                )

            val result = engine.executeWorkflow(workflow, execContext, appContext = null)
            assertTrue("Expected WorkflowExecutionResult.Ignored", result is WorkflowExecutionResult.Ignored)
            assertEquals(2, result.steps.size)
            assertTrue(result.steps.all { it.state == NodeStepState.SKIPPED })
        }

    @Test
    fun testQueuePolicySetsIsQueueWaitingOnNodeStepDuringWaitAndClearsOnRecovery() =
        runTest {
            val healthAttempts = java.util.concurrent.atomic.AtomicInteger(0)
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val url = chain.request().url.toString()
                        val code =
                            if (url.contains("health")) {
                                if (healthAttempts.incrementAndGet() >= 2) 200 else 503
                            } else {
                                200
                            }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message(if (code == 200) "OK" else "Service Unavailable")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val evaluator =
                PreconditionEvaluator(
                    context = null,
                    okHttpClient = okHttpClient,
                    delayDuration = { /* skip real delay */ },
                )
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(okHttpClient),
                )

            val triggerNode = WorkflowNode(id = "trig", name = "Trigger", type = NodeType.MANUAL_TRIGGER)
            val actionNode =
                WorkflowNode(
                    id = "act",
                    name = "ntfy Target",
                    type = NodeType.NTFY_ACTION,
                    configJson = "{\"serverUrl\":\"https://ntfy.sh\",\"topic\":\"test-topic\"}",
                    preconditions =
                        listOf(
                            Precondition.HealthCheck(
                                isEnabled = true,
                                targetUrl = "https://ntfy.sh/health",
                                expectedStatus = 200,
                                onFailureAction = dev.zophop.domain.model.PreconditionFailurePolicy.QUEUE,
                                queueTimeoutMinutes = 5,
                                retryIntervalSeconds = 10,
                            ),
                        ),
                )
            val workflow =
                Workflow(
                    id = "wf_queue_flag",
                    name = "Queue Flag Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, actionNode),
                    connections = listOf(WorkflowConnection("trig", "act")),
                )

            val stepSnapshots = mutableListOf<List<NodeExecutionStep>>()
            val result =
                engine.executeWorkflowWithProgress(
                    workflow = workflow,
                    context = ExecutionContext(mapOf("body" to "hello")),
                    appContext = null,
                    onStepUpdate = { steps -> stepSnapshots.add(steps) },
                )

            assertTrue(result is WorkflowExecutionResult.Success)
            assertTrue(
                "Expected at least one step snapshot with isWaitingInQueue() == true",
                stepSnapshots.any { it.isWaitingInQueue() },
            )
            assertTrue(
                "Final steps must clear isQueueWaiting",
                result.steps.none { it.isQueueWaiting },
            )
        }
}
