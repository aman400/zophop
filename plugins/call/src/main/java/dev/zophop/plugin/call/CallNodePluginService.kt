package dev.zophop.plugin.call

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class CallTriggerExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        if (!hasPhoneStatePermission(appContext)) {
            return NodeExecutionResult.Failed("READ_PHONE_STATE permission not granted in Call Plugin App")
        }
        return evaluateCallTrigger(node, context)
    }
}

private fun hasPhoneStatePermission(appContext: Context?): Boolean {
    if (appContext == null) return true
    return ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.READ_PHONE_STATE,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun evaluateCallTrigger(
    node: WorkflowNode,
    context: ExecutionContext,
): NodeExecutionResult {
    val config = parseJsonConfig(node.configJson)
    val callerRegex = config.optString("callerRegex", ".*").ifBlank { ".*" }
    val callerNumber =
        context.getString("caller_number", "").ifEmpty {
            context.getString("sender", "").ifEmpty {
                context.getString("header", "")
            }
        }

    val matches = matchesCallerRegex(callerRegex, callerNumber)
    if (!matches) {
        return NodeExecutionResult.Ignored("Caller number '$callerNumber' did not match filter regex '$callerRegex'")
    }

    context.set("caller_number", callerNumber)
    context.set("sender", callerNumber)
    context.set("header", callerNumber)

    return NodeExecutionResult.Success(logMessage = "Incoming call trigger matched caller '$callerNumber'")
}

class DisconnectCallExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val ctx = appContext ?: return NodeExecutionResult.Failed("Context unavailable for call action")
        val error = validateDisconnectPrerequisites(ctx)
        return if (error != null) {
            NodeExecutionResult.Failed(error)
        } else {
            executeTelecomDisconnect(ctx, context)
        }
    }
}

private fun validateDisconnectPrerequisites(ctx: Context): String? =
    when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
            "Disconnecting call requires Android 9.0+ (API 28)"
        !hasDisconnectPermission(ctx) ->
            "ANSWER_PHONE_CALLS or READ_PHONE_STATE permission not granted"
        ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager == null ->
            "TelecomManager service unavailable"
        else ->
            null
    }

private fun hasDisconnectPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(
        ctx,
        Manifest.permission.ANSWER_PHONE_CALLS,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

private fun executeTelecomDisconnect(
    ctx: Context,
    context: ExecutionContext,
): NodeExecutionResult {
    val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        ?: return NodeExecutionResult.Failed("TelecomManager unavailable")
    val endSuccess =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                telecomManager.endCall()
            } catch (_: SecurityException) {
                false
            }
        } else {
            false
        }

    context.set("call_status", if (endSuccess) "DISCONNECTED" else "FAILED")
    return if (endSuccess) {
        NodeExecutionResult.Success(logMessage = "Disconnected incoming call")
    } else {
        NodeExecutionResult.Failed(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "Failed to end call"
            else "Ending calls via TelecomManager requires Android 9.0 (API 28) or higher",
        )
    }
}

class AnswerCallExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val ctx = appContext ?: return NodeExecutionResult.Failed("Context unavailable for call action")
        val error = validateAnswerPrerequisites(ctx)
        return if (error != null) {
            NodeExecutionResult.Failed(error)
        } else {
            executeTelecomAnswer(ctx, context)
        }
    }
}

private fun validateAnswerPrerequisites(ctx: Context): String? =
    when {
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ANSWER_PHONE_CALLS,
        ) != PackageManager.PERMISSION_GRANTED ->
            "ANSWER_PHONE_CALLS permission not granted in Call Plugin App"
        ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager == null ->
            "TelecomManager service unavailable"
        else ->
            null
    }

private fun executeTelecomAnswer(
    ctx: Context,
    context: ExecutionContext,
): NodeExecutionResult {
    val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val acceptSuccess =
        try {
            telecomManager.acceptRingingCall()
            true
        } catch (_: SecurityException) {
            false
        }

    context.set("call_status", if (acceptSuccess) "ANSWERED" else "FAILED")
    return if (acceptSuccess) {
        NodeExecutionResult.Success(logMessage = "Answered incoming call")
    } else {
        NodeExecutionResult.Failed("Failed to answer call")
    }
}

