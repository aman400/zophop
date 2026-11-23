package dev.zophop.domain.engine.executors

import android.content.Context
import android.util.Base64
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.engine.HttpHeaderUtils
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Strategy executor for [NodeType.HTTP_ACTION].
 * Dispatches REST HTTP webhook requests (GET, POST, PUT, PATCH, DELETE, HEAD) with dynamic template substitution,
 * query parameter appending, header set injection, and credential resolution.
 */
class HttpActionExecutor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository? = null,
) : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult = withContext(Dispatchers.IO) {
        val config = try {
            JSONObject(node.configJson)
        } catch (_: JSONException) {
            JSONObject()
        }

        val rawUrl = config.optString("url", "")
        val method = config.optString("method", "GET").uppercase()
        val queryParams = config.optString("queryParams", "")
        val expectedStatus = config.optInt("expectedStatus", 200)

        var targetUrl = context.interpolate(rawUrl).trim()
        if (targetUrl.isBlank()) {
            return@withContext NodeExecutionResult.Failed("HTTP URL is empty")
        }

        if (queryParams.isNotBlank()) {
            val interpolatedQuery = context.interpolate(queryParams)
            targetUrl = if (targetUrl.contains("?")) "$targetUrl&$interpolatedQuery" else "$targetUrl?$interpolatedQuery"
        }

        try {
            val requestBuilder = buildRequest(config, targetUrl, method, context)
            val response = okHttpClient.newCall(requestBuilder.build()).await()
            response.use { resp ->
                handleHttpResponse(resp, method, targetUrl, expectedStatus, context)
            }
        } catch (e: IOException) {
            NodeExecutionResult.Failed("HTTP request exception: ${e.message}")
        } catch (e: IllegalArgumentException) {
            NodeExecutionResult.Failed("HTTP request exception: ${e.message}")
        } catch (e: IllegalStateException) {
            NodeExecutionResult.Failed("HTTP request exception: ${e.message}")
        }
    }

    private suspend fun buildRequest(
        config: JSONObject,
        targetUrl: String,
        method: String,
        context: ExecutionContext,
    ): Request.Builder {
        val requestBuilder = Request.Builder().url(targetUrl)

        val bodyType = config.optString("bodyType", "JSON").uppercase()
        val bodyTemplate = config.optString("bodyTemplate", "")
        val interpolatedBody = context.interpolate(bodyTemplate)
        val requestBody = buildRequestBody(bodyType, interpolatedBody)
        applyMethod(requestBuilder, method, requestBody)

        val headers = config.optString("headers", "")
        if (headers.isNotBlank()) {
            HttpHeaderUtils.parseHeaders(context.interpolate(headers)).forEach { (k, v) ->
                requestBuilder.addHeader(k, v)
            }
        }

        val credentialId = config.optString("credentialId", "")
        val resolvedCred = if (credentialId.isNotBlank() && credentialRepository != null) {
            credentialRepository.getCredentialById(credentialId)
        } else {
            null
        }

        if (resolvedCred != null) {
            applyCredentialToRequest(requestBuilder, resolvedCred, context)
        } else {
            applyInlineAuth(requestBuilder, config, context)
        }

        return requestBuilder
    }

    private fun buildRequestBody(bodyType: String, interpolatedBody: String): RequestBody? = when (bodyType) {
        "NONE" -> null
        "FORM_DATA" -> {
            val formBuilder = FormBody.Builder()
            interpolatedBody.split("\n", "&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    formBuilder.add(parts[0].trim(), parts[1].trim())
                }
            }
            formBuilder.build()
        }
        "RAW" -> interpolatedBody.toRequestBody("text/plain; charset=utf-8".toMediaType())
        else -> interpolatedBody.toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    private fun applyMethod(builder: Request.Builder, method: String, requestBody: RequestBody?) {
        val emptyBody = ByteArray(0).toRequestBody(null)
        when (method) {
            "POST" -> builder.post(requestBody ?: emptyBody)
            "PUT" -> builder.put(requestBody ?: emptyBody)
            "PATCH" -> builder.patch(requestBody ?: emptyBody)
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            "HEAD" -> builder.head()
            else -> builder.get()
        }
    }

    private fun applyInlineAuth(builder: Request.Builder, config: JSONObject, context: ExecutionContext) {
        val authType = config.optString("authType", "NONE").uppercase()
        when (authType) {
            "BEARER" -> {
                val token = context.interpolate(config.optString("bearerToken", "")).trim()
                if (token.isNotBlank()) builder.addHeader("Authorization", "Bearer $token")
            }
            "BASIC" -> {
                val user = context.interpolate(config.optString("basicUser", "")).trim()
                val pass = context.interpolate(config.optString("basicPassword", "")).trim()
                val credentials = "$user:$pass"
                val authHeader = "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
                builder.addHeader("Authorization", authHeader)
            }
        }
    }

    private fun handleHttpResponse(
        resp: Response,
        method: String,
        targetUrl: String,
        expectedStatus: Int,
        context: ExecutionContext,
    ): NodeExecutionResult {
        val responseBody = resp.body?.string() ?: ""
        context.set("http_status", resp.code)
        context.set("http_response", responseBody)

        val responseHeaders = resp.headers.toString().trim()
        val detailedLog = buildString {
            appendLine("HTTP $method $targetUrl")
            appendLine("Status: ${resp.code} ${resp.message}")
            if (responseHeaders.isNotBlank()) {
                appendLine("--- Headers ---")
                appendLine(responseHeaders)
            }
            if (responseBody.isNotBlank()) {
                appendLine("--- Response Body ---")
                appendLine(responseBody)
            }
        }

        return if (resp.code == expectedStatus || (expectedStatus == 200 && resp.isSuccessful)) {
            NodeExecutionResult.Success(logMessage = detailedLog)
        } else {
            NodeExecutionResult.Failed("HTTP request failed with status ${resp.code}\n\n$detailedLog")
        }
    }

    private fun applyCredentialToRequest(
        builder: Request.Builder,
        credential: Credential,
        context: ExecutionContext,
    ) {
        val data = credential.dataMap
        when (credential.type) {
            CredentialType.BEARER_TOKEN, CredentialType.NTFY_AUTH -> {
                val token = context.interpolate(data["token"] ?: "").trim()
                if (token.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer $token")
                }
            }
            CredentialType.BASIC_AUTH -> {
                val user = context.interpolate(data["username"] ?: "").trim()
                val pass = context.interpolate(data["password"] ?: "").trim()
                val authHeader = okhttp3.Credentials.basic(user, pass)
                builder.addHeader("Authorization", authHeader)
            }
            CredentialType.HEADER_SET -> {
                val headers = data["headers"] ?: ""
                HttpHeaderUtils.parseHeaders(context.interpolate(headers)).forEach { (k, v) ->
                    builder.addHeader(k, v)
                }
            }
            CredentialType.GOTIFY_AUTH -> {
                val appToken = context.interpolate(data["appToken"] ?: "").trim()
                if (appToken.isNotBlank()) {
                    builder.addHeader("X-Gotify-Key", appToken)
                }
            }
        }
    }
}

suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }
        try {
            val response = execute()
            continuation.resume(response)
        } catch (e: IOException) {
            if (!continuation.isCancelled) {
                continuation.resumeWithException(e)
            }
        }
    }
