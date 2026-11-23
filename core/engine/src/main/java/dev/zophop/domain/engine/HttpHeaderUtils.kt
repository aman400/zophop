package dev.zophop.domain.engine

object HttpHeaderUtils {
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
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    parts[0].trim() to parts[1].trim()
                } else {
                    null
                }
            }
        }
    }
}