object CallPluginModule : NodePluginModule {
    override val moduleId: String = "call-plugin-module"

    override fun createExecutor(
        typeId: String,
        okHttpClient: OkHttpClient,
        credentialRepository: Any?,
    ): NodeExecutor? =
        when {
            typeId.contains("CALL_TRIGGER") -> CallTriggerExecutor()
            typeId.contains("DISCONNECT_CALL_ACTION") -> DisconnectCallExecutor()
            typeId.contains("ANSWER_CALL_ACTION") -> AnswerCallExecutor()
            else -> null
        }

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.plugin.call.CALL_TRIGGER",
                displayName = "Incoming Call Trigger",
                description = "Triggers when an incoming phone call is received",
                category = NodeCategory.TRIGGER,
                iconResName = "ic_node_call",
                requiredPermissions = listOf(Manifest.permission.READ_PHONE_STATE),
                setupActivity = "dev.zophop.plugin.call.SetupActivity",
                triggerTestPayload = """{"caller_number":"+15559876543","sender":"+15559876543"}""",
                outputVariables = listOf("caller_number", "sender", "header"),
                defaultConfigJson = """{"callerRegex":".*"}""",
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
                typeId = "dev.zophop.plugin.call.DISCONNECT_CALL_ACTION",
                displayName = "Disconnect Call Action",
                description = "Rejects or disconnects an incoming phone call",
                category = NodeCategory.ACTION,
                iconResName = "ic_node_call",
                requiredPermissions = listOf(Manifest.permission.ANSWER_PHONE_CALLS),
                setupActivity = "dev.zophop.plugin.call.SetupActivity",
                supportedTriggerTypes = listOf("dev.zophop.plugin.call.CALL_TRIGGER"),
                outputVariables = listOf("call_status"),
            ),
            NodePluginManifest(
                typeId = "dev.zophop.plugin.call.ANSWER_CALL_ACTION",
                displayName = "Answer Call Action",
                description = "Answers an incoming phone call in speakerphone/hands-free mode",
                category = NodeCategory.ACTION,
                iconResName = "ic_node_call",
                requiredPermissions = listOf(Manifest.permission.ANSWER_PHONE_CALLS),
                setupActivity = "dev.zophop.plugin.call.SetupActivity",
                supportedTriggerTypes = listOf("dev.zophop.plugin.call.CALL_TRIGGER"),
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
                val hasPhonePermission =
                    checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
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
                    buildJsonObject {
                        put("isSuccess", false)
                        put("isIgnored", true)
                        put("errorReason", "Caller number '$callerNumber' did not match filter regex '$callerRegex'")
                    }.toString()
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
                val error = validateServiceDisconnect()
                if (error != null) {
                    return failService(error)
                }
                return performServiceDisconnect(callerNumber)
            }

            private fun validateServiceDisconnect(): String? =
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
                        "Disconnecting call requires Android 9.0+ (API 28)"
                    checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED &&
                        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ->
                        "ANSWER_PHONE_CALLS or READ_PHONE_STATE permission not granted"
                    else ->
                        null
                }

            private fun performServiceDisconnect(callerNumber: String): String {
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
                    put("outputDataMap", buildJsonObject { put("call_status", if (endSuccess) "DISCONNECTED" else "FAILED") })
                }.toString()
            }

            private fun executeAnswerCall(callerNumber: String): String {
                val error = validateServiceAnswer()
                if (error != null) {
                    return failService(error)
                }
                return performServiceAnswer(callerNumber)
            }

            private fun validateServiceAnswer(): String? =
                when {
                    checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED ->
                        "ANSWER_PHONE_CALLS permission not granted in Call Plugin App"
                    else ->
                        null
                }

            private fun performServiceAnswer(callerNumber: String): String {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                val acceptSuccess =
                    try {
                        telecomManager.acceptRingingCall()
                        true
                    } catch (_: SecurityException) {
                        false
                    }

                return buildJsonObject {
                    put("isSuccess", acceptSuccess)
                    put("outputPort", "main")
                    put("logMessage", if (acceptSuccess) "Answered incoming call from '$callerNumber'" else "Failed to answer call")
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
