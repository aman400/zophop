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
}
