package dev.zophop.plugin.ntfy

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.CredentialSpec
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodeExecutor
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object NtfyPluginModule : NodePluginModule {
    override val moduleId: String = "ntfy-plugin-module"

    override fun validateConfig(
        typeId: String,
        configJson: String,
    ): String? {
        if (!typeId.contains("NTFY", ignoreCase = true)) return null
        val config = parseJsonConfig(configJson)
        val serverUrl = config.optString("serverUrl", "").trim()
        val topic = config.optString("topic", "").trim()
        val credentialId = config.optString("credentialId", "").trim()
        val invalidUrl =
            serverUrl.isNotBlank() &&
                !serverUrl.startsWith("http://", ignoreCase = true) &&
                !serverUrl.startsWith("https://", ignoreCase = true)

        return when {
            serverUrl.isBlank() && credentialId.isBlank() ->
                "Server URL or an attached credential must be provided."
            invalidUrl ->
                "Server URL must start with http:// or https://."
            topic.isBlank() ->
                "Topic name cannot be blank."
            else ->
                null
        }
    }

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.plugin.ntfy.NTFY_ACTION",
                displayName = "ntfy Push Action",
                description = "Sends payload to a ntfy.sh or self-hosted server",
                category = NodeCategory.ACTION,
                requiredCredentialType = "NTFY_AUTH",
                credentialSpec =
                    CredentialSpec(
                        typeId = "NTFY_AUTH",
                        displayName = "ntfy Server & Token",
                        description = "ntfy server URL, topic name, and access token credentials",
                        fields =
                            listOf(
                                ConfigFieldSpec.TextField(
                                    key = "serverUrl",
                                    label = "ntfy Server URL",
                                    placeholder = "https://ntfy.sh",
                                ),
                                ConfigFieldSpec.TextField(
                                    key = "topic",
                                    label = "ntfy Topic Name",
                                    placeholder = "my-topic",
                                ),
                                ConfigFieldSpec.TextField(
                                    key = "token",
                                    label = "ntfy Access Token (Optional)",
                                ),
                            ),
                    ),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "serverUrl",
                            label = "Server URL (Leave blank to use credential default)",
                            placeholder = "https://ntfy.sh",
                        ),
                        ConfigFieldSpec.TextField(
                            key = "topic",
                            label = "Topic",
                        ),
                        ConfigFieldSpec.TextField(
                            key = "title",
                            label = "Title",
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "priority",
                            label = "Priority (1-5)",
                            options =
                                listOf(
                                    "3" to "Default / Normal (3)",
                                    "5" to "Max / Urgent (5)",
                                    "4" to "High (4)",
                                    "2" to "Low (2)",
                                    "1" to "Min / Silent (1)",
                                ),
                        ),
                        ConfigFieldSpec.TextField(
                            key = "tags",
                            label = "Tags (comma separated)",
                        ),
                        ConfigFieldSpec.CodeTemplateField(
                            key = "template",
                            label = "Message Template",
                        ),
                    ),
            ),
        )

    override fun getCredentialSpecs(): List<CredentialSpec> = getManifests().mapNotNull { it.credentialSpec }

    override fun createExecutor(
        typeId: String,
        okHttpClient: OkHttpClient,
        credentialRepository: Any?,
    ): NodeExecutor? =
        when {
            typeId == "NTFY_ACTION" || typeId.endsWith(".NTFY_ACTION") -> NtfyActionExecutor(okHttpClient, credentialRepository)
            else -> null
        }
}

class NtfyNodePluginService : Service() {
    private val okHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    private val binder =
        object : INodePluginService.Stub() {
            override fun getPluginApiVersion(): Int = 1

            override fun getManifests(): List<NodePluginManifest> = NtfyPluginModule.getManifests()

            override fun executeNode(
                nodeId: String,
                configJson: String,
                inputContextJson: String,
            ): String {
                val params = parseNtfyServiceParams(configJson, inputContextJson)
                if (params.serverUrl.isBlank()) {
                    return fail("ntfy serverUrl is empty")
                }
                return dispatchNtfyRequest(params)
            }

            private fun dispatchNtfyRequest(params: NtfyServiceParams): String {
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
                            success("Delivered to ntfy topic '${params.topic}'")
                        } else {
                            fail("ntfy request failed: HTTP ${resp.code} - $respBody")
                        }
                    }
                } catch (e: IOException) {
                    fail("ntfy dispatch failed: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    fail("ntfy dispatch failed: ${e.message}")
                }
            }

            private fun success(msg: String) =
                JSONObject()
                    .apply {
                        put("isSuccess", true)
                        put("outputPort", "main")
                        put("logMessage", msg)
                    }.toString()

            private fun fail(reason: String) =
                JSONObject()
                    .apply {
                        put("isSuccess", false)
                        put("errorReason", reason)
                    }.toString()

            private fun toSafeHeaderValue(value: String): String =
                if (value.any { it.code !in 32..126 }) {
                    "=?utf-8?B?" + android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) + "?="
                } else {
                    value
                }
        }

    override fun onBind(intent: Intent?): IBinder = binder
}

private fun parseJsonConfig(configJson: String): JSONObject =
    try {
        JSONObject(configJson)
    } catch (_: JSONException) {
        JSONObject()
    }

private data class NtfyServiceParams(
    val serverUrl: String,
    val topic: String,
    val token: String,
    val title: String,
    val priority: String,
    val tags: String,
    val payload: String,
)

private fun parseNtfyServiceParams(
    configJson: String,
    inputContextJson: String,
): NtfyServiceParams {
    val config = parseJsonConfig(configJson)
    val inputCtx = parseJsonConfig(inputContextJson)

    val credServerUrl = inputCtx.optString("cred_serverUrl", "")
    val credToken = inputCtx.optString("cred_token", "")
    val credTopic = inputCtx.optString("cred_topic", "")

    val serverUrl =
        config
            .optString("serverUrl", "")
            .ifBlank { credServerUrl }
            .trim()
            .trimEnd('/')

    val topic = config.optString("topic", "").ifBlank { credTopic }.trim()
    val token = config.optString("token", "").ifBlank { credToken }.trim()
    val title = config.optString("title", "ZopHop Notification").trim()
    val priority = config.optString("priority", "3")
    val tags = config.optString("tags", "").trim()
    val payload = config.optString("template", inputCtx.optString("formatted_payload", inputCtx.optString("body", "")))

    return NtfyServiceParams(serverUrl, topic, token, title, priority, tags, payload)
}
