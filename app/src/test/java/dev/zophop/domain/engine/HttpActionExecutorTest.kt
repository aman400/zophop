package dev.zophop.domain.engine

import dev.zophop.data.CredentialRepository
import dev.zophop.domain.engine.executors.HttpActionExecutor
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HttpActionExecutorTest {
    @Test
    fun testHttpPostExecutionWithBearerToken() =
        runTest {
            var capturedHeader: String? = null
            var capturedBody: String? = null

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedHeader = request.header("Authorization")
                        val buffer = okio.Buffer()
                        request.body?.writeTo(buffer)
                        capturedBody = buffer.readUtf8()

                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("{\"status\":\"success\"}".toResponseBody())
                            .build()
                    }.build()

            val executor = HttpActionExecutor(okHttpClient)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Webhook",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://example.com/api\",\"method\":\"POST\",\"authType\":\"BEARER\",\"bearerToken\":\"my-secret-token\",\"bodyType\":\"JSON\",\"bodyTemplate\":\"{\\\"sender\\\":\\\"\${sender}\\\"}\"}",
                )

            val context = ExecutionContext(mapOf("sender" to "ALERTS"))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("Bearer my-secret-token", capturedHeader)
            assertEquals("{\"sender\":\"ALERTS\"}", capturedBody)
            assertEquals("200", context.getString("http_status"))
            assertTrue(context.getString("http_response").contains("success"))
        }

    @Test
    fun testHttpExecutionWithCredentialStoreResolution() =
        runTest {
            var capturedHeader: String? = null

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedHeader = request.header("Authorization")
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val mockRepo =
                object : CredentialRepository {
                    override fun getAllCredentialsFlow(): Flow<List<Credential>> = flowOf(emptyList())

                    override suspend fun getCredentialById(id: String): Credential? =
                        if (id == "cred-123") {
                            Credential(
                                id = "cred-123",
                                name = "Mock Bearer Credential",
                                type = CredentialType.BEARER_TOKEN,
                                dataMap = mapOf("token" to "vault-secret-999"),
                            )
                        } else {
                            null
                        }

                    override suspend fun saveCredential(credential: Credential) {}

                    override suspend fun deleteCredential(id: String) {}
                }

            val executor = HttpActionExecutor(okHttpClient, mockRepo)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Node with Vault Credential",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://example.com/api\",\"method\":\"GET\",\"credentialId\":\"cred-123\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("Bearer vault-secret-999", capturedHeader)
        }
}
