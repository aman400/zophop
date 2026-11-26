package dev.zophop.plugin.gotify

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class GotifyNodePluginServiceTest {
    @Test
    fun testGotifyPluginManifestStructure() {
        val serviceController = Robolectric.buildService(GotifyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService
        val manifests = binder.getManifests()

        assertEquals(1, manifests.size)
        val gotifyAction = manifests.first()

        assertEquals("dev.zophop.plugin.gotify.GOTIFY_ACTION", gotifyAction.typeId)
        assertEquals("GOTIFY_AUTH", gotifyAction.requiredCredentialType)
        assertTrue(gotifyAction.requiredPermissions.isEmpty())
        assertEquals(5, gotifyAction.configSchema.size)
    }

    @Test
    fun testGotifyActionFailsOnEmptyServerUrl() {
        val serviceController = Robolectric.buildService(GotifyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.gotify.GOTIFY_ACTION"}"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")

        val json = parseJsonObjectSafe(resultJson)
        assertEquals(false, json.optBoolean("isSuccess"))
        assertTrue(json.optString("errorReason").contains("serverUrl is empty"))
    }

    @Test
    fun testGotifyActionExecutor_sanitizesCrlfTokenAndRedactsTokenFromDetailedLog() =
        runTest {
            var capturedKeyHeader: String? = null
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        capturedKeyHeader = req.header("X-Gotify-Key")
                        Response
                            .Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"id":1}""".toResponseBody())
                            .build()
                    }.build()

            val executor = GotifyActionExecutor(okHttpClient = client)
            val node =
                WorkflowNode(
                    id = "g1",
                    name = "Gotify",
                    type = "dev.zophop.plugin.gotify.GOTIFY_ACTION",
                    configJson = """{"serverUrl":"https://gotify.example.com","appToken":"secret_tok_999\r\n","title":"Alert","template":"Hi"}""",
                )
            val result = executor.execute(node, ExecutionContext(), null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("secret_tok_999", capturedKeyHeader)
            val detailedLog = (result as NodeExecutionResult.Success).logMessage
            assertFalse("Gotify logMessage must NOT expose raw appToken", detailedLog.contains("secret_tok_999"))
        }
}
