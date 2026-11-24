package dev.zophop.plugin.ntfy

import android.content.Context
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
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

        return try {
            val requestBuilder =
                Request
                    .Builder()
                    .url(targetUrl)
                    .post(params.payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))

            if (params.title.isNotBlank()) requestBuilder.addHeader("Title", toSafeHeaderValue(params.title))
            if (params.priority.isNotBlank()) requestBuilder.addHeader("Priority", params.priority)
            if (params.tags.isNotBlank()) requestBuilder.addHeader("Tags", toSafeHeaderValue(params.tags))
            if (params.token.isNotBlank()) requestBuilder.addHeader("Authorization", "Bearer ${params.token}")

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val detailedLog =
                        "Delivered to ntfy topic '${params.topic}' (HTTP ${resp.code})\nURL: $targetUrl\nTitle: ${params.title}\nBody: ${params.payload}"
                    NodeExecutionResult.Success(outputPort = "main", logMessage = detailedLog)
                } else {
                    NodeExecutionResult.Failed("ntfy request failed: HTTP ${resp.code} - $respBody")
                }
            }
        } catch (e: IOException) {
            NodeExecutionResult.Failed("ntfy dispatch failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            NodeExecutionResult.Failed("ntfy dispatch failed: ${e.message}")
        }
    }

    private fun toSafeHeaderValue(value: String): String =
        if (value.any { it.code !in 32..126 }) {
            "=?utf-8?B?" + android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) + "?="
        } else {
            value
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
