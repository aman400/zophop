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
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowAndPreconditionRetryTest {
    @Test
    fun testPreconditionFailImmediatelyPolicyNoRetry() =
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
                    id = "prec_fail_now",
                    isEnabled = true,
                    targetUrl = "https://example.com/healthz",
                    expectedStatus = 200,
                    onFailureAction = "FAIL_IMMEDIATELY",
                    maxRetries = 5,
                    retryIntervalSeconds = 1,
                )

            val result = evaluator.evaluate(healthCheck)

            assertTrue(result is PreconditionResult.Failed)
            assertEquals(1, attempts) // Fail immediately must NOT retry despite maxRetries = 5
        }

    @Test
    fun testPreconditionRetryProgressCallbackEmitsStepUpdates() =
        runTest {
            var attempts = 0
            val emittedSteps = mutableListOf<List<NodeExecutionStep>>()

            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        attempts++
                        if (attempts < 2) {
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(503)
                                .message("Service Unavailable")
                                .body("busy".toResponseBody())
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
                    id = "prec_retry_step",
                    isEnabled = true,
                    targetUrl = "https://example.com/healthz",
                    expectedStatus = 200,
                    onFailureAction = "RETRY",
                    maxRetries = 2,
                    retryIntervalSeconds = 1,
                )

            val result =
                evaluator.evaluate(healthCheck) { steps ->
                    emittedSteps.add(steps)
                }

            assertTrue(result is PreconditionResult.Passed)
            assertEquals(2, attempts)
            assertTrue(emittedSteps.isNotEmpty())
        }

    @Test
    fun testWorkflowEngineResumeFromFailedNode() =
        runTest {
            val executedNodes = mutableListOf<String>()

            val client = OkHttpClient.Builder().build()
            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(client),
                )

            val node1 = WorkflowNode("n1", "SMS Trigger", NodeType.MANUAL_TRIGGER)
            val node2 =
                WorkflowNode("n2", "Formatter", NodeType.TEMPLATE_FORMATTER, configJson = "{\"template\":\"Resumed \${'$'}{body}\"}")
            val node3 =
                WorkflowNode(
                    id = "n3",
                    name = "ntfy Webhook",
                    type = NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"resumed-topic"}""",
                )

            val workflow =
                Workflow(
                    id = "w_resume",
                    name = "Resumable Workflow",
                    isEnabled = true,
                    nodes = listOf(node1, node2, node3),
                    connections =
                        listOf(
                            WorkflowConnection("n1", "n2"),
                            WorkflowConnection("n2", "n3"),
                        ),
                )

            val context = ExecutionContext(mapOf("body" to "Test Resume"))

            // Resume from node2 ("n2") skipping node1 ("n1")
            val result =
                engine.executeWorkflow(
                    workflow = workflow,
                    context = context,
                    appContext = null,
                    startNodeId = "n2",
                )

            assertTrue(result is WorkflowExecutionResult.Success)
            val logs = (result as WorkflowExecutionResult.Success).logs.joinToString("\n")
            assertTrue(logs.contains("Resuming from node 'n2'"))
            assertTrue(logs.contains("Formatter"))
            assertTrue(logs.contains("ntfy Webhook"))
        }

    @Test
    fun testWorkflowEngineRetryWithUpdatedContextVariables() =
        runTest {
            val capturedBodies = mutableListOf<String>()

            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val buffer = Buffer()
                        chain.request().body?.writeTo(buffer)
                        capturedBodies.add(buffer.readUtf8())

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
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(client),
                )

            val actionNode =
                WorkflowNode(
                    id = "action_retry",
                    name = "ntfy Retry Action",
                    type = NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"retry-topic","template":"OTP Code: ${'$'}{otp}"}""",
                )

            val workflow =
                Workflow(
                    id = "w_retry_vars",
                    name = "Retry Vars Workflow",
                    isEnabled = true,
                    nodes = listOf(actionNode),
                    connections = emptyList(),
                )

            // Context updated with new OTP variable before retrying node
            val retriedContext = ExecutionContext(mapOf("otp" to "998877"))

            val result =
                engine.executeWorkflow(
                    workflow = workflow,
                    context = retriedContext,
                    appContext = null,
                    startNodeId = "action_retry",
                )

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals(1, capturedBodies.size)
            assertEquals("OTP Code: 998877", capturedBodies[0])
        }

    @Test
    fun testChainedPreconditionRetryPolicyRecovery() =
        kotlinx.coroutines.runBlocking {
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
                                .body("busy".toResponseBody())
                                .build()
                        } else {
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body("""{"status":"ONLINE"}""".toResponseBody())
                                .build()
                        }
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)

            val triggerNode = WorkflowNode("ch_trig", "Trigger", NodeType.MANUAL_TRIGGER)
            val probeNode =
                WorkflowNode(
                    id = "ch_probe",
                    name = "HTTP Probe",
                    type = NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://server.test/health","method":"GET"}""",
                )
            val jsonParserNode =
                WorkflowNode(
                    id = "ch_json",
                    name = "JSON Extractor",
                    type = NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"status","outputKey":"server_status"}""",
                )
            val conditionCheckNode =
                WorkflowNode(
                    id = "ch_cond",
                    name = "Condition Check",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"server_status","operator":"EQUALS","value":"ONLINE"}""",
                )

            val chainedCheckPrecondition =
                Precondition.ChainCheck(
                    id = "prec_chained_retry",
                    isEnabled = true,
                    nodes = listOf(triggerNode, probeNode, jsonParserNode, conditionCheckNode),
                    connections =
                        listOf(
                            WorkflowConnection("ch_trig", "ch_probe"),
                            WorkflowConnection("ch_probe", "ch_json"),
                            WorkflowConnection("ch_json", "ch_cond"),
                        ),
                    onFailureAction = "RETRY",
                    maxRetries = 2,
                    retryIntervalSeconds = 1,
                )

            val result = evaluator.evaluate(chainedCheckPrecondition)

            assertTrue(result is PreconditionResult.Passed)
            assertEquals(2, attempts)
        }

    @Test
    fun testPreconditionFailImmediatelyPolicy() =
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
                            .message("Internal Server Error")
                            .body("error".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val healthCheck =
                Precondition.HealthCheck(
                    id = "prec_fail_now",
                    isEnabled = true,
                    targetUrl = "https://example.com/healthz",
                    expectedStatus = 200,
                    onFailureAction = "FAIL_IMMEDIATELY",
                    maxRetries = 3,
                    retryIntervalSeconds = 1,
                )

            val result = evaluator.evaluate(healthCheck)

            assertTrue(result is PreconditionResult.Failed)
            assertEquals(1, attempts)
        }

    @Test
    fun testPreconditionMaxRetriesExhaustedFailure() =
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
                            .code(503)
                            .message("Service Unavailable")
                            .body("busy".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val healthCheck =
                Precondition.HealthCheck(
                    id = "prec_exhaust",
                    isEnabled = true,
                    targetUrl = "https://example.com/healthz",
                    expectedStatus = 200,
                    onFailureAction = "RETRY",
                    maxRetries = 2,
                    retryIntervalSeconds = 1,
                )

            val result = evaluator.evaluate(healthCheck)

            assertTrue(result is PreconditionResult.Failed)
            // Initial attempt + 2 retries = 3 total attempts
            assertEquals(3, attempts)
        }
}
