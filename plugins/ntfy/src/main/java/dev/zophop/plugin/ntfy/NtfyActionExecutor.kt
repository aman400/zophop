package dev.zophop.plugin.ntfy

import android.content.Context
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.ExecutionLogFormatter
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Preset action executor for [NodeType.NTFY_ACTION].
 * Formats ntfy HTTP push requests with title, priority, tags, and credential resolution.
 */
class NtfyActionExecutor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: Any? = null,
) : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val params = resolveNtfyParams(node, context, credentialRepository)
        if (params.serverUrl.isBlank() || params.topic.isBlank()) {
            return NodeExecutionResult.Failed("Ntfy serverUrl or topic is empty")
        }
        return sendNtfyMessage(params)
    }

    private fun sendNtfyMessage(params: NtfyParams): NodeExecutionResult {
        val targetUrl = "${params.serverUrl}/${params.topic}"
        val authMode = if (params.token.isNotBlank()) "Bearer ***" else "None"

        return try {
            val requestBuilder =
                Request
                    .Builder()
                    .url(targetUrl)
                    .post(params.payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))

            if (params.title.isNotBlank()) requestBuilder.addHeader("Title", toSafeHeaderValue(params.title))
            if (params.priority.isNotBlank()) requestBuilder.addHeader("Priority", sanitizeControlChars(params.priority))
            if (params.tags.isNotBlank()) requestBuilder.addHeader("Tags", toSafeHeaderValue(params.tags))
            if (params.token.isNotBlank()) requestBuilder.addHeader("Authorization", "Bearer ${sanitizeControlChars(params.token)}")

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp -> buildNtfyResponseResult(resp, targetUrl, authMode, params) }
        } catch (e: IOException) {
            NodeExecutionResult.Failed("ntfy dispatch to $targetUrl failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            NodeExecutionResult.Failed("ntfy dispatch to $targetUrl failed: ${e.message}")
        }
    }

    private fun buildNtfyResponseResult(
        resp: okhttp3.Response,
        targetUrl: String,
        authMode: String,
        params: NtfyParams,
    ): NodeExecutionResult {
        val respBody = resp.body?.string() ?: ""
        val details =
            "URL: $targetUrl\nTopic: ${params.topic}\nTitle: ${params.title}\n" +
                "Priority: ${params.priority}\nTags: ${params.tags}\nAuth: $authMode\nStatus: ${resp.code} ${resp.message}"
        return if (resp.isSuccessful) {
            val sections = mutableListOf(
                "ntfy Request Details" to details,
                "Body" to ExecutionLogFormatter.previewText(params.payload, 1000),
            )
            if (respBody.isNotBlank()) {
                sections.add("Response Body" to ExecutionLogFormatter.previewText(respBody, 1000))
            }
            val detailedLog =
                ExecutionLogFormatter.buildStructuredLog(
                    summary = "Delivered to ntfy topic '${params.topic}' (HTTP ${resp.code})",
                    sections = sections,
                )
            NodeExecutionResult.Success(outputPort = "main", logMessage = detailedLog)
        } else {
            val failedLog =
                ExecutionLogFormatter.buildStructuredLog(
                    summary = "ntfy request failed: HTTP ${resp.code} ${resp.message}",
                    sections =
                        listOfNotNull(
                            "ntfy Request Details" to details,
                            respBody.takeIf { it.isNotBlank() }?.let { "Response Body" to ExecutionLogFormatter.previewText(it, 1000) },
                        ),
                )
            NodeExecutionResult.Failed(failedLog)
        }
    }

    private fun sanitizeControlChars(value: String): String =
        value.replace(Regex("[\\r\\n\\u0000]"), "").trim()

    private fun toSafeHeaderValue(value: String): String {
        val cleaned = sanitizeControlChars(value)
        return if (cleaned.any { it.code !in 32..126 }) {
            "=?utf-8?B?" + android.util.Base64.encodeToString(cleaned.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) + "?="
        } else {
            cleaned
        }
    }
}

private data class NtfyParams(
    val serverUrl: String,
    val topic: String,
    val token: String,
    val title: String,
    val priority: String,
    val tags: String,
    val payload: String,
)

private suspend fun resolveNtfyParams(
    node: WorkflowNode,
    context: ExecutionContext,
    credentialRepository: Any?,
): NtfyParams {
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
    val credTopic = resolvedCred?.dataMap?.get("topic") ?: ""
    val credToken = resolvedCred?.dataMap?.get("token") ?: ""

    val serverUrl = context.interpolate(config.optString("serverUrl", "").ifBlank { credServerUrl }).trim().trimEnd('/')
    val topic = context.interpolate(config.optString("topic", "").ifBlank { credTopic }).trim()
    val token = context.interpolate(config.optString("token", "").ifBlank { credToken }).trim()
    val title = context.interpolate(config.optString("title", "ZopHop Notification")).trim()
    val priority = config.optString("priority", "3")
    val tags = config.optString("tags", "otp,sms")
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

    return NtfyParams(serverUrl, topic, token, title, priority, tags, payload)
}
