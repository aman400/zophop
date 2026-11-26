package dev.zophop.plugin.gotify

import android.content.Context
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optInt
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.ExecutionLogFormatter
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Preset action executor for [NodeType.GOTIFY_ACTION].
 * Formats Gotify HTTP push messages with title, priority, and app tokens.
 */
class GotifyActionExecutor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: Any? = null,
) : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val params = resolveGotifyParams(node, context, credentialRepository)
        if (params.serverUrl.isBlank()) {
            return NodeExecutionResult.Failed("Gotify serverUrl is empty")
        }
        return sendGotifyMessage(params)
    }

    private fun sendGotifyMessage(params: GotifyParams): NodeExecutionResult {
        val cleanToken = params.appToken.replace(Regex("[\\r\\n\\u0000]"), "").trim()
        val encodedToken = java.net.URLEncoder.encode(cleanToken, Charsets.UTF_8.name())
        val baseMessageUrl = "${params.serverUrl}/message"
        val targetUrl = if (cleanToken.isNotBlank()) "$baseMessageUrl?token=$encodedToken" else baseMessageUrl
        val authMode = if (cleanToken.isNotBlank()) "X-Gotify-Key ***" else "None"
        val jsonBody =
            buildJsonObject {
                put("title", params.title)
                put("message", params.payload)
                put("priority", params.priority)
            }.toString()

        return try {
            val requestBuilder =
                Request
                    .Builder()
                    .url(targetUrl)
                    .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (cleanToken.isNotBlank()) requestBuilder.addHeader("X-Gotify-Key", cleanToken)

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp -> buildGotifyResponseResult(resp, baseMessageUrl, cleanToken, authMode, params) }
        } catch (e: IOException) {
            val msg = e.message?.let { m -> if (cleanToken.isNotBlank()) m.replace(cleanToken, "***") else m }
            NodeExecutionResult.Failed("Gotify dispatch to $baseMessageUrl failed: $msg")
        } catch (e: IllegalArgumentException) {
            val msg = e.message?.let { m -> if (cleanToken.isNotBlank()) m.replace(cleanToken, "***") else m }
            NodeExecutionResult.Failed("Gotify dispatch to $baseMessageUrl failed: $msg")
        }
    }

    private fun buildGotifyResponseResult(
        resp: okhttp3.Response,
        baseMessageUrl: String,
        cleanToken: String,
        authMode: String,
        params: GotifyParams,
    ): NodeExecutionResult {
        val respBody = resp.body?.string() ?: ""
        val details =
            "URL: $baseMessageUrl\nTitle: ${params.title}\nPriority: ${params.priority}\nAuth: $authMode\nStatus: ${resp.code} ${resp.message}"
        return if (resp.isSuccessful) {
            val sections = mutableListOf(
                "Gotify Request Details" to details,
                "Message Body" to ExecutionLogFormatter.previewText(params.payload, 1000),
            )
            if (respBody.isNotBlank()) {
                sections.add("Response Body" to ExecutionLogFormatter.previewText(respBody, 1000))
            }
            val detailedLog =
                ExecutionLogFormatter.buildStructuredLog(
                    summary = "Delivered to Gotify server (HTTP ${resp.code})",
                    sections = sections,
                )
            NodeExecutionResult.Success(outputPort = "main", logMessage = detailedLog)
        } else {
            val safeResp = if (cleanToken.isNotBlank()) respBody.replace(cleanToken, "***") else respBody
            val failedLog =
                ExecutionLogFormatter.buildStructuredLog(
                    summary = "Gotify request failed: HTTP ${resp.code} ${resp.message}",
                    sections =
                        listOfNotNull(
                            "Gotify Request Details" to details,
                            safeResp.takeIf { it.isNotBlank() }?.let { "Response Body" to ExecutionLogFormatter.previewText(it, 1000) },
                        ),
                )
            NodeExecutionResult.Failed(failedLog)
        }
    }
}

private data class GotifyParams(
    val serverUrl: String,
    val appToken: String,
    val title: String,
    val priority: Int,
    val payload: String,
)

private suspend fun resolveGotifyParams(
    node: WorkflowNode,
    context: ExecutionContext,
    credentialRepository: Any?,
): GotifyParams {
    val credRepo = credentialRepository as? CredentialRepository
    val config = parseJsonObjectSafe(node.configJson)
    val credentialId = config.optString("credentialId", "")
    val resolvedCred =
        if (credentialId.isNotBlank() && credRepo != null) {
            credRepo.getCredentialById(credentialId)
        } else {
            null
        }

    val credServerUrl = resolvedCred?.dataMap?.get("serverUrl") ?: ""
    val credAppToken = resolvedCred?.dataMap?.get("appToken") ?: ""

    val serverUrl = context.interpolate(config.optString("serverUrl", "").ifBlank { credServerUrl }).trim().trimEnd('/')
    val appToken = context.interpolate(config.optString("appToken", "").ifBlank { credAppToken }).trim()
    val title = context.interpolate(config.optString("title", "ZopHop Notification")).trim()
    val priority = config.optInt("priority", 5)
    val customTemplate = config.optString("template", "")

    val payload =
        if (customTemplate.isNotBlank()) {
            context.interpolate(customTemplate)
        } else {
            context.getString("formatted_payload", "").ifEmpty {
                context.getString("payload", "").ifEmpty {
                    context.getString("body", "")
                }
            }
        }

    return GotifyParams(serverUrl, appToken, title, priority, payload)
}
