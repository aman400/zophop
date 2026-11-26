package dev.zophop.domain.engine

object HttpHeaderUtils {
    private val HEADER_NAME_REGEX = Regex("^[!#\$%&'*+\\-.^_`|~0-9A-Za-z]+$")
    private val CONTROL_CHARS_REGEX = Regex("[\\r\\n\\u0000]")

    fun sanitizeHeaderValue(value: String): String = CONTROL_CHARS_REGEX.replace(value, "").trim()

    fun isValidHeaderName(name: String): Boolean = HEADER_NAME_REGEX.matches(name)

    /**
     * Parses a custom HTTP headers string into a list of [Pair<String, String>] (key, value).
     * Correctly handles multi-valued headers containing commas (such as Accept, Cache-Control, or User-Agent)
     * without improperly splitting within header values.
     */
    fun parseHeaders(headersString: String): List<Pair<String, String>> {
        if (headersString.isBlank()) return emptyList()

        val lines =
            if (headersString.contains("\n")) {
                headersString.split("\r\n", "\n")
            } else if (headersString.contains(",") && Regex(",\\s*[a-zA-Z0-9_-]+:").containsMatchIn(headersString)) {
                headersString.split(Regex(",(?=\\s*[a-zA-Z0-9_-]+:)"))
            } else {
                listOf(headersString)
            }

        return lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                val parts = trimmed.split(":", limit = 2)
                val name = parts.getOrNull(0)?.trim().orEmpty()
                if (parts.size == 2 && isValidHeaderName(name)) {
                    name to sanitizeHeaderValue(parts[1])
                } else {
                    null
                }
            }
        }
    }
}
