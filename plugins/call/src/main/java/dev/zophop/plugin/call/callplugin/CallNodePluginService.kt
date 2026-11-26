package dev.zophop.plugin.call.callplugin

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object CallPluginModule : NodePluginModule {
    override val moduleId: String = "call-plugin-module"

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.callplugin.CALL_TRIGGER",
                displayName = "Incoming Call Trigger",
                description = "Triggers when an incoming phone call is received",
                category = NodeCategory.TRIGGER,
                requiredPermissions = listOf(Manifest.permission.READ_PHONE_STATE),
                setupActivity = "dev.zophop.callplugin.SetupActivity",
                triggerTestPayload = """{"caller_number":"+15559876543","sender":"+15559876543"}""",
                outputVariables = listOf("caller_number", "sender", "header"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "callerRegex",
                            label = "Caller Number Regex Filter",
                            tooltip = "Regex pattern matching caller phone numbers (e.g. .*555.*)",
                        ),
                    ),
            ),
            NodePluginManifest(
                typeId = "dev.zophop.callplugin.DISCONNECT_CALL_ACTION",
                displayName = "Disconnect Call Action",
                description = "Rejects or disconnects an incoming phone call",
                category = NodeCategory.ACTION,
                requiredPermissions = listOf(Manifest.permission.ANSWER_PHONE_CALLS),
                setupActivity = "dev.zophop.callplugin.SetupActivity",
                supportedTriggerTypes = listOf("dev.zophop.callplugin.CALL_TRIGGER"),
                outputVariables = listOf("call_status"),
            ),
            NodePluginManifest(
                typeId = "dev.zophop.callplugin.ANSWER_CALL_ACTION",
                displayName = "Answer Call Action",
                description = "Answers an incoming phone call in speakerphone/hands-free mode",
                category = NodeCategory.ACTION,
                requiredPermissions = listOf(Manifest.permission.ANSWER_PHONE_CALLS),
                setupActivity = "dev.zophop.callplugin.SetupActivity",
                supportedTriggerTypes = listOf("dev.zophop.callplugin.CALL_TRIGGER"),
                outputVariables = listOf("call_status"),
            ),
        )
}

class CallNodePluginService : Service() {
    private val binder =
        object : INodePluginService.Stub() {
            override fun getPluginApiVersion(): Int = 1

            override fun getManifests(): List<NodePluginManifest> = CallPluginModule.getManifests()

            override fun executeNode(
                nodeId: String,
                configJson: String,
                inputContextJson: String,
            ): String {
                val config = parseJsonConfig(configJson)
                val inputCtx = parseJsonConfig(inputContextJson)

                val typeId = config.optString("typeId", nodeId)
                val callerNumber = inputCtx.optString("caller_number", inputCtx.optString("sender", ""))

                return when {
                    typeId.contains("DISCONNECT_CALL_ACTION") -> executeDisconnectCall(callerNumber)
                    typeId.contains("ANSWER_CALL_ACTION") -> executeAnswerCall(callerNumber)
                    else -> executeCallTrigger(config, callerNumber)
                }
            }

            private fun executeCallTrigger(
                config: JsonObject,
                callerNumber: String,
            ): String {
                val hasPhonePermission = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                if (!hasPhonePermission) {
                    return failService("READ_PHONE_STATE permission not granted in Call Plugin App")
                }
                return evaluateCallTriggerService(config, callerNumber)
            }

            private fun evaluateCallTriggerService(
                config: JsonObject,
                callerNumber: String,
            ): String {
                val callerRegex = config.optString("callerRegex", ".*")
                val matches = matchesCallerRegex(callerRegex, callerNumber)

                return if (!matches) {
                    failService("Caller number '$callerNumber' did not match filter regex '$callerRegex'")
                } else {
                    val outputMap =
                        buildJsonObject {
                            put("caller_number", callerNumber)
                            put("sender", callerNumber)
                            put("header", callerNumber)
                        }
                    buildJsonObject {
                        put("isSuccess", true)
                        put("outputPort", "main")
                        put("logMessage", "Incoming call trigger matched caller '$callerNumber'")
                        put("outputDataMap", outputMap)
                    }.toString()
                }
            }

            private fun executeDisconnectCall(callerNumber: String): String {
                val error = validateDisconnectPrerequisites()
                if (error != null) {
                    return failService(error)
                }
                return performDisconnect(callerNumber)
            }

            private fun validateDisconnectPrerequisites(): String? =
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
                        "Disconnecting call requires Android 9.0+ (API 28)"
                    checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED &&
                        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ->
                        "ANSWER_PHONE_CALLS or READ_PHONE_STATE permission not granted"
                    else ->
                        null
                }

            private fun performDisconnect(callerNumber: String): String {
                val telecomManager = getSystemService(TELECOM_SERVICE) as? TelecomManager
                val endSuccess =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecomManager != null) {
                        try {
                            telecomManager.endCall()
                        } catch (_: SecurityException) {
                            false
                        }
                    } else {
                        false
                    }

                return buildJsonObject {
                    put("isSuccess", endSuccess)
                    put("outputPort", "main")
                    put("logMessage", if (endSuccess) "Disconnected incoming call from '$callerNumber'" else "Failed to end call")
                    if (!endSuccess) {
                        put("errorReason", if (telecomManager == null) "TelecomManager unavailable" else "Failed to end call")
                    }
                    put("outputDataMap", buildJsonObject { put("call_status", if (endSuccess) "DISCONNECTED" else "FAILED") })
                }.toString()
            }

            private fun executeAnswerCall(callerNumber: String): String {
                val error = validateAnswerPrerequisites()
                if (error != null) {
                    return failService(error)
                }
                return performAnswer(callerNumber)
            }

            private fun validateAnswerPrerequisites(): String? =
                when {
                    checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED ->
                        "ANSWER_PHONE_CALLS permission not granted in Call Plugin App"
                    else ->
                        null
                }

            private fun performAnswer(callerNumber: String): String {
                val telecomManager = getSystemService(TELECOM_SERVICE) as? TelecomManager
                val acceptSuccess =
                    if (telecomManager != null) {
                        try {
                            telecomManager.acceptRingingCall()
                            true
                        } catch (_: SecurityException) {
                            false
                        }
                    } else {
                        false
                    }

                return buildJsonObject {
                    put("isSuccess", acceptSuccess)
                    put("outputPort", "main")
                    put("logMessage", if (acceptSuccess) "Answered incoming call from '$callerNumber'" else "Failed to answer call")
                    if (!acceptSuccess) {
                        put("errorReason", if (telecomManager == null) "TelecomManager unavailable" else "Failed to answer call")
                    }
                    put("outputDataMap", buildJsonObject { put("call_status", if (acceptSuccess) "ANSWERED" else "FAILED") })
                }.toString()
            }

            private fun failService(reason: String): String =
                buildJsonObject {
                    put("isSuccess", false)
                    put("errorReason", reason)
                }.toString()
        }

    override fun onBind(intent: Intent?): IBinder = binder
}

private fun parseJsonConfig(configJson: String): JsonObject = parseJsonObjectSafe(configJson)

private fun matchesCallerRegex(
    callerRegex: String,
    callerNumber: String,
): Boolean =
    try {
        Pattern.compile(callerRegex, Pattern.CASE_INSENSITIVE).matcher(callerNumber).find()
    } catch (_: PatternSyntaxException) {
        false
    }
