package dev.zophop.domain.engine.executors

import android.content.Context
import dev.zophop.data.CredentialRepository
import dev.zophop.data.DatabaseEncryption
import dev.zophop.domain.engine.HttpHeaderUtils
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optInt
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.ExecutionLogFormatter
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
        val config = parseJsonObjectSafe(node.configJson)

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
        val targetUrl = appendQueryParams(rawTargetUrl, queryParams, context)
        val parsedHttpUrl = targetUrl.toHttpUrlOrNull()
        when {
            rawTargetUrl.isBlank() -> NodeExecutionResult.Failed("HTTP URL is empty")
            parsedHttpUrl == null || (parsedHttpUrl.scheme != "https" && parsedHttpUrl.scheme != "http") ->
                NodeExecutionResult.Failed("Invalid HTTP/HTTPS URL")
            parsedHttpUrl.username.isNotEmpty() || parsedHttpUrl.password.isNotEmpty() ->
                NodeExecutionResult.Failed("Invalid HTTP/HTTPS URL: embedded credentials are not allowed")
            else ->
                executeHttpRequest(
                    config = config,
                    targetUrl = targetUrl,
                    parsedHttpUrl = parsedHttpUrl,
                    method = method,
                    expectedStatus = expectedStatus,
                    context = context,
                    resolvedCred = resolvedCred,
                )
        }
    }

    private suspend fun executeHttpRequest(
        config: JsonObject,
        targetUrl: String,
        parsedHttpUrl: okhttp3.HttpUrl,
        method: String,
        expectedStatus: Int,
        context: ExecutionContext,
        resolvedCred: Credential?,
    ): NodeExecutionResult {
        val safeUrl = redactSensitiveUrlParams(targetUrl)
        val bodyType = config.optString("bodyType", "JSON").uppercase()
        val interpolatedBody = context.interpolate(config.optString("bodyTemplate", ""))
        val request = buildRequest(config, targetUrl, method, bodyType, interpolatedBody, context, resolvedCred).build()
        val explicitSecrets = collectExplicitSecrets(config, parsedHttpUrl, request, context, resolvedCred)
        val reqHeadersBlock =
            ExecutionLogFormatter.formatHeadersBlock(
                (0 until request.headers.size).map { idx ->
                    request.headers.name(idx) to request.headers.value(idx)
                },
            )
        val startNs = System.nanoTime()

        return try {
            val response = okHttpClient.newCall(request).await()
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
            response.use { resp ->
                handleHttpResponse(
                    resp = resp,
                    method = method,
                    safeUrl = safeUrl,
                    expectedStatus = expectedStatus,
                    bodyType = bodyType,
                    requestHeadersBlock = reqHeadersBlock,
                    requestBody = if (method == "GET" || method == "HEAD") "" else interpolatedBody,
                    elapsedMs = elapsedMs,
                    explicitSecrets = explicitSecrets,
                    context = context,
                )
            }
        } catch (e: IOException) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
            NodeExecutionResult.Failed(
                buildFailureDetails(method, safeUrl, bodyType, reqHeadersBlock, elapsedMs, e, explicitSecrets),
            )
        } catch (e: IllegalArgumentException) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
            NodeExecutionResult.Failed(
                buildFailureDetails(method, safeUrl, bodyType, reqHeadersBlock, elapsedMs, e, explicitSecrets),
            )
        } catch (e: IllegalStateException) {
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
            NodeExecutionResult.Failed(
                buildFailureDetails(method, safeUrl, bodyType, reqHeadersBlock, elapsedMs, e, explicitSecrets),
            )
        }
    }

    private fun buildFailureDetails(
        method: String,
        safeUrl: String,
        bodyType: String,
        reqHeadersBlock: String,
        elapsedMs: Long,
        error: Throwable,
        explicitSecrets: List<String>,
    ): String {
        val rawErrMsg = error.message ?: "Request exception"
        val safeErrMsg = ExecutionLogFormatter.redactSecrets(rawErrMsg, explicitSecrets)
        val summary = "HTTP $method $safeUrl failed (${error::class.simpleName}): $safeErrMsg"
        val sections = mutableListOf<Pair<String, String>>()
        sections.add(
            "Request Details" to
                "Method: $method\nURL: $safeUrl\nBody Type: $bodyType\nElapsed: ${elapsedMs}ms",
        )
        if (reqHeadersBlock.isNotBlank()) {
            sections.add("Request Headers" to reqHeadersBlock)
        }
        return ExecutionLogFormatter.buildStructuredLog(summary, sections)
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
        config: JsonObject,
        targetUrl: String,
        method: String,
        bodyType: String,
        interpolatedBody: String,
        context: ExecutionContext,
        resolvedCred: Credential?,
    ): Request.Builder {
        val requestBuilder = Request.Builder().url(targetUrl)

        val requestBody = buildRequestBody(bodyType, interpolatedBody)
        applyMethod(requestBuilder, method, requestBody)

        if (resolvedCred != null) {
            applyCredentialToRequest(requestBuilder, resolvedCred, context)
        } else {
            applyInlineAuth(requestBuilder, config, context)
        }

        val headers = config.optString("headers", "")
        if (headers.isNotBlank()) {
            HttpHeaderUtils.parseHeaders(headers).forEach { (rawKey, rawVal) ->
                val interpolatedKey = HttpHeaderUtils.sanitizeHeaderValue(context.interpolate(rawKey))
                val interpolatedVal = HttpHeaderUtils.sanitizeHeaderValue(context.interpolate(rawVal))
                if (HttpHeaderUtils.isValidHeaderName(interpolatedKey) && interpolatedVal.isNotBlank()) {
                    requestBuilder.header(interpolatedKey, interpolatedVal)
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

    private fun applyInlineAuth(builder: Request.Builder, config: JsonObject, context: ExecutionContext) {
        val authType = config.optString("authType", "NONE").uppercase()
        when (authType) {
            "BEARER" -> {
                val token = HttpHeaderUtils.sanitizeHeaderValue(context.interpolate(config.optString("bearerToken", "")))
                if (token.isNotBlank()) builder.addHeader("Authorization", "Bearer $token")
            }
            "BASIC" -> {
                val user = HttpHeaderUtils.sanitizeHeaderValue(context.interpolate(config.optString("basicUser", "")))
                val pass = HttpHeaderUtils.sanitizeHeaderValue(context.interpolate(config.optString("basicPassword", "")))
                val authHeader = okhttp3.Credentials.basic(user, pass)
                builder.addHeader("Authorization", authHeader)
            }
        }
    }

    private fun handleHttpResponse(
        resp: Response,
        method: String,
        safeUrl: String,
        expectedStatus: Int,
        bodyType: String,
        requestHeadersBlock: String,
        requestBody: String,
        elapsedMs: Long,
        explicitSecrets: List<String>,
        context: ExecutionContext,
    ): NodeExecutionResult {
        val responseBody = resp.body?.string() ?: ""
        context.set("http_status", resp.code)
        context.set("http_response", responseBody)

        val responseHeadersBlock =
            ExecutionLogFormatter.formatHeadersBlock(
                (0 until resp.headers.size).map { idx ->
                    resp.headers.name(idx) to resp.headers.value(idx)
                },
            )
        val sections = mutableListOf<Pair<String, String>>()
        sections.add(
            "Request Details" to
                "Method: $method\nURL: $safeUrl\nBody Type: $bodyType\nExpected Status: $expectedStatus",
        )
        if (requestHeadersBlock.isNotBlank()) {
            sections.add("Request Headers" to requestHeadersBlock)
        }
        if (requestBody.isNotBlank()) {
            val redactedReqBody = ExecutionLogFormatter.redactSecrets(requestBody, explicitSecrets)
            sections.add("Request Body" to ExecutionLogFormatter.previewText(redactedReqBody, 2000))
        }
        sections.add(
            "Response Summary" to
                "Status: ${resp.code} ${resp.message}\nLatency: ${elapsedMs}ms\nBody Size: ${responseBody.length} chars\nVariables Set: http_status, http_response",
        )
        if (responseHeadersBlock.isNotBlank()) {
            sections.add("Headers" to responseHeadersBlock)
        }
        if (responseBody.isNotBlank()) {
            val redactedRespBody = ExecutionLogFormatter.redactSecrets(responseBody, explicitSecrets)
            sections.add("Response Body" to ExecutionLogFormatter.previewText(redactedRespBody, 8000))
        }

        val isSuccess = resp.code == expectedStatus || (expectedStatus == 200 && resp.isSuccessful)
        return if (isSuccess) {
            val summary = "HTTP $method $safeUrl -> ${resp.code} ${resp.message} (${elapsedMs}ms)"
            NodeExecutionResult.Success(logMessage = ExecutionLogFormatter.buildStructuredLog(summary, sections))
        } else {
            val summary = "HTTP request failed with status ${resp.code} (${resp.message}) for $method $safeUrl"
            NodeExecutionResult.Failed(ExecutionLogFormatter.buildStructuredLog(summary, sections))
        }
    }

    private fun collectExplicitSecrets(
        config: JsonObject,
        parsedHttpUrl: okhttp3.HttpUrl,
        request: Request,
        context: ExecutionContext,
        resolvedCred: Credential?,
    ): List<String> {
        val secrets = mutableListOf<String>()
        resolvedCred?.dataMap?.forEach { (k, v) ->
            if (DatabaseEncryption.isSensitiveKey(k) && v.isNotBlank()) {
                secrets.add(context.interpolate(v))
            }
        }
        val bearerToken = config.optString("bearerToken", "")
        if (bearerToken.isNotBlank()) secrets.add(context.interpolate(bearerToken))
        val basicPassword = config.optString("basicPassword", "")
        if (basicPassword.isNotBlank()) secrets.add(context.interpolate(basicPassword))

        for (i in 0 until request.headers.size) {
            val name = request.headers.name(i)
            val value = request.headers.value(i)
            if (ExecutionLogFormatter.isSensitiveHeader(name) && value.isNotBlank()) {
                secrets.add(value)
                secrets.add(value.removePrefix("Bearer ").removePrefix("Basic ").removePrefix("Token ").trim())
            }
        }
        for (name in parsedHttpUrl.queryParameterNames) {
            if (isSensitiveUrlParam(name)) {
                parsedHttpUrl.queryParameterValues(name).filterNotNull().forEach { secrets.add(it) }
            }
        }
        return secrets.filter { it.length >= 4 }.distinct()
    }

    private fun isSensitiveUrlParam(name: String): Boolean {
        val lower = name.lowercase()
        return lower in SENSITIVE_URL_PARAM_KEYS ||
            lower.endsWith("token") ||
            lower.endsWith("secret") ||
            lower.endsWith("password") ||
            lower.endsWith("apikey") ||
            lower.endsWith("api_key")
    }

    private fun redactSensitiveUrlParams(rawUrl: String): String {
        val preRedacted = ExecutionLogFormatter.redactUrl(rawUrl)
        val httpUrl = preRedacted.toHttpUrlOrNull()
        val sensitiveNames = httpUrl?.queryParameterNames?.filter { isSensitiveUrlParam(it) }.orEmpty()
        return if (httpUrl == null || sensitiveNames.isEmpty()) {
            preRedacted
        } else {
            val builder = httpUrl.newBuilder()
            for (name in sensitiveNames) {
                builder.removeAllQueryParameters(name)
                builder.addQueryParameter(name, "***")
            }
            builder.build().toString()
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
        val token = HttpHeaderUtils.sanitizeHeaderValue(context.interpolate(credential.dataMap["token"] ?: ""))
        if (token.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $token")
        }
    }

    private companion object {
        val SENSITIVE_URL_PARAM_KEYS =
            setOf(
                "token",
                "apptoken",
                "app_token",
                "bottoken",
                "bot_token",
                "access_token",
                "refresh_token",
                "apikey",
                "api_key",
                "secret",
                "client_secret",
                "password",
                "passwd",
                "pwd",
                "pass",
                "auth",
                "key",
                "signature",
                "sig",
                "webhook_secret",
            )
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
