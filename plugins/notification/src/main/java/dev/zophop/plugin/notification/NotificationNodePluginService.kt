package dev.zophop.plugin.notification

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class NotificationTriggerExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val permissionError = checkNotificationPermission(appContext)
        if (permissionError != null) {
            return NodeExecutionResult.Failed(permissionError)
        }
        return evaluateTriggerMatches(node, context)
    }
}

private fun checkNotificationPermission(appContext: Context?): String? {
    if (appContext == null) return null
    val pkg = appContext.packageName
    val flat =
        Settings.Secure
            .getString(appContext.contentResolver, "enabled_notification_listeners")
    val hasPermission = flat != null && flat.contains(pkg)
    return if (!hasPermission) {
        "Missing Notification Listener Service permission (Notification Access)"
    } else {
        null
    }
}

private fun parseConfig(configJson: String): JSONObject =
    try {
        JSONObject(configJson)
    } catch (_: JSONException) {
        JSONObject()
    }

private fun evaluateTriggerMatches(
    node: WorkflowNode,
    context: ExecutionContext,
): NodeExecutionResult {
    val config = parseConfig(node.configJson)
    val packageFilter = config.optString("packageFilter", ".*").ifBlank { ".*" }
    val textRegex = config.optString("textRegex", ".*").ifBlank { ".*" }

    val packageName = context.getString("package_name", "").ifEmpty { context.getString("package", "") }
    val title = context.getString("title", "")
    val text = context.getString("text", "").ifEmpty { context.getString("body", "") }

    return when {
        !matchesPackageFilter(packageName, packageFilter) ->
            NodeExecutionResult.Ignored("Package '$packageName' did not match filter '$packageFilter'")
        !matchesTextRegex(title, text, textRegex) ->
            NodeExecutionResult.Ignored("Notification content did not match text regex '$textRegex'")
        else ->
            NodeExecutionResult.Success(logMessage = "Notification trigger matched app: $packageName")
    }
}

private fun matchesPackageFilter(
    packageName: String,
    packageFilter: String,
): Boolean {
    if (packageFilter == ".*") return true
    val filters = packageFilter.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() && it != ".*" }
    return filters.isEmpty() || anyFilterMatches(packageName, filters)
}

private fun anyFilterMatches(
    packageName: String,
    filters: List<String>,
): Boolean =
    filters.any { filter ->
        try {
            val regex = Pattern.compile(filter.replace("*", ".*"), Pattern.CASE_INSENSITIVE)
            regex.matcher(packageName).find()
        } catch (_: PatternSyntaxException) {
            false
        }
    }

private fun matchesTextRegex(
    title: String,
    text: String,
    textRegex: String,
): Boolean {
    if (textRegex == ".*") return true
    val content = "$title $text"
    return try {
        val regex = Pattern.compile(textRegex, Pattern.CASE_INSENSITIVE)
        regex.matcher(content).find()
    } catch (_: PatternSyntaxException) {
        false
    }
}

object NotificationPluginModule : NodePluginModule {
    override val moduleId: String = "notification-plugin-module"

    const val NOTIFICATION_TRIGGER = "NOTIFICATION_TRIGGER"

    override fun createExecutor(
        typeId: String,
        okHttpClient: OkHttpClient,
        credentialRepository: Any?,
    ): NodeExecutor? =
        when {
            typeId.contains("NOTIFICATION_TRIGGER") -> NotificationTriggerExecutor()
            else -> null
        }

    override fun validateConfig(
        typeId: String,
        configJson: String,
    ): String? {
        if (!typeId.contains("NOTIFICATION", ignoreCase = true)) return null
        val config = parseConfig(configJson)
        val textRegex = config.optString("textRegex", ".*").trim()
        if (textRegex.isNotBlank() && textRegex != ".*") {
            try {
                Pattern.compile(textRegex)
            } catch (e: PatternSyntaxException) {
                return "Invalid text regex filter: ${e.message}"
            }
        }
        return null
    }

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.plugin.notification.NOTIFICATION_TRIGGER",
                displayName = "App Notification Trigger",
                description = "Triggers when a notification from selected apps is intercepted",
                category = NodeCategory.TRIGGER,
                iconResName = "ic_node_notification_trigger",
                requiredPermissions = listOf("NOTIFICATION_LISTENER"),
                outputVariables = listOf("package_name", "title", "text", "sender", "body"),
                defaultConfigJson = """{"packageFilter":".*","textRegex":".*"}""",
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "packageFilter",
                            label = "Target App Packages (Comma separated or .* for all)",
                            placeholder = ".*messaging.*",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "textRegex",
                            label = "Notification Text Regex Filter",
                            placeholder = ".*verification code.*",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "testPackageName",
                            label = "Simulated Target Package",
                            placeholder = "com.example.bankapp",
                            singleLine = true,
                            isTestField = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "testTitle",
                            label = "Simulated Notification Title",
                            placeholder = "Bank Alert: OTP Code",
                            singleLine = true,
                            isTestField = true,
                        ),
                        ConfigFieldSpec.CodeTemplateField(
                            key = "testText",
                            label = "Simulated Notification Text",
                            minLines = 2,
                            isTestField = true,
                        ),
                    ),
            ),
        )
}

class NotificationNodePluginService : Service() {
    private val binder =
        object : INodePluginService.Stub() {
            override fun getPluginApiVersion(): Int = 1

            override fun getManifests(): List<NodePluginManifest> = NotificationPluginModule.getManifests()

            override fun executeNode(
                nodeId: String,
                configJson: String,
                inputContextJson: String,
            ): String =
                JSONObject()
                    .apply {
                        put("isSuccess", true)
                        put("outputPort", "main")
                        put("logMessage", "Notification plugin executed")
                    }.toString()
        }

    override fun onBind(intent: Intent?): IBinder = binder
}
