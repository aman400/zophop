package dev.zophop.domain.engine.executors

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optInt
import dev.zophop.domain.model.optJsonArray
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.ExecutionLogFormatter
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

private fun parseTriggerConfig(configJson: String): JsonObject = parseJsonObjectSafe(configJson)

/**
 * Strategy executor for [NodeType.MANUAL_TRIGGER].
 */
class ManualTriggerExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseTriggerConfig(node.configJson)
        val testBody = config.optString("testBody", "").trim()
        val currentBody = context.getString("body", "").ifEmpty { context.getString("text", "") }
        val body = testBody.ifEmpty { currentBody }.ifEmpty { "Manual trigger execution" }

        context.set("body", body)
        context.set("text", body)
        if (context.get("timestamp") == null) {
            context.set("timestamp", System.currentTimeMillis().toString())
        }

        val log =
            ExecutionLogFormatter.buildStructuredLog(
                summary = "Manual trigger executed on demand",
                sections =
                    listOf(
                        "Trigger Context" to
                            "Body Length: ${body.length} chars\nVariables Set: body, text, timestamp",
                        "Payload" to ExecutionLogFormatter.previewText(body, 600),
                    ),
            )
        return NodeExecutionResult.Success(logMessage = log)
    }
}

/**
 * Strategy executor for [NodeType.NOTIFICATION_TRIGGER].
 */
class NotificationTriggerExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val permError = checkNotificationPermission(appContext)
        if (permError != null) {
            return NodeExecutionResult.Failed(permError)
        }

        val config = parseTriggerConfig(node.configJson)
        val packageFilter = config.optString("packageFilter", ".*")
        val textRegex = config.optString("textRegex", ".*")

        val packageName = context.getString("package_name", "").ifEmpty { context.getString("package", "") }
        val title = context.getString("title", "")
        val text = context.getString("text", "").ifEmpty { context.getString("body", "") }

        return when {
            !checkPackageFilter(packageFilter, packageName) ->
                NodeExecutionResult.Ignored("Package '$packageName' did not match filter '$packageFilter'")
            !checkTextRegex(textRegex, "$title $text") ->
                NodeExecutionResult.Ignored("Notification content did not match text regex '$textRegex'")
            else -> {
                val details =
                    "App Package: $packageName\nTitle: ${ExecutionLogFormatter.previewText(title, 200)}\n" +
                        "Package Filter: $packageFilter\nText Regex: $textRegex\n" +
                        "Text Length: ${text.length} chars\nVariables Set: package_name, title, text, body"
                val log =
                    ExecutionLogFormatter.buildStructuredLog(
                        summary = "Notification trigger matched app: $packageName",
                        sections =
                            listOf(
                                "Notification Details" to details,
                                "Content Preview" to ExecutionLogFormatter.previewText(text, 500),
                            ),
                    )
                NodeExecutionResult.Success(logMessage = log)
            }
        }
    }

    private fun checkNotificationPermission(appContext: Context?): String? {
        if (appContext == null) return null
        val pkg = appContext.packageName
        val flat = android.provider.Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners",
        )
        val hasPermission = flat != null && flat.contains(pkg)
        return if (!hasPermission) "Missing Notification Listener Service permission (Notification Access)" else null
    }

    private fun checkPackageFilter(packageFilter: String, packageName: String): Boolean {
        if (packageFilter.isBlank() || packageFilter == ".*") return true
        val filters = packageFilter.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() && it != ".*" }
        if (filters.isEmpty()) return true
        return filters.any { filter ->
            try {
                val normalized = STANDALONE_WILDCARD_REGEX.replace(filter, ".*")
                Pattern.compile(normalized, Pattern.CASE_INSENSITIVE).matcher(packageName).find()
            } catch (_: PatternSyntaxException) {
                false
            }
        }
    }

    private companion object {
        val STANDALONE_WILDCARD_REGEX = Regex("(?<![.\\])+?*])\\*")
    }

    private fun checkTextRegex(textRegex: String, content: String): Boolean {
        if (textRegex.isBlank() || textRegex == ".*") return true
        return try {
            Pattern.compile(textRegex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(content).find()
        } catch (_: PatternSyntaxException) {
            false
        }
    }
}

