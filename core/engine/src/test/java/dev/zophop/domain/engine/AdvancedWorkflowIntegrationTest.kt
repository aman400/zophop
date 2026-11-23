package dev.zophop.domain.engine

import dev.zophop.data.CredentialRepository
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedWorkflowIntegrationTest {
    private class FakeCredentialRepository(
        private val credentials: List<Credential>,
    ) : CredentialRepository {
        override fun getAllCredentialsFlow(): Flow<List<Credential>> = flowOf(credentials)

        override suspend fun getCredentialById(id: String): Credential? = credentials.find { it.id == id }

        override suspend fun saveCredential(credential: Credential) {}

        override suspend fun deleteCredential(id: String) {}
    }

    @Test
    fun testPreconditionFailureBlocksNodeExecutionAndHaltsWorkflow() =
        runTest {
            val requestedUrls = mutableListOf<String>()

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val url = chain.request().url.toString()
                        requestedUrls.add(url)
                        if (url.contains("failing.service")) {
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(500)
                                .message("Internal Server Error")
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

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = okHttpClient)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(okHttpClient),
                )

            val failingPrecondition =
                Precondition.HealthCheck(
                    id = "prec_fail_1",
                    isEnabled = true,
                    targetUrl = "https://failing.service/healthz",
                    expectedStatus = 200,
                )

            val triggerNode = WorkflowNode("t1", "Manual Trigger", NodeType.MANUAL_TRIGGER)
            val actionNode =
                WorkflowNode(
                    id = "a1",
                    name = "Guarded HTTP Action",
                    type = NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://api.example.com/data","method":"POST"}""",
                    preconditions = listOf(failingPrecondition),
                )

            val workflow =
                Workflow(
                    id = "w_precondition_fail",
                    name = "Precondition Failure Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, actionNode),
                    connections = listOf(WorkflowConnection("t1", "a1")),
                )

            val context = ExecutionContext(mapOf("body" to "Trigger Payload"))
            val result = engine.executeWorkflow(workflow, context, null)

            assertTrue(result is WorkflowExecutionResult.PreconditionFailed)
            val failureReason = (result as WorkflowExecutionResult.PreconditionFailed).reason
            assertTrue(failureReason.contains("500") || failureReason.contains("failed") || failureReason.contains("HealthCheck"))
            // Main action URL (api.example.com) must never be requested because precondition failed
            assertTrue(requestedUrls.none { it.contains("api.example.com") })
        }

    @Test
    fun testMultiBranchConditionTrueAndFalsePaths() =
        runTest {
            val capturedUrls = mutableListOf<String>()

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedUrls.add(chain.request().url.toString())
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

            val triggerId = "node_trig"
            val conditionId = "node_cond"
            val highPriorityActionId = "node_high"

            val triggerNode = WorkflowNode(triggerId, "Trigger", NodeType.MANUAL_TRIGGER)
            val conditionNode =
                WorkflowNode(
                    id = conditionId,
                    name = "Check High Value",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"amount","operator":"GREATER_THAN","value":"1000"}""",
                )
            val highAction =
                WorkflowNode(
                    id = highPriorityActionId,
                    name = "High Priority Webhook",
                    type = NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"high-priority-alerts"}""",
                )

            val workflow =
                Workflow(
                    id = "w_multi_branch",
                    name = "Multi-Branch Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, conditionNode, highAction),
                    connections =
                        listOf(
                            WorkflowConnection(triggerId, conditionId),
                            // After BUG-01 fix: ConditionExecutor emits "true" on match
                            WorkflowConnection(conditionId, highPriorityActionId, outputPort = "true"),
                        ),
                )

            // Case 1: High amount (5000 > 1000) -> Condition true -> Executes high-priority-alerts path
            val highCtx = ExecutionContext(mapOf("amount" to "5000"))
            val resultHigh = engine.executeWorkflow(workflow, highCtx, null)
            assertTrue(resultHigh is WorkflowExecutionResult.Success)
            assertEquals(1, capturedUrls.size)
            assertTrue(capturedUrls[0].contains("high-priority-alerts"))

            capturedUrls.clear()

            // Case 2: Low amount (200 <= 1000) -> Condition false; no "false" connection → workflow
            // still completes as Success (a false condition no longer halts the workflow)
            val lowCtx = ExecutionContext(mapOf("amount" to "200"))
            val resultLow = engine.executeWorkflow(workflow, lowCtx, null)
            assertTrue(resultLow is WorkflowExecutionResult.Success)
            assertEquals(0, capturedUrls.size)
        }

    @Test
    fun testFanOutParallelActionNodesExecution() =
        runTest {
            val capturedRequests = mutableListOf<Request>()

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedRequests.add(chain.request())
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

            val triggerId = "trig_fanout"
            val ntfyId = "ntfy_fanout"
            val gotifyId = "gotify_fanout"

            val triggerNode = WorkflowNode(triggerId, "Manual Trigger", NodeType.MANUAL_TRIGGER)
            val ntfyNode =
                WorkflowNode(
                    id = ntfyId,
                    name = "ntfy Push",
                    type = NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"fanout-topic","title":"Alert"}""",
                )
            val gotifyNode =
                WorkflowNode(
                    id = gotifyId,
                    name = "Gotify Push",
                    type = NodeType.GOTIFY_ACTION,
                    configJson = """{"serverUrl":"https://gotify.test","appToken":"A123B456","title":"Alert"}""",
                )

            val workflow =
                Workflow(
                    id = "w_fanout",
                    name = "Fan-out Broadcast Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, ntfyNode, gotifyNode),
                    connections =
                        listOf(
                            WorkflowConnection(triggerId, ntfyId),
                            WorkflowConnection(triggerId, gotifyId),
                        ),
                )

            val context = ExecutionContext(mapOf("sender" to "99999", "body" to "Broadcast Message"))
            val result = engine.executeWorkflow(workflow, context, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals(2, capturedRequests.size)

            val urls = capturedRequests.map { it.url.toString() }
            assertTrue(urls.any { it.contains("fanout-topic") })
            assertTrue(urls.any { it.contains("gotify.test") })
        }

    @Test
    fun testCredentialResolutionInActionNodes() =
        runTest {
            val capturedAuthHeaders = mutableListOf<String>()

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedAuthHeaders.add(chain.request().header("Authorization") ?: "")
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val secretCred =
                Credential(
                    id = "cred_secret_123",
                    name = "Bearer Secret Token",
                    type = CredentialType.BEARER_TOKEN,
                    dataMap = mapOf("token" to "super_secret_bearer_999"),
                )
            val credRepo = FakeCredentialRepository(listOf(secretCred))

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = okHttpClient)
            val engine =
                WorkflowEngine(
                    preconditionEvaluator = evaluator,
                    credentialRepository = credRepo,
                    executorFactory = NodeExecutorFactory(okHttpClient, credentialRepository = credRepo),
                )

            val triggerNode = WorkflowNode("n1", "Trigger", NodeType.MANUAL_TRIGGER)
            val httpNode =
                WorkflowNode(
                    id = "n2",
                    name = "Authenticated HTTP",
                    type = NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://secure.api.com/v1/resource","method":"POST","credentialId":"cred_secret_123"}""",
                )

            val workflow =
                Workflow(
                    id = "w_auth",
                    name = "Authenticated Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, httpNode),
                    connections = listOf(WorkflowConnection("n1", "n2")),
                )

            val context = ExecutionContext(mapOf("body" to "Data"))
            val result = engine.executeWorkflow(workflow, context, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals(1, capturedAuthHeaders.size)
            assertEquals("Bearer super_secret_bearer_999", capturedAuthHeaders[0])
        }

    @Test
    fun testDisabledNodeBypassInWorkflowPipeline() =
        runTest {
            val capturedUrls = mutableListOf<String>()

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        capturedUrls.add(chain.request().url.toString())
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

            val triggerNode = WorkflowNode("n1", "Trigger", NodeType.MANUAL_TRIGGER)
            val disabledNode =
                WorkflowNode(
                    id = "n2_disabled",
                    name = "Disabled Formatter",
                    type = NodeType.TEMPLATE_FORMATTER,
                    isEnabled = false,
                    configJson = """{"template":"Ignored Template"}""",
                )
            val activeActionNode =
                WorkflowNode(
                    id = "n3",
                    name = "Active Action",
                    type = NodeType.NTFY_ACTION,
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"bypass-topic"}""",
                )

            val workflow =
                Workflow(
                    id = "w_bypass",
                    name = "Disabled Node Bypass Workflow",
                    isEnabled = true,
                    nodes = listOf(triggerNode, disabledNode, activeActionNode),
                    connections =
                        listOf(
                            WorkflowConnection("n1", "n2_disabled"),
                            WorkflowConnection("n2_disabled", "n3"),
                        ),
                )

            val context = ExecutionContext(mapOf("body" to "Hello World"))
            val result = engine.executeWorkflow(workflow, context, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals(1, capturedUrls.size)
            assertTrue(capturedUrls[0].contains("bypass-topic"))
        }
}
