package dev.zophop.plugin.telegram

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.CredentialSpec
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class TelegramActionExecutor(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val credentialRepository: Any? = null,
) : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: android.content.Context?,
    ): NodeExecutionResult {
        val config = parseConfig(node.configJson)
        val botToken = resolveBotToken(config, context, credentialRepository)
        val chatId = context.interpolate(config.optString("chatId", "")).trim()
        val template = config.optString("messageTemplate", "\${body}").ifEmpty { "\${body}" }

        if (botToken.isBlank() || chatId.isBlank()) {
            return NodeExecutionResult.Failed("Telegram botToken or chatId is empty")
        }

        val message = context.interpolate(template)
        val parseMode = config.optString("parseMode", "").trim()
        return sendTelegramMessage(botToken, chatId, message, parseMode)
    }

    private fun sendTelegramMessage(
        botToken: String,
        chatId: String,
        message: String,
        parseMode: String,
    ): NodeExecutionResult {
        if (!BOT_TOKEN_REGEX.matches(botToken)) {
            return NodeExecutionResult.Failed("Invalid Telegram botToken format")
        }
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val jsonBody =
            buildJsonObject {
                put("chat_id", chatId)
                put("text", message)
                if (parseMode.isNotBlank()) {
                    put("parse_mode", parseMode)
                }
            }

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request =
            Request
                .Builder()
                .url(url)
                .post(requestBody)
                .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val responseBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    NodeExecutionResult.Success(logMessage = "Telegram message successfully sent to chat $chatId")
                } else {
                    NodeExecutionResult.Failed("Telegram API error (${resp.code}): ${responseBody.replace(botToken, "***")}")
                }
            }
        } catch (e: IOException) {
            NodeExecutionResult.Failed("Telegram request exception: ${e.message?.replace(botToken, "***")}")
        } catch (e: IllegalArgumentException) {
            NodeExecutionResult.Failed("Telegram request exception: ${e.message?.replace(botToken, "***")}")
        }
    }

    private companion object {
        val BOT_TOKEN_REGEX = Regex("^[A-Za-z0-9:_-]+$")
    }
}

private fun parseConfig(configJson: String): JsonObject = parseJsonObjectSafe(configJson)

private fun resolveBotToken(
    config: JsonObject,
    context: ExecutionContext,
    credentialRepository: Any?,
): String {
    val credTokenFromIpc =
        context.getString("_cred_token", "").ifBlank {
            context.getString("_cred_value", "")
        }.trim()
    val tokenFromConfig = config.optString("botToken", "").trim()
    val credentialId = config.optString("credentialId", "").trim()

    return when {
        credTokenFromIpc.isNotBlank() -> credTokenFromIpc
        tokenFromConfig.isNotBlank() -> tokenFromConfig
        else -> resolveCredentialToken(credentialRepository, credentialId) ?: ""
    }
}

private fun resolveCredentialToken(
    credentialRepository: Any?,
    credentialId: String,
): String? {
    if (credentialRepository == null || credentialId.isBlank()) return null
    return try {
        val method = credentialRepository.javaClass.methods.firstOrNull { it.name == "getCredentialById" }
        if (method != null && method.parameterCount == 1) {
            val cred = method.invoke(credentialRepository, credentialId)
            if (cred != null) {
                extractTokenFromDataMap(cred) ?: extractTokenFromConfigJson(cred)
            } else {
                null
            }
        } else {
            null
        }
    } catch (_: ReflectiveOperationException) {
        null
    }
}

private fun extractTokenFromDataMap(cred: Any): String? =
    try {
        val mapMethod = cred.javaClass.getMethod("getDataMap")
        val dataMap = mapMethod.invoke(cred) as? Map<*, *>
        dataMap?.get("token")?.toString()
            ?: dataMap?.get("value")?.toString()
            ?: dataMap?.get("botToken")?.toString()
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: ClassCastException) {
        null
    }

private fun extractTokenFromConfigJson(cred: Any): String? =
    try {
        val jsonMethod = cred.javaClass.getMethod("getConfigJson")
        val configJson = jsonMethod.invoke(cred) as? String
        if (!configJson.isNullOrBlank()) {
            val credJson = parseJsonObjectSafe(configJson)
            credJson
                .optString("token", "")
                .ifBlank { credJson.optString("value", "") }
                .ifBlank { credJson.optString("botToken", "") }
                .ifBlank { null }
        } else {
            null
        }
    } catch (_: ReflectiveOperationException) {
        null
    }

object TelegramPluginModule : NodePluginModule {
    override val moduleId: String = "telegram-plugin-module"

    override fun createExecutor(
        typeId: String,
        okHttpClient: OkHttpClient,
        credentialRepository: Any?,
    ): NodeExecutor? =
        when {
            typeId.contains("TELEGRAM_ACTION") -> TelegramActionExecutor(okHttpClient, credentialRepository)
            else -> null
        }

