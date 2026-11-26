package dev.zophop.plugin.ntfy.ntfyplugin

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Base64
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.CredentialSpec
import dev.zophop.plugin.api.ExecutionLogFormatter
import dev.zophop.plugin.api.HttpCredentialAdapter
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object NtfyPluginModule : NodePluginModule {
    override val moduleId: String = "ntfy-plugin-module"

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.ntfyplugin.NTFY_ACTION",
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
                            key = "token",
                            label = "Access Token (Optional)",
                            placeholder = "Leave blank to use credential default",
                            tooltip = "Override access token. If left blank, token from attached credential is used.",
                            singleLine = true,
                            isSecret = true,
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

    override fun getHttpCredentialAdapters(): List<HttpCredentialAdapter> =
        listOf(dev.zophop.plugin.ntfy.NtfyHttpCredentialAdapter)
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
                if (!isTrustedCaller()) {
                    return fail("Unauthorized caller")
                }
                val params = parseNtfyServiceParams(configJson, inputContextJson)
                if (params.serverUrl.isBlank()) {
                    return fail("ntfy serverUrl is empty")
                }
                return dispatchNtfyRequest(params)
            }

            private fun dispatchNtfyRequest(params: NtfyServiceParams): String {
                val targetUrl = "${params.serverUrl}/${params.topic}"
                val cleanToken = sanitizeControlChars(params.token)
                val explicitSecrets = listOfNotNull(cleanToken.takeIf { it.isNotBlank() }, params.token.takeIf { it.isNotBlank() }).distinct()

                return try {
                    val requestBuilder =
                        Request
                            .Builder()
                            .url(targetUrl)
                            .post(params.payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))

                    if (params.title.isNotBlank()) requestBuilder.addHeader("Title", toSafeHeaderValue(params.title))
                    if (params.priority.isNotBlank()) requestBuilder.addHeader("Priority", sanitizeControlChars(params.priority))
                    if (params.tags.isNotBlank()) requestBuilder.addHeader("Tags", toSafeHeaderValue(params.tags))
                    if (cleanToken.isNotBlank()) requestBuilder.addHeader("Authorization", "Bearer $cleanToken")

                    val response = okHttpClient.newCall(requestBuilder.build()).execute()
                    response.use { resp ->
                        val respBody = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            success("Delivered to ntfy topic '${params.topic}'")
                        } else {
                            val safeBody = ExecutionLogFormatter.redactSecrets(respBody, explicitSecrets)
                            fail("ntfy request failed: HTTP ${resp.code} - $safeBody")
                        }
                    }
                } catch (e: IOException) {
                    val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
                    fail("ntfy dispatch failed: $safeMsg")
                } catch (e: IllegalArgumentException) {
                    val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
                    fail("ntfy dispatch failed: $safeMsg")
                }
            }

            private fun sanitizeControlChars(value: String): String =
                value.replace(Regex("[\\r\\n\\u0000]"), "").trim()

            private fun toSafeHeaderValue(value: String): String {
                val cleaned = sanitizeControlChars(value)
                return if (cleaned.any { it.code !in 32..126 }) {
                    "=?utf-8?B?" + Base64.encodeToString(cleaned.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="
                } else {
                    cleaned
                }
            }

            private fun success(msg: String) =
                buildJsonObject {
                    put("isSuccess", true)
                    put("outputPort", "main")
                    put("logMessage", msg)
                }.toString()

            private fun fail(reason: String) =
                buildJsonObject {
                    put("isSuccess", false)
                    put("errorReason", reason)
                }.toString()
        }

    private fun isTrustedCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val myUid = Process.myUid()
        if (callingUid == myUid) return true
        return packageManager.checkSignatures(myUid, callingUid) == PackageManager.SIGNATURE_MATCH
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

private fun parseJsonConfig(configJson: String): JsonObject = parseJsonObjectSafe(configJson)

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
