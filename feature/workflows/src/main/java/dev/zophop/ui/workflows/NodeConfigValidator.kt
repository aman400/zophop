package dev.zophop.ui.workflows

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object NodeConfigValidator {
    fun validateNodeConfig(
        nodeType: String,
        config: Map<String, String>,
    ): String? =
        when {
            nodeType.contains("SEND_SMS_ACTION", ignoreCase = true) -> validateSmsAction(config)
            nodeType.contains("SEND_NOTIFICATION_ACTION", ignoreCase = true) -> validateNotificationAction(config)
            nodeType.contains("HTTP_ACTION", ignoreCase = true) -> validateHttpAction(config)
            nodeType.contains("NTFY_ACTION", ignoreCase = true) -> validateNtfyAction(config)
            nodeType.contains("GOTIFY_ACTION", ignoreCase = true) -> validateGotifyAction(config)
            nodeType.contains("TELEGRAM_ACTION", ignoreCase = true) -> validateTelegramAction(config)
            nodeType.contains("REGEX_PARSER", ignoreCase = true) ||
                nodeType.contains("PARSE_NOTIFICATION", ignoreCase = true) -> validateRegexParser(config)
            nodeType.contains("JSON_PARSER", ignoreCase = true) -> validateJsonParser(config)
            nodeType.contains("TEMPLATE_FORMATTER", ignoreCase = true) -> validateTemplateFormatter(config)
            nodeType.contains("CONDITION", ignoreCase = true) -> validateCondition(config)
            nodeType.contains("CRON_TRIGGER", ignoreCase = true) -> validateCronTrigger(config)
            nodeType.contains("SMS_TRIGGER", ignoreCase = true) -> validateSmsTrigger(config)
            nodeType.contains("NOTIFICATION_TRIGGER", ignoreCase = true) -> validateNotificationTrigger(config)
            else -> null
        }

    private fun validateSmsAction(config: Map<String, String>): String? {
        val recipient = config["recipient"]?.trim() ?: ""
        val body = config["body"]?.trim() ?: ""
        return when {
            recipient.isBlank() -> "Recipient phone number cannot be blank."
            body.isBlank() -> "Message body cannot be blank."
            else -> null
        }
    }

    private fun validateNotificationAction(config: Map<String, String>): String? {
        val title = config["title"]?.trim() ?: ""
        val body = config["body"]?.trim() ?: ""
        return when {
            title.isBlank() -> "Notification title cannot be blank."
            body.isBlank() -> "Notification content body cannot be blank."
            else -> null
        }
    }

    private fun validateHttpAction(config: Map<String, String>): String? {
        val url = config["url"]?.trim() ?: config["serverUrl"]?.trim() ?: ""
        val isHttp = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        return when {
            url.isBlank() -> "HTTP URL cannot be blank."
            !isHttp -> "HTTP URL must start with http:// or https://."
            else -> null
        }
    }

    private fun validateNtfyAction(config: Map<String, String>): String? {
        val serverUrl = config["serverUrl"]?.trim() ?: ""
        val topic = config["topic"]?.trim() ?: ""
        val credentialId = config["credentialId"]?.trim() ?: ""
        return when {
            serverUrl.isBlank() && credentialId.isBlank() -> "Server URL or an attached credential must be provided."
            topic.isBlank() -> "Topic name cannot be blank."
            else -> null
        }
    }

    private fun validateGotifyAction(config: Map<String, String>): String? {
        val serverUrl = config["serverUrl"]?.trim() ?: ""
        val appToken = config["appToken"]?.trim() ?: ""
        val credentialId = config["credentialId"]?.trim() ?: ""
        return when {
            serverUrl.isBlank() && credentialId.isBlank() -> "Server URL or an attached credential must be provided."
            appToken.isBlank() && credentialId.isBlank() -> "App Token or an attached credential must be provided."
            else -> null
        }
    }

    private fun validateTelegramAction(config: Map<String, String>): String? {
        val botToken = config["botToken"]?.trim() ?: ""
        val chatId = config["chatId"]?.trim() ?: ""
        val credentialId = config["credentialId"]?.trim() ?: ""
        return when {
            botToken.isBlank() && credentialId.isBlank() -> "Either Telegram Bot Token or an attached credential must be provided."
            chatId.isBlank() -> "Telegram Chat ID cannot be blank."
            else -> null
        }
    }

    private fun validateRegexParser(config: Map<String, String>): String? {
        val regex = config["regex"]?.trim()?.ifEmpty { "\\b\\d{6}\\b" } ?: "\\b\\d{6}\\b"
        val outputKey = config["outputKey"]?.trim()?.ifEmpty { "code" } ?: "code"
        if (regex.isBlank()) return "Regular expression pattern cannot be blank."
        val regexError = try {
            Pattern.compile(regex)
            null
        } catch (e: PatternSyntaxException) {
            "Invalid regular expression: ${e.message}"
        }
        return regexError ?: if (outputKey.isBlank()) "Output variable name cannot be blank." else null
    }

    private fun validateJsonParser(config: Map<String, String>): String? {
        val jsonKey = config["jsonKey"]?.trim()?.ifEmpty { "data.status" } ?: "data.status"
        val outputKey = config["outputKey"]?.trim()?.ifEmpty { "parsedStatus" } ?: "parsedStatus"
        return when {
            jsonKey.isBlank() -> "JSON path key cannot be blank."
            outputKey.isBlank() -> "Output variable name cannot be blank."
            else -> null
        }
    }

    private fun validateTemplateFormatter(config: Map<String, String>): String? {
        val template = config["template"]?.trim()?.ifEmpty { "\${body}" } ?: "\${body}"
        if (template.isBlank()) return "Template text cannot be blank."
        return null
    }

    private fun validateCondition(config: Map<String, String>): String? {
        val variable = config["variable"]?.trim()?.ifEmpty { "status" } ?: "status"
        val operator = config["operator"]?.trim()?.ifEmpty { "EQUALS" } ?: "EQUALS"
        return when {
            variable.isBlank() -> "Condition variable cannot be blank."
            operator.isBlank() -> "Condition operator must be selected."
            else -> null
        }
    }

    private fun validateCronTrigger(config: Map<String, String>): String? {
        val cronExpression = config["cronExpression"]?.trim()?.ifEmpty { "every 30m" } ?: "every 30m"
        if (cronExpression.isBlank()) return "Schedule interval or cron expression cannot be blank."
        return null
    }

    private fun validateSmsTrigger(config: Map<String, String>): String? {
        val allowlist = config["allowlist"]?.trim() ?: ""
        val keywordRegex = config["keywordRegex"]?.trim() ?: ""
        val allowlistError = if (allowlist.isNotBlank() && allowlist != ".*") {
            try {
                Pattern.compile(allowlist.replace("*", ".*"))
                null
            } catch (e: PatternSyntaxException) {
                "Invalid sender allowlist regex: ${e.message}"
            }
        } else {
            null
        }
        if (allowlistError != null) return allowlistError

        return if (keywordRegex.isNotBlank() && keywordRegex != ".*") {
            try {
                Pattern.compile(keywordRegex)
                null
            } catch (e: PatternSyntaxException) {
                "Invalid keyword regex: ${e.message}"
            }
        } else {
            null
        }
    }

    private fun validateNotificationTrigger(config: Map<String, String>): String? {
        val textRegex = config["textRegex"]?.trim() ?: ""
        if (textRegex.isNotBlank() && textRegex != ".*") {
            try {
                Pattern.compile(textRegex)
            } catch (e: PatternSyntaxException) {
                return "Invalid text regex filter: ${e.message}"
            }
        }
        return null
    }
}