/**
 * Strategy executor for [NodeType.REGEX_PARSER].
 * Extracts values using regular expressions.
 */
class RegexParserExecutor : NodeExecutor {
    private data class MatchRecord(
        val fullMatch: String,
        val groups: List<String?>,
        val namedGroups: Map<String, String>,
    )

    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseTriggerConfig(node.configJson)
        val rawRegexStr = config.optString("regex", "\\b\\d{4,8}\\b")
        val regexStr = context.interpolate(rawRegexStr).trim()
        val outputKey = config.optString("outputKey", "code").trim().ifEmpty { "code" }
        val defaultValue = config.optString("defaultValue", "")
        val matchIndex = config.optInt("matchIndex", 0)
        val matchAll = config.optBoolean("matchAll", false)
        val outputType = config.optString("outputType", "AUTO").trim()

        val inputText = resolveInputText(config, context)
        val matches = if (inputText.isNotBlank()) {
            try {
                findAllMatches(regexStr, inputText)
            } catch (_: PatternSyntaxException) {
                null
            }
        } else {
            null
        }

        return when {
            inputText.isBlank() -> NodeExecutionResult.Failed("Input text is empty for regex parsing")
            matches == null -> NodeExecutionResult.Failed("Regex execution failed for '$regexStr'")
            matches.isEmpty() -> handleEmptyMatches(defaultValue, outputKey, regexStr, context)
            else -> handleSuccessfulMatches(matches, matchIndex, config, outputKey, matchAll, outputType, regexStr, inputText.length, context)
        }
    }

    private fun resolveInputText(config: JsonObject, context: ExecutionContext): String {
        val rawInputVar = config.optString("inputVariable", "").trim()
        val inputVariable = context.interpolate(rawInputVar).trim()
        return when {
            inputVariable.isNotBlank() && context.get(inputVariable) != null -> context.getString(inputVariable, "")
            context.getString("body", "").isNotBlank() -> context.getString("body", "")
            context.getString("text", "").isNotBlank() -> context.getString("text", "")
            context.getString("http_response", "").isNotBlank() -> context.getString("http_response", "")
            context.getString("formatted_payload", "").isNotBlank() -> context.getString("formatted_payload", "")
            context.getString("payload", "").isNotBlank() -> context.getString("payload", "")
            else -> ""
        }
    }

    private fun findAllMatches(regexStr: String, inputText: String): List<MatchRecord> {
        val flags = Pattern.CASE_INSENSITIVE or Pattern.DOTALL or Pattern.MULTILINE
        val pattern = Pattern.compile(regexStr, flags)
        val matcher = pattern.matcher(inputText)

        val namedGroupRegex = Regex("""[(][?][<]([a-zA-Z][a-zA-Z0-9_]*)[>]""")
        val declaredGroupNames = namedGroupRegex.findAll(regexStr).map { it.groupValues[1] }.toList()

        val matches = mutableListOf<MatchRecord>()
        while (matcher.find()) {
            val full = matcher.group(0) ?: ""
            val groupList = (1..matcher.groupCount()).map { matcher.group(it) }
            val namedMap = extractNamedGroups(matcher, declaredGroupNames)
            matches.add(MatchRecord(full, groupList, namedMap))
        }
        return matches
    }

    private fun extractNamedGroups(matcher: java.util.regex.Matcher, declaredGroupNames: List<String>): Map<String, String> {
        val namedMap = mutableMapOf<String, String>()
        for (name in declaredGroupNames) {
            try {
                val v = matcher.group(name)
                if (v != null) namedMap[name] = v
            } catch (_: IllegalArgumentException) {
            }
        }
        return namedMap
    }

    private fun handleEmptyMatches(
        defaultValue: String,
        outputKey: String,
        regexStr: String,
        context: ExecutionContext,
    ): NodeExecutionResult = if (defaultValue.isNotBlank()) {
        context.set(outputKey, defaultValue)
        context.set("code", defaultValue)
        context.set("otp", defaultValue)
        NodeExecutionResult.Success(
            logMessage = "No match found for '$regexStr', used default '$outputKey' = '$defaultValue'",
        )
    } else {
        NodeExecutionResult.Failed("No match found for regex '$regexStr'")
    }

    private fun handleSuccessfulMatches(
        matches: List<MatchRecord>,
        matchIndex: Int,
        config: JsonObject,
        outputKey: String,
        matchAll: Boolean,
        outputType: String,
        regexStr: String,
        inputLength: Int,
        context: ExecutionContext,
    ): NodeExecutionResult {
        val resolvedIndex = if (matchIndex < 0) matches.size + matchIndex else matchIndex
        val chosenMatch = matches.getOrNull(resolvedIndex) ?: matches.first()
        val preferredVal = chosenMatch.groups.firstOrNull { it != null } ?: chosenMatch.fullMatch

        context.set(outputKey, preferredVal)
        context.set("code", preferredVal)
        context.set("otp", preferredVal)

        chosenMatch.namedGroups.forEach { (name, value) -> context.set(name, value) }
        chosenMatch.groups.forEachIndexed { idx, gVal ->
            if (gVal != null) context.set("group_${idx + 1}", gVal)
        }
        populateOutputKeysArray(config.optJsonArray("outputKeys"), chosenMatch.groups, context)
        populateMatchAllArray(matches, outputKey, matchAll, outputType, context)

        val summary = if (chosenMatch.namedGroups.isNotEmpty()) {
            "Extracted '$outputKey' = '$preferredVal' with named groups: ${chosenMatch.namedGroups.keys}"
        } else {
            "Extracted '$outputKey' = '$preferredVal'"
        }
        val details =
            buildString {
                appendLine("Pattern: $regexStr")
                appendLine("Input Length: $inputLength chars")
                appendLine("Matches Found: ${matches.size} (selected #$resolvedIndex)")
                appendLine("Full Match: ${ExecutionLogFormatter.previewText(chosenMatch.fullMatch, 200)}")
                append("Output Variable: $outputKey = '$preferredVal'")
                if (chosenMatch.namedGroups.isNotEmpty()) {
                    append("\nNamed Groups: ${chosenMatch.namedGroups}")
                }
            }
        return NodeExecutionResult.Success(
            logMessage = ExecutionLogFormatter.buildStructuredLog(summary, listOf("Regex Extraction Details" to details)),
        )
    }

    private fun populateOutputKeysArray(
        outputKeysArr: JsonArray?,
        groups: List<String?>,
        context: ExecutionContext,
    ) {
        if (outputKeysArr == null) return
        for (i in 0 until outputKeysArr.size) {
            val k = outputKeysArr.optString(i, "").trim()
            val v = groups.getOrNull(i)
            if (k.isNotEmpty() && v != null) {
                context.set(k, v)
            }
        }
    }

    private fun populateMatchAllArray(
        matches: List<MatchRecord>,
        outputKey: String,
        matchAll: Boolean,
        outputType: String,
        context: ExecutionContext,
    ) {
        val isArrayOutput = outputType.equals("ARRAY", ignoreCase = true) || outputType.equals("LIST", ignoreCase = true)
        if (!matchAll && !isArrayOutput) return

        val allArray =
            buildJsonArray {
                matches.forEach { m ->
                    val v = m.groups.firstOrNull { it != null } ?: m.fullMatch
                    add(JsonPrimitive(v))
                }
            }
        context.set("${outputKey}_all", allArray.toString())
        context.set("all_matches", allArray.toString())
        if (isArrayOutput) {
            context.set(outputKey, allArray.toString())
        }
    }
}

