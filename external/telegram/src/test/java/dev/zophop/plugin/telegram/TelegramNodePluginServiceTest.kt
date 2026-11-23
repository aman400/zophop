package dev.zophop.plugin.telegram

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class TelegramNodePluginServiceTest {
    @Test
    fun testTelegramPluginManifestStructure() {
        val serviceController = Robolectric.buildService(TelegramNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService
        val manifests = binder.getManifests()

        assertEquals(1, manifests.size)
        val telegramAction = manifests.first()

        assertEquals("dev.zophop.plugin.telegram.TELEGRAM_ACTION", telegramAction.typeId)
        assertEquals(3, telegramAction.configSchema.size)
    }

    @Test
    fun testTelegramExecutorCreation() {
        val executor = TelegramPluginModule.createExecutor("dev.zophop.plugin.telegram.TELEGRAM_ACTION", okhttp3.OkHttpClient(), null)
        assertNotNull(executor)
        assertTrue(executor is TelegramActionExecutor)
    }

    @Test
    fun testTelegramManifestCredentialSpec() {
        val manifests = TelegramPluginModule.getManifests()
        val manifest = manifests.first()
        assertEquals("TELEGRAM_AUTH", manifest.requiredCredentialType)
        assertNotNull(manifest.credentialSpec)
        assertEquals("TELEGRAM_AUTH", manifest.credentialSpec?.typeId)
    }

    @Test
    fun testTelegramExecutorExecutionSuccess() =
        runTest {
            var requestedUrl = ""
            var requestBody = ""

            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        requestedUrl = req.url.toString()
                        val buffer = okio.Buffer()
                        req.body?.writeTo(buffer)
                        requestBody = buffer.readUtf8()

                        Response
                            .Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"ok":true}""".toResponseBody())
                            .build()
                    }.build()

            val executor = TelegramActionExecutor(okHttpClient = client)
            val node =
                WorkflowNode(
                    id = "t_exec",
                    name = "Telegram",
                    type = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                    configJson = """{"botToken":"123456:ABC-DEF","chatId":"-100987654321","messageTemplate":"Alert: ${'$'}{body}"}""",
                )
            val context = ExecutionContext(mapOf("body" to "OTP is 445566"))

            val result = executor.execute(node, context, null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://api.telegram.org/bot123456:ABC-DEF/sendMessage", requestedUrl)
            assertTrue(requestBody.contains("-100987654321"))
            assertTrue(requestBody.contains("Alert: OTP is 445566"))
        }

    @Test
    fun testTelegramExecutorResolvesCredentialViaDataMap() =
        runTest {
            var requestedUrl = ""

            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requestedUrl = chain.request().url.toString()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"ok":true}""".toResponseBody())
                            .build()
                    }.build()

            val mockCredRepo =
                object {
                    @Suppress("unused")
                    fun getCredentialById(id: String): Any? {
                        if (id == "cred_data_map") {
                            return object {
                                @Suppress("unused")
                                fun getDataMap(): Map<String, String> = mapOf("token" to "resolved_token_999")
                            }
                        }
                        return null
                    }
                }

            val executor = TelegramActionExecutor(okHttpClient = client, credentialRepository = mockCredRepo)
            val node =
                WorkflowNode(
                    id = "t_cred",
                    name = "Telegram",
                    type = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                    configJson = """{"credentialId":"cred_data_map","chatId":"-100123","messageTemplate":"hello"}""",
                )
            val context = ExecutionContext(mapOf("body" to "test body"))

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://api.telegram.org/botresolved_token_999/sendMessage", requestedUrl)
        }

    @Test
    fun testTelegramExecutorResolvesTokenInjectedViaIpcContext() =
        runTest {
            var requestedUrl = ""

            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requestedUrl = chain.request().url.toString()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"ok":true}""".toResponseBody())
                            .build()
                    }.build()

            val executor = TelegramActionExecutor(okHttpClient = client)
            val node =
                WorkflowNode(
                    id = "t_ipc",
                    name = "Telegram",
                    type = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                    configJson = """{"chatId":"-100123","messageTemplate":"hello"}""",
                )
            // Simulated token injected by ExternalPluginExecutor across IPC
            val context =
                ExecutionContext(
                    mapOf(
                        "_cred_token" to "ipc_injected_bot_token",
                        "body" to "test body",
                    ),
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://api.telegram.org/botipc_injected_bot_token/sendMessage", requestedUrl)
        }

    @Test
    fun testTelegramExecutorFailsOnEmptyTokenOrChatId() =
        runTest {
            val executor = TelegramActionExecutor()
            val node =
                WorkflowNode(
                    id = "t_fail",
                    name = "Telegram",
                    type = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                    configJson = """{"botToken":"","chatId":""}""",
                )
            val context = ExecutionContext(mapOf("body" to "test"))
            val result = executor.execute(node, context, null)

            assertTrue(result is NodeExecutionResult.Failed)
            assertTrue((result as NodeExecutionResult.Failed).reason.contains("empty"))
        }

    @Test
    fun testTelegramExecutorInterpolatesChatIdFromContextAndOmitsDefaultMarkdownMode() =
        runTest {
            var requestBody = ""

            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        val buffer = okio.Buffer()
                        req.body?.writeTo(buffer)
                        requestBody = buffer.readUtf8()

                        Response
                            .Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"ok":true}""".toResponseBody())
                            .build()
                    }.build()

            val executor = TelegramActionExecutor(okHttpClient = client)
            val node =
                WorkflowNode(
                    id = "t_interp",
                    name = "Telegram",
                    type = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                    configJson = """{"botToken":"123:ABC","chatId":"${'$'}{dynamic_chat_id}","messageTemplate":"Hello ${'$'}{user_name}"}""",
                )
            val context =
                ExecutionContext(
                    mapOf(
                        "dynamic_chat_id" to "-100778899",
                        "user_name" to "Alice",
                    ),
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            val json = org.json.JSONObject(requestBody)
            assertEquals("-100778899", json.getString("chat_id"))
            assertEquals("Hello Alice", json.getString("text"))
            org.junit.Assert.assertFalse(json.has("parse_mode"))
        }
}
