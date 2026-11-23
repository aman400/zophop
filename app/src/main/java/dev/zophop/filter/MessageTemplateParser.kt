package dev.zophop.filter

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object MessageTemplateParser {
    /**
     * Auto-extracts the first 4-8 digit numeric code from the text.
     */
    fun extractOtpCode(body: String): String {
        val pattern = Pattern.compile("\\b\\d{4,8}\\b")
        val matcher = pattern.matcher(body)
        return if (matcher.find()) matcher.group() else ""
    }

    /**
     * Formats an outgoing message payload based on sender/body regex capture groups and a template string.
     */
    fun formatPayload(
        sender: String,
        body: String,
        senderRegex: String = ".*",
        bodyParseRegex: String = "\\b\\d{4,8}\\b",
        template: String = "\${body}",
    ): String {
        if (template.isBlank()) return body

        val extractedCode = extractOtpCode(body)
        var result = template
            .replace("\${sender}", sender)
            .replace("{sender}", sender)
            .replace("\${body}", body)
            .replace("{body}", body)
            .replace("\${message}", body)
            .replace("{message}", body)
            .replace("\${text}", body)
            .replace("{text}", body)
            .replace("\${otp}", extractedCode)
            .replace("{otp}", extractedCode)
            .replace("\${code}", extractedCode)
            .replace("{code}", extractedCode)

        result = replaceSenderGroups(sender, senderRegex, result)
        result = replaceBodyGroups(body, bodyParseRegex, result)
        return result
    }

    private fun replaceSenderGroups(sender: String, senderRegex: String, current: String): String {
        if (senderRegex.isBlank() || senderRegex == ".*") return current
        return try {
            val matcher = Pattern.compile(senderRegex).matcher(sender)
            if (matcher.find()) applySenderGroupReplacements(matcher, current) else current
        } catch (_: PatternSyntaxException) {
            current
        }
    }

    private fun applySenderGroupReplacements(matcher: Matcher, initial: String): String {
        var updated = initial
        for (i in 1..matcher.groupCount()) {
            val groupValue = matcher.group(i) ?: ""
            updated = updated
                .replace("\${senderGroup$i}", groupValue)
                .replace("{senderGroup$i}", groupValue)
                .replace("\${sGroup$i}", groupValue)
                .replace("{sGroup$i}", groupValue)
        }
        return updated
    }

    private fun replaceBodyGroups(body: String, bodyParseRegex: String, current: String): String {
        if (bodyParseRegex.isBlank()) return current
        return try {
            val matcher = Pattern.compile(bodyParseRegex).matcher(body)
            if (matcher.find()) applyBodyGroupReplacements(matcher, current) else current
        } catch (_: PatternSyntaxException) {
            current
        }
    }

    private fun applyBodyGroupReplacements(matcher: Matcher, initial: String): String {
        var updated = initial
        for (i in 0..matcher.groupCount()) {
            val groupValue = matcher.group(i) ?: ""
            updated = if (i == 0) {
                updated.replace("\${match}", groupValue).replace("{match}", groupValue)
            } else {
                updated
                    .replace("\${group$i}", groupValue)
                    .replace("{group$i}", groupValue)
                    .replace("\${g$i}", groupValue)
                    .replace("{g$i}", groupValue)
            }
        }
        return updated
    }
}