/**
 * Strategy executor for [NodeType.TEMPLATE_FORMATTER].
 */
class TemplateFormatterExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseTriggerConfig(node.configJson)
        val template = config.optString("template", "\${body}")
        val outputKey = config.optString("outputKey", "").trim()

        val formatted = context.interpolate(template)
        context.set("formatted_payload", formatted)
        context.set("payload", formatted)
        if (outputKey.isNotBlank()) {
            context.set(outputKey, formatted)
        }

        val writtenVars = buildList {
            add("formatted_payload")
            add("payload")
            if (outputKey.isNotBlank()) add(outputKey)
        }.joinToString(", ")

        val log =
            ExecutionLogFormatter.buildStructuredLog(
                summary = "Formatted payload created (${formatted.length} chars)",
                sections =
                    listOf(
                        "Template Details" to "Output Length: ${formatted.length} chars\nVariables Set: $writtenVars",
                        "Formatted Output" to ExecutionLogFormatter.previewText(formatted, 1500),
                    ),
            )
        return NodeExecutionResult.Success(logMessage = log)
    }
}

/**
 * Strategy executor for [NodeType.SEND_NOTIFICATION_ACTION].
 */
class SendNotificationExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseTriggerConfig(node.configJson)
        val titleTemplate = config.optString("title", "ZopHop Forwarder")
        val messageTemplate = config.optString("body", "").ifEmpty {
            config.optString("message", "").ifEmpty {
                config.optString("template", "\${body}")
            }
        }

        val title = context.interpolate(titleTemplate).ifBlank { "ZopHop Notification" }
        val text = context.interpolate(messageTemplate).ifBlank { context.getString("body", "Notification alert") }

        val notifSections =
            listOf(
                "Notification Details" to "Channel: Workflow Alerts\nTitle: $title\nBody Length: ${text.length} chars",
                "Notification Body" to ExecutionLogFormatter.previewText(text, 600),
            )
        return if (appContext != null) {
            dispatchNotification(appContext, title, text, notifSections)
        } else {
            NodeExecutionResult.Success(
                logMessage =
                    ExecutionLogFormatter.buildStructuredLog(
                        summary = "Posted system notification: '$title' (headless)",
                        sections = notifSections,
                    ),
            )
        }
    }

    private fun dispatchNotification(
        ctx: Context,
        title: String,
        text: String,
        sections: List<Pair<String, String>>,
    ): NodeExecutionResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationPermission(ctx)) {
            return NodeExecutionResult.Failed("Missing Post Notifications Permission (POST_NOTIFICATIONS)")
        }
        return try {
            postSystemNotification(ctx, title, text)
            NodeExecutionResult.Success(
                logMessage =
                    ExecutionLogFormatter.buildStructuredLog(
                        summary = "Posted system notification: '$title'",
                        sections = sections,
                    ),
            )
        } catch (e: SecurityException) {
            NodeExecutionResult.Failed("Notification posting failed: ${e.message}")
        } catch (e: IllegalStateException) {
            NodeExecutionResult.Failed("Notification posting failed: ${e.message}")
        }
    }

    private fun hasPostNotificationPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    private fun postSystemNotification(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "zophop_workflow_channel_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Workflow Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications posted by ZopHop Workflows"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = Intent().apply {
            setClassName(ctx, "dev.zophop.ui.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            ctx,
            System.currentTimeMillis().toInt(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(dev.zophop.core.engine.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingOpenIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}

/**
 * Strategy executor for [NodeType.CRON_TRIGGER].
 */
class CronTriggerExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseTriggerConfig(node.configJson)
        val cronExpression = config.optString("cronExpression", "every 30m")
        val payload = config.optString("testPayload", "Scheduled cron trigger executed")
            .ifEmpty { "Scheduled cron trigger executed" }

        context.set("body", payload)
        context.set("text", payload)
        context.set("sender", "CronScheduler")
        context.set("timestamp", System.currentTimeMillis().toString())

        val log =
            ExecutionLogFormatter.buildStructuredLog(
                summary = "Cron schedule triggered successfully",
                sections =
                    listOf(
                        "Schedule Details" to "Expression: $cronExpression\nVariables Set: body, text, sender, timestamp",
                        "Payload" to ExecutionLogFormatter.previewText(payload, 500),
                    ),
            )
        return NodeExecutionResult.Success(logMessage = log)
    }
}
