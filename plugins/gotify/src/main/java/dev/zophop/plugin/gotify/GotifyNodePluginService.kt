package dev.zophop.plugin.gotify

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

object GotifyPluginModule : NodePluginModule {
    override val moduleId: String = "gotify-plugin-module"

    override fun validateConfig(
        typeId: String,
        configJson: String,
    ): String? {
        if (!typeId.contains("GOTIFY", ignoreCase = true)) return null
        val config = parseConfig(configJson)
        val serverUrl = config.optString("serverUrl", "").trim()
        val appToken = config.optString("appToken", "").trim()
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
            appToken.isBlank() && credentialId.isBlank() ->
                "App Token or an attached credential must be provided."
            else ->
                null
        }
    }

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.plugin.gotify.GOTIFY_ACTION",
                displayName = "Gotify Push Action",
                description = "Sends payload to a Gotify push server",
                category = NodeCategory.ACTION,
                iconResName = "ic_node_gotify",
                requiredCredentialType = "GOTIFY_AUTH",
                defaultConfigJson = """{"serverUrl":"https://gotify.example.com","priority":"5","title":"ZopHop Notification","template":"${'$'}{body}"}""",
                credentialSpec =
                    CredentialSpec(
                        typeId = "GOTIFY_AUTH",
                        displayName = "Gotify Server & App Token",
                        description = "Gotify server URL and application token credentials",
                        fields =
                            listOf(
                                ConfigFieldSpec.TextField(
                                    key = "serverUrl",
                                    label = "Gotify Server URL",
                                    placeholder = "https://gotify.example.com",
                                ),
                                ConfigFieldSpec.TextField(
                                    key = "appToken",
                                    label = "Gotify Application Token",
                                ),
                            ),
                    ),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "serverUrl",
                            label = "Gotify Server URL (Leave blank for credential default)",
                            placeholder = "https://gotify.example.com",
                        ),
                        ConfigFieldSpec.TextField(
                            key = "title",
                            label = "Notification Title",
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "priority",
                            label = "Priority (1-10)",
                            options =
                                listOf(
                                    "5" to "Default / Normal (5)",
                                    "10" to "Max / Urgent (10)",
                                    "8" to "High (8)",
                                    "3" to "Low (3)",
                                    "1" to "Min / Silent (1)",
                                ),
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
            typeId == "GOTIFY_ACTION" || typeId.endsWith(".GOTIFY_ACTION") -> GotifyActionExecutor(okHttpClient, credentialRepository)
            else -> null
        }
}

class GotifyNodePluginService : Service() {
    private val okHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    private val binder =
        object : INodePluginService.Stub() {
            override fun getPluginApiVersion(): Int = 1

            override fun getManifests(): List<NodePluginManifest> = GotifyPluginModule.getManifests()

            override fun executeNode(
                nodeId: String,
                configJson: String,
                inputContextJson: String,
            ): String {
                val params = parseGotifyServiceParams(configJson, inputContextJson)
                if (params.serverUrl.isBlank()) {
                    return fail("Gotify serverUrl is empty")
                }
                return dispatchGotifyRequest(params)
            }

            private fun dispatchGotifyRequest(params: GotifyServiceParams): String {
                val targetUrl = "${params.serverUrl}/message" + (if (params.appToken.isNotBlank()) "?token=${params.appToken}" else "")
                val jsonBody =
                    JSONObject()
                        .apply {
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
                            success("Delivered to Gotify server")
                        } else {
                            fail("Gotify request failed: HTTP ${resp.code} - $respBody")
                        }
                    }
                } catch (e: IOException) {
                    fail("Gotify dispatch failed: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    fail("Gotify dispatch failed: ${e.message}")
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
        }

    override fun onBind(intent: Intent?): IBinder = binder
}

private fun parseConfig(configJson: String): JSONObject =
    try {
        JSONObject(configJson)
    } catch (_: JSONException) {
        JSONObject()
    }

private data class GotifyServiceParams(
    val serverUrl: String,
    val appToken: String,
    val title: String,
    val priority: Int,
    val payload: String,
)

private fun parseGotifyServiceParams(
    configJson: String,
    inputContextJson: String,
): GotifyServiceParams {
    val config = parseConfig(configJson)
    val inputCtx = parseConfig(inputContextJson)

    val credServerUrl = inputCtx.optString("cred_serverUrl", "")
    val credAppToken = inputCtx.optString("cred_appToken", "")

    val serverUrl =
        config
            .optString("serverUrl", "")
            .ifBlank { credServerUrl }
            .trim()
            .trimEnd('/')

    val appToken = config.optString("appToken", "").ifBlank { credAppToken }.trim()
    val title = config.optString("title", "ZopHop Notification").trim()
    val priority = config.optInt("priority", 5)
    val payload = config.optString("template", inputCtx.optString("formatted_payload", inputCtx.optString("body", "")))

    return GotifyServiceParams(serverUrl, appToken, title, priority, payload)
}
