package dev.zophop.integration

import dev.zophop.domain.engine.NodeExecutorFactory
import dev.zophop.domain.engine.PreconditionEvaluator
import dev.zophop.domain.engine.PreconditionResult
import dev.zophop.domain.engine.WorkflowEngine
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndToEndForwardingIntegrationTest {
    @Test
    fun testSmsToNtfyForwardingPipelineEndToEnd() =
        runTest {
            val capturedRequests = mutableListOf<Request>()
            val capturedBodies = mutableListOf<String>()

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedRequests.add(request)
                        val buffer = Buffer()
                        request.body?.writeTo(buffer)
                        capturedBodies.add(buffer.readUtf8())

                        Response
                            .Builder()
                            .request(request)
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

            val triggerNode =
                WorkflowNode(
                    id = "n_trigger",
                    name = "Manual Trigger",
                    type = NodeType.MANUAL_TRIGGER,
                )
            val otpParserNode =
                WorkflowNode(
                    id = "n_otp_parser",
                    name = "Extract OTP",
                    type = NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\b\\d{6}\\b","outputKey":"otp"}""",
                )
            val formatterNode =
                WorkflowNode(
                    id = "n_formatter",
                    name = "Format Alert Message",
                    type = NodeType.TEMPLATE_FORMATTER,
                    configJson = "{\"template\":\"HDFC Alert: Security Code \${otp}\"}",
                )
            val ntfyActionNode =
                WorkflowNode(
                    id = "n_ntfy_action",
                    name = "Send ntfy Push Notification",
                    type = NodeType.NTFY_ACTION,
                    configJson = "{\"serverUrl\":\"https://ntfy.sh\",\"topic\":\"my-bank-alerts\",\"title\":\"HDFC OTP Alert\",\"priority\":\"5\",\"tags\":\"bank\"}",
                )

            val workflow =
                Workflow(
                    id = "w_sms_to_ntfy",
                    name = "SMS to ntfy Pipeline",
                    isEnabled = true,
                    nodes = listOf(triggerNode, otpParserNode, formatterNode, ntfyActionNode),
                    connections =
                        listOf(
                            WorkflowConnection("n_trigger", "n_otp_parser"),
                            WorkflowConnection("n_otp_parser", "n_formatter"),
                            WorkflowConnection("n_formatter", "n_ntfy_action"),
                        ),
                )

            val incomingSmsContext =
                ExecutionContext(
                    mapOf(
                        "sender" to "HDFCBK",
                        "body" to "HDFC Bank: OTP for Rs 12,500 at Amazon is 938104",
                    ),
                )

            val result = engine.executeWorkflow(workflow, incomingSmsContext, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals("938104", incomingSmsContext.getString("otp"))
            assertEquals("HDFC Alert: Security Code 938104", incomingSmsContext.getString("formatted_payload"))

            assertEquals(1, capturedRequests.size)
            val req = capturedRequests[0]
            assertEquals("https://ntfy.sh/my-bank-alerts", req.url.toString())
            assertEquals("HDFC OTP Alert", req.header("Title"))
            assertEquals("5", req.header("Priority"))
            assertEquals("bank", req.header("Tags"))
            assertEquals("HDFC Alert: Security Code 938104", capturedBodies[0])
        }

    @Test
    fun testNotificationToGotifyForwardingPipelineEndToEnd() =
        runTest {
            val capturedRequests = mutableListOf<Request>()
            val capturedBodies = mutableListOf<String>()

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedRequests.add(request)
                        val buffer = Buffer()
                        request.body?.writeTo(buffer)
                        capturedBodies.add(buffer.readUtf8())

                        Response
                            .Builder()
                            .request(request)
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

            val triggerNode =
                WorkflowNode(
                    id = "t_notif",
                    name = "App Notification Listener",
                    type = NodeType.NOTIFICATION_TRIGGER,
                    configJson = """{"packageFilter":"com.google.android.apps.messaging","textRegex":".*verification code.*"}""",
                )
            val parserNode =
                WorkflowNode(
                    id = "p_regex",
                    name = "Extract Code",
                    type = NodeType.REGEX_PARSER,
                    configJson = """{"regex":"\\b\\d{6}\\b","outputKey":"code"}""",
                )
            val gotifyNode =
                WorkflowNode(
                    id = "a_gotify",
                    name = "Gotify Push Action",
                    type = NodeType.GOTIFY_ACTION,
                    configJson = """{"serverUrl":"https://gotify.my-domain.com","appToken":"AppToken12345","title":"Google Verification Code","priority":5,"template":"Security Code: ${'$'}{code}"}""",
                )

            val workflow =
                Workflow(
                    id = "w_notif_to_gotify",
                    name = "Notification to Gotify Pipeline",
                    isEnabled = true,
                    nodes = listOf(triggerNode, parserNode, gotifyNode),
                    connections =
                        listOf(
                            WorkflowConnection("t_notif", "p_regex"),
                            WorkflowConnection("p_regex", "a_gotify"),
                        ),
                )

            val notifContext =
                ExecutionContext(
                    mapOf(
                        "package_name" to "com.google.android.apps.messaging",
                        "title" to "Google",
                        "text" to "Your Google verification code is 491028",
                        "body" to "Your Google verification code is 491028",
                    ),
                )

            val result = engine.executeWorkflow(workflow, notifContext, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals("491028", notifContext.getString("code"))

            assertEquals(1, capturedRequests.size)
            val req = capturedRequests[0]
            assertEquals("https://gotify.my-domain.com/message?token=AppToken12345", req.url.toString())

            val jsonObj = JSONObject(capturedBodies[0])
            assertEquals("Google Verification Code", jsonObj.optString("title"))
            assertEquals("Security Code: 491028", jsonObj.optString("message"))
            assertEquals(5, jsonObj.optInt("priority"))
        }

    @Test
    fun testMultiStepChainedPreconditionEvaluation() =
        runTest {
            val evaluator = PreconditionEvaluator(context = null)

            val triggerNode =
                WorkflowNode(
                    id = "ch_trig",
                    name = "Test Trigger",
                    type = NodeType.MANUAL_TRIGGER,
                    configJson = """{"testBody":"{\"status\":\"ONLINE\"}"}""",
                )
            val jsonParserNode =
                WorkflowNode(
                    id = "ch_json",
                    name = "JSON Status Extractor",
                    type = NodeType.JSON_PARSER,
                    configJson = """{"jsonKey":"status","outputKey":"server_status"}""",
                )
            val conditionCheckNode =
                WorkflowNode(
                    id = "ch_cond",
                    name = "Check Status Online",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"server_status","operator":"EQUALS","value":"ONLINE"}""",
                )

            val chainedCheckPrecondition =
                Precondition.ChainCheck(
                    id = "prec_chained_1",
                    isEnabled = true,
                    nodes = listOf(triggerNode, jsonParserNode, conditionCheckNode),
                    connections =
                        listOf(
                            WorkflowConnection("ch_trig", "ch_json"),
                            WorkflowConnection("ch_json", "ch_cond"),
                        ),
                )

            val result = evaluator.evaluate(chainedCheckPrecondition)
            assertTrue(result is PreconditionResult.Passed)
        }
}
