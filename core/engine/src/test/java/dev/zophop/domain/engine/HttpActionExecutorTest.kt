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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class HttpActionExecutorTest {
    private fun createMockClient(
        statusCode: Int = 200,
        responseBody: String = "OK",
        headers: Map<String, String> = emptyMap(),
        onRequest: ((Request) -> Unit)? = null,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                onRequest?.invoke(request)
                val respBuilder =
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(statusCode)
                        .message("HTTP $statusCode")
                        .body(responseBody.toResponseBody("application/json".toMediaType()))
                headers.forEach { (k, v) -> respBuilder.addHeader(k, v) }
                respBuilder.build()
            }.build()

    private fun createMockRepo(vararg credentials: Credential): CredentialRepository {
        val credMap = credentials.associateBy { it.id }
        return object : CredentialRepository {
            override fun getAllCredentialsFlow(): Flow<List<Credential>> = flowOf(credentials.toList())

            override suspend fun getCredentialById(id: String): Credential? = credMap[id]

            override suspend fun saveCredential(credential: Credential) {}

            override suspend fun deleteCredential(id: String) {}
        }
    }

    private fun Request.readBodyUtf8(): String {
        val body = this.body ?: return ""
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    // --- 1. HTTP Methods Testing ---

    @Test
    fun testHttpPostExecutionWithBearerToken() =
        runTest {
            var capturedHeader: String? = null
            var capturedBody: String? = null

            val client =
                createMockClient(statusCode = 200, responseBody = "{\"status\":\"success\"}") { req ->
                    capturedHeader = req.header("Authorization")
                    capturedBody = req.readBodyUtf8()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Webhook",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/api\",\"method\":\"POST\",\"authType\":\"BEARER\",\"bearerToken\":\"my-secret-token\",\"bodyType\":\"JSON\",\"bodyTemplate\":\"{\\\"sender\\\":\\\"\${sender}\\\"}\"}",
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
    fun testHttpGet_emptyBodyAndUrlQuery() =
        runTest {
            var capturedMethod: String? = null
            var capturedUrl: String? = null
            var capturedBody: String? = null

            val client =
                createMockClient(statusCode = 200, responseBody = "{\"count\":5}") { req ->
                    capturedMethod = req.method
                    capturedUrl = req.url.toString()
                    capturedBody = req.readBodyUtf8()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP GET",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/items\",\"method\":\"GET\",\"queryParams\":\"limit=10&page=1\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("GET", capturedMethod)
            assertEquals("https://example.com/items?limit=10&page=1", capturedUrl)
            assertEquals("", capturedBody)
            assertEquals("200", context.getString("http_status"))
        }

    @Test
    fun testHttpPut_withJsonPayload() =
        runTest {
            var capturedMethod: String? = null
            var capturedBody: String? = null

            val client =
                createMockClient(statusCode = 200) { req ->
                    capturedMethod = req.method
                    capturedBody = req.readBodyUtf8()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP PUT",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/items/42\",\"method\":\"PUT\",\"bodyType\":\"JSON\",\"bodyTemplate\":\"{\\\"status\\\":\\\"updated\\\"}\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("PUT", capturedMethod)
            assertEquals("{\"status\":\"updated\"}", capturedBody)
        }

    @Test
    fun testHttpPatch_withJsonPayload() =
        runTest {
            var capturedMethod: String? = null
            var capturedBody: String? = null

            val client =
                createMockClient(statusCode = 200) { req ->
                    capturedMethod = req.method
                    capturedBody = req.readBodyUtf8()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP PATCH",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/items/42\",\"method\":\"PATCH\",\"bodyType\":\"JSON\",\"bodyTemplate\":\"{\\\"patchField\\\":\\\"partial\\\"}\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("PATCH", capturedMethod)
            assertEquals("{\"patchField\":\"partial\"}", capturedBody)
        }

    @Test
    fun testHttpDelete_withoutBody() =
        runTest {
            var capturedMethod: String? = null
            var capturedBodyLength: Long = 0

            val client =
                createMockClient(statusCode = 204, responseBody = "") { req ->
                    capturedMethod = req.method
                    capturedBodyLength = req.body?.contentLength() ?: 0
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP DELETE",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/items/42\",\"method\":\"DELETE\",\"bodyType\":\"NONE\",\"expectedStatus\":204}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("DELETE", capturedMethod)
            assertEquals(0L, capturedBodyLength)
            assertEquals("204", context.getString("http_status"))
        }

    @Test
    fun testHttpDelete_withJsonBody() =
        runTest {
            var capturedMethod: String? = null
            var capturedBody: String? = null

            val client =
                createMockClient(statusCode = 200) { req ->
                    capturedMethod = req.method
                    capturedBody = req.readBodyUtf8()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP DELETE with Body",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/items\",\"method\":\"DELETE\",\"bodyType\":\"JSON\",\"bodyTemplate\":\"{\\\"deleteIds\\\":[1,2,3]}\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("DELETE", capturedMethod)
            assertEquals("{\"deleteIds\":[1,2,3]}", capturedBody)
        }

    @Test
    fun testHttpHead_returnsHeadersAndCodeWithoutBody() =
        runTest {
            var capturedMethod: String? = null

            val client =
                createMockClient(
                    statusCode = 200,
                    responseBody = "",
                    headers = mapOf("ETag" to "\"123456\"", "X-Server-Time" to "1700000000"),
                ) { req ->
                    capturedMethod = req.method
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP HEAD",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://example.com/health\",\"method\":\"HEAD\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("HEAD", capturedMethod)
            assertEquals("200", context.getString("http_status"))
        }

    @Test
    fun testBlankMethod_defaultsToGetWhenNoCredential() =
        runTest {
            var capturedMethod: String? = null

            val client =
                createMockClient { req ->
                    capturedMethod = req.method
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Default Method",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://example.com/default-method\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("GET", capturedMethod)
        }

    @Test
    fun testExplicitGetMethod_preservedEvenWhenNtfyCredentialAttached() =
        runTest {
            var capturedMethod: String? = null
            var capturedUrl: String? = null

            val client =
                createMockClient { req ->
                    capturedMethod = req.method
                    capturedUrl = req.url.toString()
                }

            val mockRepo =
                createMockRepo(
                    Credential(
                        id = "cred-ntfy-get",
                        name = "Ntfy Poller",
                        type = CredentialType.NTFY_AUTH,
                        dataMap = mapOf("serverUrl" to "https://ntfy.sh", "topic" to "my-alerts"),
                    ),
                )

            val executor = HttpActionExecutor(client, mockRepo)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Poller",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"credentialId\":\"cred-ntfy-get\",\"method\":\"GET\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("GET", capturedMethod)
            assertEquals("https://ntfy.sh/my-alerts", capturedUrl)
        }

    // --- 2. Body Types & Payload Formatting ---

    @Test
    fun testHttpPayload_formData_withAmpersandAndNewlines() =
        runTest {
            var capturedContentType: String? = null
            var capturedBody: String? = null

            val client =
                createMockClient { req ->
                    capturedContentType = req.body?.contentType()?.toString()
                    capturedBody = req.readBodyUtf8()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Form Data",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/form\",\"method\":\"POST\",\"bodyType\":\"FORM_DATA\",\"bodyTemplate\":\"title=\${title}\\nmessage=\${msg}&channel=sms\"}",
                )

            val context = ExecutionContext(mapOf("title" to "Hello World", "msg" to "Test Alert"))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertNotNull(capturedBody)
            val body = capturedBody!!
            assertTrue(capturedContentType.orEmpty().contains("application/x-www-form-urlencoded"))
            assertTrue(body.contains("title=Hello+World") || body.contains("title=Hello%20World"))
            assertTrue(body.contains("message=Test+Alert") || body.contains("message=Test%20Alert"))
            assertTrue(body.contains("channel=sms"))
        }

    @Test
    fun testHttpPayload_raw_plainText() =
        runTest {
            var capturedContentType: String? = null
            var capturedBody: String? = null

            val client =
                createMockClient { req ->
                    capturedContentType = req.body?.contentType()?.toString()
                    capturedBody = req.readBodyUtf8()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP RAW",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/raw\",\"method\":\"POST\",\"bodyType\":\"RAW\",\"bodyTemplate\":\"Plain raw message payload for \${user}\"}",
                )

            val context = ExecutionContext(mapOf("user" to "Alice"))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertTrue(capturedContentType!!.contains("text/plain"))
            assertEquals("Plain raw message payload for Alice", capturedBody)
        }

    @Test
    fun testHttpPayload_none_emptyBodyForPost() =
        runTest {
            var capturedBodyLength: Long = 0

            val client =
                createMockClient { req ->
                    capturedBodyLength = req.body?.contentLength() ?: 0
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Post None Body",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://example.com/trigger\",\"method\":\"POST\",\"bodyType\":\"NONE\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals(0L, capturedBodyLength)
        }

    // --- 3. Query Parameters ---

    @Test
    fun testQueryParams_appendedToUrlWithExistingQuery() =
        runTest {
            var capturedUrl: String? = null

            val client =
                createMockClient { req ->
                    capturedUrl = req.url.toString()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Query Params with Existing",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/search?base=true\",\"queryParams\":\"query=\${searchTerm}&category=alerts\"}",
                )

            val context = ExecutionContext(mapOf("searchTerm" to "bank_otp"))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://example.com/search?base=true&query=bank_otp&category=alerts", capturedUrl)
        }

    @Test
    fun testQueryParams_multilineAndColonSeparated() =
        runTest {
            var capturedUrl: String? = null

            val client =
                createMockClient { req ->
                    capturedUrl = req.url.toString()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Multiline Query Params",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/api\",\"queryParams\":\"page: 1\\nlimit=20\\n\\napi_key: \${key}\"}",
                )

            val context = ExecutionContext(mapOf("key" to "secret123"))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://example.com/api?page=1&limit=20&api_key=secret123", capturedUrl)
        }

    // --- 4. Headers Handling ---

    @Test
    fun testHttpHeaders_multipleHeadersAndVariableInterpolation() =
        runTest {
            var capturedHeader1: String? = null
            var capturedHeader2: String? = null
            var capturedHeader3: String? = null

            val client =
                createMockClient { req ->
                    capturedHeader1 = req.header("X-App-Id")
                    capturedHeader2 = req.header("X-Trace-Id")
                    capturedHeader3 = req.header("Custom-Header")
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Multi Headers",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/api\",\"headers\":\"X-App-Id: zophop_core\\nX-Trace-Id: \${traceId}\\nCustom-Header: active_val\"}",
                )

            val context = ExecutionContext(mapOf("traceId" to "tr_abc_12345"))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("zophop_core", capturedHeader1)
            assertEquals("tr_abc_12345", capturedHeader2)
            assertEquals("active_val", capturedHeader3)
        }

    @Test
    fun testHttpHeaders_multiValuedHeadersWithCommas() =
        runTest {
            var capturedAccept: String? = null

            val client =
                createMockClient { req ->
                    capturedAccept = req.header("Accept")
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Accept Header",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/api\",\"headers\":\"Accept: application/json, text/plain, */*\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("application/json, text/plain, */*", capturedAccept)
        }

    // --- 5. Authentication & Credentials Resolution ---

    @Test
    fun testHttpExecutionWithCredentialStoreResolution() =
        runTest {
            var capturedHeader: String? = null

            val client =
                createMockClient { req ->
                    capturedHeader = req.header("Authorization")
                }

            val mockRepo =
                createMockRepo(
                    Credential(
                        id = "cred-123",
                        name = "Mock Bearer Credential",
                        type = CredentialType.BEARER_TOKEN,
                        dataMap = mapOf("token" to "vault-secret-999"),
                    ),
                )

            val executor = HttpActionExecutor(client, mockRepo)
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

    @Test
    fun testInlineAuth_basicAuth_computesBase64Header() =
        runTest {
            var capturedAuth: String? = null

            val client =
                createMockClient { req ->
                    capturedAuth = req.header("Authorization")
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Basic Auth",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"url\":\"https://example.com/api\",\"authType\":\"BASIC\",\"basicUser\":\"my_user\",\"basicPassword\":\"my_pass\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertNotNull(capturedAuth)
            assertTrue(capturedAuth!!.startsWith("Basic "))
            val encoded = capturedAuth!!.removePrefix("Basic ").trim()
            val decoded = String(java.util.Base64.getDecoder().decode(encoded))
            assertEquals("my_user:my_pass", decoded)
        }

    @Test
    fun testCredentialStore_basicAuth_computesBasicHeader() =
        runTest {
            var capturedAuth: String? = null

            val client =
                createMockClient { req ->
                    capturedAuth = req.header("Authorization")
                }

            val mockRepo =
                createMockRepo(
                    Credential(
                        id = "cred-basic",
                        name = "Basic Vault Auth",
                        type = CredentialType.BASIC_AUTH,
                        dataMap = mapOf("username" to "vault_admin", "password" to "vault_pw_99"),
                    ),
                )

            val executor = HttpActionExecutor(client, mockRepo)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Node Basic Credential",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://example.com/api\",\"credentialId\":\"cred-basic\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertNotNull(capturedAuth)
            assertTrue(capturedAuth!!.startsWith("Basic "))
            val encoded = capturedAuth!!.removePrefix("Basic ").trim()
            val decoded = String(java.util.Base64.getDecoder().decode(encoded))
            assertEquals("vault_admin:vault_pw_99", decoded)
        }

    @Test
    fun testCredentialStore_gotifyAuth_injectsXGotifyKeyAndDefaultsUrl() =
        runTest {
            var capturedUrl: String? = null
            var capturedMethod: String? = null
            var capturedKey: String? = null

            val client =
                createMockClient { req ->
                    capturedUrl = req.url.toString()
                    capturedMethod = req.method
                    capturedKey = req.header("X-Gotify-Key")
                }

            val mockRepo =
                createMockRepo(
                    Credential(
                        id = "cred-gotify",
                        name = "Gotify Server",
                        type = CredentialType.GOTIFY_AUTH,
                        dataMap = mapOf("serverUrl" to "https://gotify.corp.lan", "appToken" to "A999XYZ123"),
                    ),
                )

            val executor = HttpActionExecutor(client, mockRepo)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "Gotify Push",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"credentialId\":\"cred-gotify\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://gotify.corp.lan/message", capturedUrl)
            assertEquals("POST", capturedMethod)
            assertEquals("A999XYZ123", capturedKey)
        }

    @Test
    fun testCredentialStore_headerSet_injectsMultipleVaultHeaders() =
        runTest {
            var capturedHeaderA: String? = null
            var capturedHeaderB: String? = null

            val client =
                createMockClient { req ->
                    capturedHeaderA = req.header("X-Api-Key")
                    capturedHeaderB = req.header("X-Tenant-Id")
                }

            val mockRepo =
                createMockRepo(
                    Credential(
                        id = "cred-headers",
                        name = "Custom Header Set",
                        type = CredentialType.HEADER_SET,
                        dataMap = mapOf("headers" to "X-Api-Key: key_12345\nX-Tenant-Id: org_001"),
                    ),
                )

            val executor = HttpActionExecutor(client, mockRepo)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Header Set",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://example.com/api\",\"credentialId\":\"cred-headers\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("key_12345", capturedHeaderA)
            assertEquals("org_001", capturedHeaderB)
        }

    @Test
    fun testCredentialStore_genericCredential_defaultsUrl() =
        runTest {
            var capturedUrl: String? = null

            val client =
                createMockClient { req ->
                    capturedUrl = req.url.toString()
                }

            val mockRepo =
                createMockRepo(
                    Credential(
                        id = "cred-generic",
                        name = "Generic Server",
                        type = "GENERIC_WEBHOOK",
                        dataMap = mapOf("url" to "https://webhook.generic.org/hook"),
                    ),
                )

            val executor = HttpActionExecutor(client, mockRepo)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "Generic Webhook",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"credentialId\":\"cred-generic\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://webhook.generic.org/hook", capturedUrl)
        }

    @Test
    fun testHttpExecutionWithNtfyCredential_defaultsUrlMethodAndToken() =
        runTest {
            var capturedUrl: String? = null
            var capturedMethod: String? = null
            var capturedAuth: String? = null

            val client =
                createMockClient(statusCode = 200, responseBody = "{\"success\":true}") { req ->
                    capturedUrl = req.url.toString()
                    capturedMethod = req.method
                    capturedAuth = req.header("Authorization")
                }

            val mockRepo =
                createMockRepo(
                    Credential(
                        id = "cred-ntfy-auto",
                        name = "Ntfy Credential",
                        type = CredentialType.NTFY_AUTH,
                        dataMap =
                            mapOf(
                                "serverUrl" to "https://ntfy.example.com",
                                "topic" to "my_channel",
                                "token" to "ntfy-token-xyz",
                            ),
                    ),
                )

            val executor = HttpActionExecutor(client, mockRepo)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Node with NTFY Credential",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"credentialId\":\"cred-ntfy-auto\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://ntfy.example.com/my_channel", capturedUrl)
            assertEquals("POST", capturedMethod)
            assertEquals("Bearer ntfy-token-xyz", capturedAuth)
        }

    @Test
    fun testHttpExecutionWithNtfyCredential_userCanOverrideUrlAndHeaders() =
        runTest {
            var capturedUrl: String? = null
            var capturedAuth: String? = null
            var capturedCustomHeader: String? = null

            val client =
                createMockClient { req ->
                    capturedUrl = req.url.toString()
                    capturedAuth = req.header("Authorization")
                    capturedCustomHeader = req.header("X-Custom-Tag")
                }

            val mockRepo =
                createMockRepo(
                    Credential(
                        id = "cred-ntfy-auto",
                        name = "Ntfy Credential",
                        type = CredentialType.NTFY_AUTH,
                        dataMap =
                            mapOf(
                                "serverUrl" to "https://ntfy.example.com",
                                "topic" to "default_topic",
                                "token" to "cred-default-token",
                            ),
                    ),
                )

            val executor = HttpActionExecutor(client, mockRepo)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Node with Overrides",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"credentialId\":\"cred-ntfy-auto\",\"url\":\"https://ntfy.example.com/override_topic\",\"headers\":\"Authorization: Bearer custom-user-token\\nX-Custom-Tag: Urgent\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://ntfy.example.com/override_topic", capturedUrl)
            assertEquals("Bearer custom-user-token", capturedAuth)
            assertEquals("Urgent", capturedCustomHeader)
        }

    @Test
    fun testHttpActionExecutor_blankHeaderValueDoesNotOverrideCredentialSecret() =
        runTest {
            var capturedAuth: String? = null

            val client =
                createMockClient { req ->
                    capturedAuth = req.header("Authorization")
                }

            val mockRepo =
                createMockRepo(
                    Credential(
                        id = "cred-ntfy-auto",
                        name = "Ntfy Credential",
                        type = CredentialType.NTFY_AUTH,
                        dataMap =
                            mapOf(
                                "serverUrl" to "https://ntfy.example.com",
                                "topic" to "default_topic",
                                "token" to "cred-default-token",
                            ),
                    ),
                )

            val executor = HttpActionExecutor(client, mockRepo)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Node without Header Override",
                    type = NodeType.HTTP_ACTION,
                    configJson =
                        "{\"credentialId\":\"cred-ntfy-auto\",\"url\":\"https://ntfy.example.com/topic\",\"headers\":\"Authorization: \"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("Bearer cred-default-token", capturedAuth)
        }

    // --- 6. URL Handling & Failure Modes ---

    @Test
    fun testHttpUrl_emptyUrlFailsGracefully() =
        runTest {
            val client = createMockClient()
            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Empty URL",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Failed)
            assertTrue((result as NodeExecutionResult.Failed).reason.contains("HTTP URL is empty"))
        }

    @Test
    fun testHttpUrl_variableInterpolationInUrl() =
        runTest {
            var capturedUrl: String? = null

            val client =
                createMockClient { req ->
                    capturedUrl = req.url.toString()
                }

            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Interpolated URL",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://\${host}/api/\${version}/status\"}",
                )

            val context = ExecutionContext(mapOf("host" to "api.example.com", "version" to "v2"))
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("https://api.example.com/api/v2/status", capturedUrl)
        }

    @Test
    fun testHttpExecution_networkIOExceptionFailsGracefully() =
        runTest {
            val failingClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor {
                        throw IOException("Connection reset by peer")
                    }.build()

            val executor = HttpActionExecutor(failingClient)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Network Failure",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://unreachable.corp/api\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Failed)
            assertTrue((result as NodeExecutionResult.Failed).reason.contains("Connection reset by peer"))
        }

    @Test
    fun testHttpExecution_malformedUrlFailsGracefully() =
        runTest {
            val client = createMockClient()
            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP Malformed URL",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"httpx://not a valid url!\"}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Failed)
        }

    // --- 7. Status Codes & Context Outputs ---

    @Test
    fun testHttpStatus_expectedStatusMatchesNon200ReturnsSuccess() =
        runTest {
            val client = createMockClient(statusCode = 201, responseBody = "{\"id\":101}")
            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP 201 Created",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://example.com/items\",\"method\":\"POST\",\"expectedStatus\":201}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("201", context.getString("http_status"))
            assertEquals("{\"id\":101}", context.getString("http_response"))
        }

    @Test
    fun testHttpStatus_unsuccessfulStatusMismatchReturnsFailed() =
        runTest {
            val client = createMockClient(statusCode = 500, responseBody = "Internal Server Error")
            val executor = HttpActionExecutor(client)
            val node =
                WorkflowNode(
                    id = UUID.randomUUID().toString(),
                    name = "HTTP 500 Failure",
                    type = NodeType.HTTP_ACTION,
                    configJson = "{\"url\":\"https://example.com/api\",\"expectedStatus\":200}",
                )

            val context = ExecutionContext()
            val result = executor.execute(node, context, appContext = null)

            assertTrue(result is NodeExecutionResult.Failed)
            assertTrue((result as NodeExecutionResult.Failed).reason.contains("HTTP request failed with status 500"))
            assertEquals("500", context.getString("http_status"))
            assertEquals("Internal Server Error", context.getString("http_response"))
        }
}
