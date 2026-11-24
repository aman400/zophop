package dev.zophop.plugin.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.serialization.json.JsonObject
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Strategy executor for [NodeType.SMS_TRIGGER].
 */
class SmsTriggerExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        if (!hasReceiveSmsPermission(appContext)) {
            return NodeExecutionResult.Failed("Missing Receive SMS permission (RECEIVE_SMS)")
        }
        return evaluateSmsTrigger(node, context)
    }

    private fun evaluateSmsTrigger(
        node: WorkflowNode,
        context: ExecutionContext,
    ): NodeExecutionResult {
        val config = parseJsonConfig(node.configJson)
        val allowlist = config.optString("allowlist", ".*")
        val keywordRegex = config.optString("keywordRegex", ".*")

        val sender = extractSender(context)
        val body = extractBody(context)

        val filterResult = checkSmsFilters(sender, allowlist, body, keywordRegex)
        if (filterResult != null) {
            return filterResult
        }

        populateSmsContext(context, sender, body)
        return NodeExecutionResult.Success(logMessage = "SMS trigger matched sender: $sender")
    }
}

private fun hasReceiveSmsPermission(appContext: Context?): Boolean =
    appContext == null ||
        (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECEIVE_SMS,
            ) == PackageManager.PERMISSION_GRANTED
        )

private fun extractSender(context: ExecutionContext): String =
    context.getString("sender", "").ifEmpty {
        context.getString("header", "").ifEmpty {
            context.getString("sms_header", "").ifEmpty {
                context.getString("address", "")
            }
        }
    }

private fun extractBody(context: ExecutionContext): String =
    context.getString("body", "").ifEmpty {
        context.getString("sms_body", "").ifEmpty {
            context.getString("message", "").ifEmpty {
                context.getString("text", "")
            }
        }
    }

private fun checkSmsFilters(
    sender: String,
    allowlist: String,
    body: String,
    keywordRegex: String,
): NodeExecutionResult? {
    if (!matchesSenderAllowlist(sender, allowlist)) {
        return NodeExecutionResult.Ignored("Sender '$sender' did not match allowlist filters")
    }
    val regexError = checkKeywordRegexValidity(keywordRegex)
    return when {
        regexError != null ->
            NodeExecutionResult.Failed(regexError)
        !matchesKeywordRegex(body, keywordRegex) ->
            NodeExecutionResult.Ignored("Message body did not match keyword regex '$keywordRegex'")
        else ->
            null
    }
}

private fun matchesSenderAllowlist(
    sender: String,
    allowlist: String,
): Boolean {
    val patterns = allowlist.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
    if (patterns.isEmpty() || allowlist == ".*") return true
    return patterns.any { pattern ->
        try {
            val regex = Pattern.compile(pattern.replace("*", ".*"), Pattern.CASE_INSENSITIVE)
            regex.matcher(sender).find()
        } catch (_: PatternSyntaxException) {
            false
        }
    }
}

private fun matchesKeywordRegex(
    body: String,
    keywordRegex: String,
): Boolean {
    if (keywordRegex.isBlank() || keywordRegex == ".*") return true
    return try {
        val regex = Pattern.compile(keywordRegex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        regex.matcher(body).find()
    } catch (_: PatternSyntaxException) {
        false
    }
}

private fun checkKeywordRegexValidity(keywordRegex: String): String? {
    if (keywordRegex.isBlank() || keywordRegex == ".*") return null
    return try {
        Pattern.compile(keywordRegex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
        null
    } catch (e: PatternSyntaxException) {
        "Invalid keyword regex: ${e.message}"
    }
}

private fun populateSmsContext(
    context: ExecutionContext,
    sender: String,
    body: String,
) {
    context.set("sender", sender)
    context.set("header", sender)
    context.set("sms_header", sender)
    context.set("address", sender)
    context.set("body", body)
    context.set("sms_body", body)
    context.set("message", body)
    context.set("text", body)
}

private fun parseJsonConfig(configJson: String): JsonObject = parseJsonObjectSafe(configJson)
