package dev.zophop.domain.engine.executors

import android.content.Context
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.engine.HttpHeaderUtils
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.model.Credential
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

        val credentialId = config.optString("credentialId", "")
        val resolvedCred =
            if (credentialId.isNotBlank() && credentialRepository != null) {
                credentialRepository.getCredentialById(credentialId)
            } else {
                null
            }

        val adapter = resolvedCred?.let { BuiltInPluginRegistry.getHttpCredentialAdapter(it.type) }
        val configured = resolvedCred?.let { adapter?.configureHttpNode(it.dataMap, emptyMap()) }

        val rawUrl = config.optString("url", "")
        val effectiveRawUrl =
            if (rawUrl.isNotBlank()) {
                rawUrl
            } else if (resolvedCred != null) {
                configured?.get("url") ?: resolvedCred.dataMap["url"] ?: resolvedCred.dataMap["serverUrl"] ?: ""
            } else {
                ""
            }

        val rawMethod = config.optString("method", "").uppercase()
        val method =
            if (rawMethod.isNotBlank()) {
                rawMethod
            } else {
                configured?.get("method")?.uppercase() ?: "GET"
            }
        val queryParams = config.optString("queryParams", "")
        val expectedStatus = config.optInt("expectedStatus", 200)

        val rawTargetUrl = context.interpolate(effectiveRawUrl).trim()
        if (rawTargetUrl.isBlank()) {
            return@withContext NodeExecutionResult.Failed("HTTP URL is empty")
        }

        val targetUrl = appendQueryParams(rawTargetUrl, queryParams, context)

        try {
            val requestBuilder = buildRequest(config, targetUrl, method, context, resolvedCred)
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

    private fun appendQueryParams(
        targetUrl: String,
        queryParams: String,
        context: ExecutionContext,
    ): String {
        if (queryParams.isBlank()) return targetUrl
        val interpolatedQuery = context.interpolate(queryParams)
        val normalizedQuery =
            interpolatedQuery
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .flatMap { it.split("&") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { part ->
                    if (part.contains("=") || !part.contains(":")) {
                        part
                    } else {
                        part.substringBefore(':').trim() + "=" + part.substringAfter(':').trim()
                    }
                }
                .joinToString("&")
        if (normalizedQuery.isBlank()) return targetUrl
        return when {
            targetUrl.endsWith("?") || targetUrl.endsWith("&") -> "$targetUrl$normalizedQuery"
            targetUrl.contains("?") -> "$targetUrl&$normalizedQuery"
            else -> "$targetUrl?$normalizedQuery"
        }
    }

    private fun buildRequest(
        config: JSONObject,
        targetUrl: String,
        method: String,
        context: ExecutionContext,
        resolvedCred: Credential?,
    ): Request.Builder {
        val requestBuilder = Request.Builder().url(targetUrl)

        val bodyType = config.optString("bodyType", "JSON").uppercase()
        val bodyTemplate = config.optString("bodyTemplate", "")
        val interpolatedBody = context.interpolate(bodyTemplate)
        val requestBody = buildRequestBody(bodyType, interpolatedBody)
        applyMethod(requestBuilder, method, requestBody)

        if (resolvedCred != null) {
            applyCredentialToRequest(requestBuilder, resolvedCred, context)
        } else {
            applyInlineAuth(requestBuilder, config, context)
        }

        val headers = config.optString("headers", "")
        if (headers.isNotBlank()) {
            HttpHeaderUtils.parseHeaders(context.interpolate(headers)).forEach { (k, v) ->
                if (v.isNotBlank()) {
                    requestBuilder.header(k, v)
                }
            }
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
                val authHeader = okhttp3.Credentials.basic(user, pass)
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
        val adapter = BuiltInPluginRegistry.getHttpCredentialAdapter(credential.type)
        if (adapter != null) {
            adapter.applyToRequest(builder, credential.dataMap, context)
            return
        }
        val token = context.interpolate(credential.dataMap["token"] ?: "").trim()
        if (token.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $token")
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
