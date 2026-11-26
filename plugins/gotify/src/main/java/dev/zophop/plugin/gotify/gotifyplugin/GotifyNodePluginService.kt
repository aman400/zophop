package dev.zophop.plugin.gotify.gotifyplugin

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import dev.zophop.domain.model.optInt
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

object GotifyPluginModule : NodePluginModule {
    override val moduleId: String = "gotify-plugin-module"

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.gotifyplugin.GOTIFY_ACTION",
                displayName = "Gotify Push Action",
                description = "Sends payload to a Gotify push server",
                category = NodeCategory.ACTION,
                requiredCredentialType = "GOTIFY_AUTH",
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
                                    isSecret = true,
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
                            key = "appToken",
                            label = "Gotify Application Token (Optional)",
                            placeholder = "Leave blank to use credential default",
                            tooltip = "Application token to authenticate with Gotify server",
                            singleLine = true,
                            isSecret = true,
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
                                    "1" to "Min (1)",
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

    override fun getHttpCredentialAdapters(): List<HttpCredentialAdapter> =
        listOf(dev.zophop.plugin.gotify.GotifyHttpCredentialAdapter)
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
                if (!isTrustedCaller()) {
                    return fail("Unauthorized caller")
                }
                val params = parseGotifyServiceParams(configJson, inputContextJson)
                if (params.serverUrl.isBlank()) {
                    return fail("Gotify serverUrl is empty")
                }
                return dispatchGotifyRequest(params)
            }

            private fun dispatchGotifyRequest(params: GotifyServiceParams): String {
                val cleanToken = params.appToken.replace(Regex("[\\r\\n\\u0000]"), "").trim()
                val encodedToken = java.net.URLEncoder.encode(cleanToken, Charsets.UTF_8.name())
                val explicitSecrets = listOfNotNull(cleanToken.takeIf { it.isNotBlank() }, encodedToken.takeIf { it.isNotBlank() })
                val targetUrl = "${params.serverUrl}/message" + (if (cleanToken.isNotBlank()) "?token=$encodedToken" else "")
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
                    response.use { resp ->
                        val respBody = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            success("Delivered to Gotify server")
                        } else {
                            val safeBody = ExecutionLogFormatter.redactSecrets(respBody, explicitSecrets)
                            fail("Gotify request failed: HTTP ${resp.code} - $safeBody")
                        }
                    }
                } catch (e: IOException) {
                    val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
                    fail("Gotify dispatch failed: $safeMsg")
                } catch (e: IllegalArgumentException) {
                    val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
                    fail("Gotify dispatch failed: $safeMsg")
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

private fun parseConfig(configJson: String): JsonObject = parseJsonObjectSafe(configJson)

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