    override fun validateConfig(
        typeId: String,
        configJson: String,
    ): String? {
        if (!typeId.contains("TELEGRAM", ignoreCase = true)) return null
        val config = parseConfig(configJson)
        val botToken = config.optString("botToken", "").trim()
        val chatId = config.optString("chatId", "").trim()
        val credentialId = config.optString("credentialId", "").trim()
        return when {
            botToken.isBlank() && credentialId.isBlank() ->
                "Either Telegram Bot Token or an attached credential must be provided."
            chatId.isBlank() ->
                "Telegram Chat ID cannot be blank."
            else ->
                null
        }
    }

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                displayName = "Telegram Notification",
                description = "Sends formatted notifications and alerts to a Telegram chat via Bot API",
                category = NodeCategory.ACTION,
                iconResName = "ic_node_telegram",
                requiredCredentialType = "TELEGRAM_AUTH",
                defaultConfigJson = """{"messageTemplate":"${'$'}{body}"}""",
                credentialSpec =
                    CredentialSpec(
                        typeId = "TELEGRAM_AUTH",
                        displayName = "Telegram Bot Token",
                        description = "Telegram Bot Token provided by @BotFather",
                        fields =
                            listOf(
                                ConfigFieldSpec.TextField(
                                    key = "token",
                                    label = "Bot Token",
                                    placeholder = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ",
                                    singleLine = true,
                                    isSecret = true,
                                ),
                            ),
                    ),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "botToken",
                            label = "Telegram Bot Token (Leave blank if credential attached)",
                            tooltip = "Bot token provided by @BotFather or attach saved credential",
                            singleLine = true,
                            isSecret = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "chatId",
                            label = "Telegram Chat ID",
                            tooltip = "Target Chat ID or Channel username (e.g. -100123456789)",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.CodeTemplateField(
                            key = "messageTemplate",
                            label = "Message Template",
                            tooltip = "Message body template (supports \${body}, \${sender}, etc.)",
                            minLines = 3,
                        ),
                    ),
            ),
        )
}

class TelegramNodePluginService : Service() {
    private val binder =
        object : INodePluginService.Stub() {
            override fun getPluginApiVersion(): Int = 1

            override fun getManifests(): List<NodePluginManifest> = TelegramPluginModule.getManifests()

            override fun executeNode(
                nodeId: String,
                configJson: String,
                inputContextJson: String,
            ): String =
                runBlocking(Dispatchers.IO) {
                    if (!isTrustedCaller()) {
                        return@runBlocking failNodeExecution("Unauthorized caller")
                    }
                    try {
                        val node =
                            WorkflowNode(
                                id = nodeId,
                                name = "Telegram Action",
                                type = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                                configJson = configJson,
                            )
                        val inputMap = mutableMapOf<String, Any>()
                        val inputObj = parseJsonObjectSafe(inputContextJson)
                        for ((k, v) in inputObj) {
                            inputMap[k] = v.optString()
                        }
                        val context = ExecutionContext(inputMap)
                        val executor = TelegramActionExecutor()
                        val result = executor.execute(node, context, this@TelegramNodePluginService)
                        when (result) {
                            is NodeExecutionResult.Success -> {
                                buildJsonObject {
                                    put("isSuccess", true)
                                    put("outputPort", result.outputPort)
                                    put("logMessage", result.logMessage)
                                }.toString()
                            }
                            is NodeExecutionResult.Failed -> {
                                buildJsonObject {
                                    put("isSuccess", false)
                                    put("errorReason", result.reason)
                                }.toString()
                            }
                            is NodeExecutionResult.Ignored -> {
                                buildJsonObject {
                                    put("isSuccess", false)
                                    put("isIgnored", true)
                                    put("errorReason", result.reason)
                                }.toString()
                            }
                        }
                    } catch (e: kotlinx.serialization.SerializationException) {
                        failNodeExecution(e.message ?: "JSON parsing failed")
                    } catch (e: IOException) {
                        failNodeExecution(e.message ?: "Network execution failed")
                    } catch (e: IllegalArgumentException) {
                        failNodeExecution(e.message ?: "Invalid argument")
                    } catch (e: IllegalStateException) {
                        failNodeExecution(e.message ?: "Illegal state")
                    }
                }
        }

    private fun isTrustedCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val myUid = Process.myUid()
        if (callingUid == myUid) return true
        return packageManager.checkSignatures(myUid, callingUid) == PackageManager.SIGNATURE_MATCH
    }

    private fun failNodeExecution(reason: String): String =
        buildJsonObject {
            put("isSuccess", false)
            put("errorReason", reason)
        }.toString()

    override fun onBind(intent: Intent?): IBinder = binder
}
