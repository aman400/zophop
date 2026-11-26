package dev.zophop.plugin.api

/**
 * Shared helpers for building consistent, redacted, multi-section node execution logs.
 */
object ExecutionLogFormatter {
    private val SENSITIVE_HEADER_KEYS =
        setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "apikey",
            "x-gotify-key",
            "cookie",
            "set-cookie",
            "x-auth-token",
            "x-access-token",
            "x-secret",
            "x-token",
            "token",
            "secret",
            "password",
            "private-key",
            "x-private-key",
        )

    private val SENSITIVE_URL_PARAM_REGEX =
        Regex(
            "(?i)([?&](?:token|apptoken|app_token|bottoken|bot_token|access_token|refresh_token|" +
                "apikey|api_key|secret|client_secret|password|passwd|pwd|pass|auth|key|signature|sig|" +
                "webhook_secret)=)([^&#\\s]+)",
        )

    private val URL_USERINFO_REGEX = Regex("(?i)(https?://)([^/@\\s]+)@")
    private val TELEGRAM_BOT_PATH_REGEX = Regex("(?i)(/bot)([^/\\s?#]+)(/)")
    private val HEADER_BEARER_REGEX = Regex("(?i)\\b(Bearer|Basic|Token)\\s+([A-Za-z0-9._~+/=-]{6,})")
    private val SENSITIVE_HEADER_LINE_REGEX =
        Regex("(?im)^((?:authorization|proxy-authorization|x-api-key|api-key|apikey|x-gotify-key|x-auth-token|x-access-token|cookie|set-cookie)\\s*:\\s*)(.+)$")

    fun previewText(
        value: String,
        maxChars: Int = 400,
    ): String {
        val trimmed = value.trim()
        return when {
            trimmed.isEmpty() -> "(empty)"
            trimmed.length <= maxChars -> trimmed
            else -> trimmed.take(maxChars) + "... [truncated, ${trimmed.length} chars total]"
        }
    }

    fun isSensitiveHeader(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower in SENSITIVE_HEADER_KEYS ||
            lower.contains("token") ||
            lower.contains("secret") ||
            lower.contains("password") ||
            lower.contains("auth") ||
            lower.contains("api-key") ||
            lower.contains("apikey") ||
            lower.contains("cookie") ||
            lower.contains("private-key")
    }

    fun redactHeaderValue(
        name: String,
        value: String,
    ): String {
        if (!isSensitiveHeader(name)) return value
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("Bearer ", ignoreCase = true) -> "Bearer ***"
            trimmed.startsWith("Basic ", ignoreCase = true) -> "Basic ***"
            trimmed.startsWith("Token ", ignoreCase = true) -> "Token ***"
            else -> "***"
        }
    }

    fun redactUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return rawUrl
        var redacted = rawUrl.replace(URL_USERINFO_REGEX, "$1***@")
        redacted = redacted.replace(TELEGRAM_BOT_PATH_REGEX, "$1***$3")
        redacted = redacted.replace(SENSITIVE_URL_PARAM_REGEX, "$1***")
        return redacted
    }

    fun redactSecrets(
        text: String,
        explicitSecrets: Collection<String> = emptyList(),
    ): String {
        if (text.isBlank()) return text
        var result = text
        val candidates =
            explicitSecrets
                .flatMap { secret ->
                    val trimmed = secret.trim()
                    if (trimmed.length < 4) {
                        emptyList()
                    } else {
                        val encoded =
                            try {
                                java.net.URLEncoder.encode(trimmed, Charsets.UTF_8.name())
                            } catch (_: Exception) {
                                trimmed
                            }
                        listOf(trimmed, encoded).distinct()
                    }
                }.sortedByDescending { it.length }

        for (secret in candidates) {
            result = result.replace(secret, "***")
        }
        result = result.replace(URL_USERINFO_REGEX, "$1***@")
        result = result.replace(TELEGRAM_BOT_PATH_REGEX, "$1***$3")
        result = result.replace(SENSITIVE_URL_PARAM_REGEX, "$1***")
        result = result.replace(SENSITIVE_HEADER_LINE_REGEX) { match ->
            val prefix = match.groupValues[1]
            val headerName = prefix.substringBefore(':').trim()
            prefix + redactHeaderValue(headerName, match.groupValues[2])
        }
        result = result.replace(HEADER_BEARER_REGEX) { match -> "${match.groupValues[1]} ***" }
        return result
    }

    fun formatHeadersBlock(headers: List<Pair<String, String>>): String =
        headers
            .filter { (k, _) -> k.isNotBlank() }
            .joinToString("\n") { (k, v) -> "$k: ${redactHeaderValue(k, v)}" }

    fun buildStructuredLog(
        summary: String,
        sections: List<Pair<String, String>>,
    ): String =
        buildString {
            append(summary.trim())
            for ((title, body) in sections) {
                val cleanBody = body.trim()
                if (cleanBody.isNotEmpty()) {
                    append("\n--- ")
                    append(title.trim())
                    append(" ---\n")
                    append(cleanBody)
                }
            }
        }
}
