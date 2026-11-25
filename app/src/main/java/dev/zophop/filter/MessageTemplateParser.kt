package dev.zophop.filter

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object MessageTemplateParser {
    private val TEMPLATE_TOKEN_REGEX = Regex("""\$\{([^{}]+)\}|\{([^{}]+)\}""")

    /**
     * Auto-extracts the first 4-8 digit numeric code from the text.
     */
    fun extractOtpCode(body: String): String {
        val pattern = Pattern.compile("\\b\\d{4,8}\\b")
        val matcher = pattern.matcher(body)
        return if (matcher.find()) matcher.group() else ""
    }

    /**
     * Formats an outgoing message payload based on sender/body regex capture groups and a template string
     * using single-pass substitution so untrusted values cannot trigger second-order expansion.
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
        val variables =
            buildMap {
                put("sender", sender)
                put("body", body)
                put("message", body)
                put("text", body)
                put("otp", extractedCode)
                put("code", extractedCode)
                collectSenderGroups(sender, senderRegex, this)
                collectBodyGroups(body, bodyParseRegex, this)
            }

        return TEMPLATE_TOKEN_REGEX.replace(template) { matchResult ->
            val key = matchResult.groupValues[1].ifEmpty { matchResult.groupValues[2] }
            variables[key] ?: matchResult.value
        }
    }

    private fun collectSenderGroups(
        sender: String,
        senderRegex: String,
        variables: MutableMap<String, String>,
    ) {
        if (senderRegex.isBlank() || senderRegex == ".*") return
        try {
            val matcher = Pattern.compile(senderRegex).matcher(sender)
            if (matcher.find()) {
                populateSenderGroups(matcher, variables)
            }
        } catch (_: PatternSyntaxException) {
            // Invalid user regex; ignore capture groups
        }
    }

    private fun populateSenderGroups(
        matcher: Matcher,
        variables: MutableMap<String, String>,
    ) {
        for (i in 1..matcher.groupCount()) {
            val groupValue = matcher.group(i) ?: ""
            variables["senderGroup$i"] = groupValue
            variables["sGroup$i"] = groupValue
        }
    }

    private fun collectBodyGroups(
        body: String,
        bodyParseRegex: String,
        variables: MutableMap<String, String>,
    ) {
        if (bodyParseRegex.isBlank()) return
        try {
            val matcher = Pattern.compile(bodyParseRegex).matcher(body)
            if (matcher.find()) {
                populateBodyGroups(matcher, variables)
            }
        } catch (_: PatternSyntaxException) {
            // Invalid user regex; ignore capture groups
        }
    }

    private fun populateBodyGroups(
        matcher: Matcher,
        variables: MutableMap<String, String>,
    ) {
        for (i in 0..matcher.groupCount()) {
            val groupValue = matcher.group(i) ?: ""
            if (i == 0) {
                variables["match"] = groupValue
            } else {
                variables["group$i"] = groupValue
                variables["g$i"] = groupValue
            }
        }
    }
}
