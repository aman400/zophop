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
        )

    fun previewText(
        value: String,
        maxChars: Int = 400,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "(empty)"
        return if (trimmed.length <= maxChars) {
            trimmed
        } else {
            trimmed.take(maxChars) + "... [truncated, ${trimmed.length} chars total]"
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
            lower.contains("apikey")
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
