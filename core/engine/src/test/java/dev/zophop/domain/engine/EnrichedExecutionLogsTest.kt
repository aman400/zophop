package dev.zophop.domain.engine

import dev.zophop.domain.engine.executors.ConditionExecutor
import dev.zophop.domain.engine.executors.HttpActionExecutor
import dev.zophop.domain.engine.executors.JsonParserExecutor
import dev.zophop.domain.engine.executors.RegexParserExecutor
import dev.zophop.domain.engine.executors.TemplateFormatterExecutor
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class EnrichedExecutionLogsTest {
    @Test
    fun httpActionExecutor_logsRedactedRequestAndResponseDetails() =
        runTest {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        Response
                            .Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(201)
                            .message("Created")
                            .addHeader("Content-Type", "application/json")
                            .addHeader("Set-Cookie", "sid=super_secret_cookie")
                            .body("""{"ok":true,"id":42}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }.build()

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = "http_rich",
                    name = "Webhook",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        """
                        {
                            "url": "https://api.example.com/events?api_key=raw_query_secret&mode=fast",
                            "method": "POST",
                            "authType": "BEARER",
                            "bearerToken": "raw_bearer_secret_999",
                            "bodyType": "JSON",
                            "bodyTemplate": "{\"hello\":\"world\"}"
                        }
                        """.trimIndent(),
                )

            val result = executor.execute(node, ExecutionContext(), null)
            assertTrue(result is NodeExecutionResult.Success)
            val log = (result as NodeExecutionResult.Success).logMessage

            assertTrue(log.contains("HTTP POST https://api.example.com/events"))
            assertTrue(log.contains("--- Request Details ---"))
            assertTrue(log.contains("--- Request Headers ---"))
            assertTrue(log.contains("Authorization: Bearer ***"))
            assertTrue(log.contains("--- Request Body ---"))
            assertTrue(log.contains("""{"hello":"world"}"""))
            assertTrue(log.contains("--- Response Summary ---"))
            assertTrue(log.contains("Status: 201 Created"))
            assertTrue(log.contains("--- Response Body ---"))
            assertTrue(log.contains("""{"ok":true,"id":42}"""))
            assertFalse("Raw bearer token must never leak in logs", log.contains("raw_bearer_secret_999"))
            assertFalse("Raw query secret must never leak in logs", log.contains("raw_query_secret"))
            assertFalse("Raw Set-Cookie must never leak in logs", log.contains("super_secret_cookie"))
        }

    @Test
    fun httpActionExecutor_ioExceptionIncludesMethodUrlAndRequestDetails() =
        runTest {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor {
                        throw IOException("Connection timed out")
                    }.build()

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = "http_err",
                    name = "Webhook",
                    type = NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://api.example.com/fail?token=secret_q","method":"PUT"}""",
                )

            val result = executor.execute(node, ExecutionContext(), null)
            assertTrue(result is NodeExecutionResult.Failed)
            val reason = (result as NodeExecutionResult.Failed).reason
            assertTrue(reason.contains("HTTP PUT https://api.example.com/fail"))
            assertTrue(reason.contains("Connection timed out"))
            assertTrue(reason.contains("--- Request Details ---"))
            assertFalse(reason.contains("secret_q"))
        }

    @Test
    fun parsersAndCondition_includeStructuredEvaluationSections() =
        runTest {
            val regexNode =
                WorkflowNode(
                    id = "r1",
                    name = "Regex",
                    type = NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\b(\\d{6})\\b","outputKey":"otp"}""",
                )
            val ctx = ExecutionContext(mapOf("body" to "Your OTP is 654321."))
            val regexRes = RegexParserExecutor().execute(regexNode, ctx, null) as NodeExecutionResult.Success
            assertTrue(regexRes.logMessage.contains("--- Regex Extraction Details ---"))
            assertTrue(regexRes.logMessage.contains("Matches Found: 1"))

            val condNode =
                WorkflowNode(
                    id = "c1",
                    name = "Condition",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"otp","operator":"EQUALS","value":"654321"}""",
                )
            val condRes = ConditionExecutor().execute(condNode, ctx, null) as NodeExecutionResult.Success
            assertTrue(condRes.logMessage.contains("--- Evaluation Details ---"))
            assertTrue(condRes.logMessage.contains("Actual Value: '654321'"))
            assertTrue(condRes.logMessage.contains("Output Port: true"))

            val fmtNode =
                WorkflowNode(
                    id = "f1",
                    name = "Formatter",
                    type = NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"Code: ${'$'}{otp}"}""",
                )
            val fmtRes = TemplateFormatterExecutor().execute(fmtNode, ctx, null) as NodeExecutionResult.Success
            assertTrue(fmtRes.logMessage.contains("--- Template Details ---"))
            assertTrue(fmtRes.logMessage.contains("--- Formatted Output ---"))

            val jsonNode =
                WorkflowNode(
                    id = "j1",
                    name = "JSON",
                    type = NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"user.role","outputKey":"role"}""",
                )
            val jsonCtx = ExecutionContext(mapOf("body" to """{"user":{"role":"admin"}}"""))
            val jsonRes = JsonParserExecutor().execute(jsonNode, jsonCtx, null) as NodeExecutionResult.Success
            assertTrue(jsonRes.logMessage.contains("--- JSON Extraction Details ---"))
            assertTrue(jsonRes.logMessage.contains("Extracted Value: admin"))
        }

    @Test
    fun workflowEngine_retainsPassedPreconditionsSummaryInStepLog() =
        runTest {
            val evaluator =
                object : PreconditionEvaluator(context = null) {
                    override fun isInternetAvailable(): Boolean = true
                }
            val engine = WorkflowEngine(preconditionEvaluator = evaluator)
            val trigger =
                WorkflowNode(
                    id = "t1",
                    name = "Manual",
                    type = NodeType.MANUAL_TRIGGER,
                    configJson = """{"testBody":"Hello"}""",
                    preconditions = listOf(Precondition.InternetCheck(id = "prec_net")),
                )
            val formatter =
                WorkflowNode(
                    id = "f1",
                    name = "Format",
                    type = NodeType.TEMPLATE_FORMATTER,
                    configJson = """{"template":"[${'$'}{body}]"}""",
                )
            val workflow =
                Workflow(
                    id = "wf_prec_trace",
                    name = "Precondition Trace",
                    isEnabled = true,
                    nodes = listOf(trigger, formatter),
                    connections = listOf(WorkflowConnection("t1", "f1")),
                )

            val result = engine.executeWorkflow(workflow, ExecutionContext(), null)
            assertTrue(result is WorkflowExecutionResult.Success)
            val triggerStep = result.steps.first { it.nodeId == "t1" }
            assertTrue(triggerStep.logMessage.contains("Manual trigger executed on demand"))
            assertTrue(triggerStep.logMessage.contains("--- Preconditions Passed"))
            assertTrue(triggerStep.logMessage.contains("Active Internet connection verified"))
        }
}
