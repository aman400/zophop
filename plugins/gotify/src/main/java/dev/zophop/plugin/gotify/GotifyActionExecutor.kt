package dev.zophop.plugin.gotify

import android.content.Context
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optInt
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
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
        val targetUrl = "${params.serverUrl}/message" + (if (params.appToken.isNotBlank()) "?token=${params.appToken}" else "")
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
            if (params.appToken.isNotBlank()) requestBuilder.addHeader("X-Gotify-Key", params.appToken)

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val detailedLog =
                        "Delivered to Gotify server (HTTP ${resp.code})\nURL: $targetUrl\nTitle: ${params.title}\nBody: ${params.payload}"
                    NodeExecutionResult.Success(outputPort = "main", logMessage = detailedLog)
                } else {
                    NodeExecutionResult.Failed("Gotify request failed: HTTP ${resp.code} - $respBody")
                }
            }
        } catch (e: IOException) {
            NodeExecutionResult.Failed("Gotify dispatch failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            NodeExecutionResult.Failed("Gotify dispatch failed: ${e.message}")
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
