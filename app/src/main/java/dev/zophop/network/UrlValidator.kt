package dev.zophop.network

import java.net.URI

object UrlValidator {
    /**
     * Validates whether a URL meets security requirements.
     * Enforces HTTPS by default. HTTP is rejected except for localhost,
     * or private/Tailscale IP addresses if allowInsecurePrivateHttp is true.
     */
    fun isValidUrl(
        url: String,
        allowInsecurePrivateHttp: Boolean = false,
    ): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false

        val uri = try {
            URI.create(trimmed)
        } catch (_: IllegalArgumentException) {
            null
        }

        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")

        return when {
            uri == null || scheme == null || host == null -> false
            scheme == "https" -> true
            scheme == "http" -> isLocalhost(host) || (allowInsecurePrivateHttp && (isPrivateIp(host) || isLocalDomain(host)))
            else -> false
        }
    }

    private fun isLocalhost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "::1"

    private fun isLocalDomain(host: String): Boolean =
        host.endsWith(".local") ||
            host.endsWith(".lan") ||
            host.endsWith(".internal") ||
            host.endsWith(".ts.net") ||
            host.endsWith(".tailscale.net")

    private fun isPrivateIp(host: String): Boolean {
        val parts = host.split(".")
        val ipParts = if (parts.size == 4) parts.mapNotNull { it.toIntOrNull() } else emptyList()
        if (ipParts.size != 4) return false

        val p1 = ipParts[0]
        val p2 = ipParts[1]
        return when (p1) {
            10 -> true
            172 -> p2 in 16..31
            192 -> p2 == 168
            100 -> p2 in 64..127
            else -> false
        }
    }
}
