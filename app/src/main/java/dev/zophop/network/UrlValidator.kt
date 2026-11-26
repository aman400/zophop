package dev.zophop.network

import java.net.URI

object UrlValidator {
    /**
     * Validates whether a URL meets security requirements.
     * Enforces HTTPS by default and rejects embedded credentials (`user:pass@host`)
     * or link-local metadata addresses (`169.254.0.0/16`, `fe80::/10`).
     * HTTP is rejected except for localhost, or private/Tailscale IP addresses
     * when [allowInsecurePrivateHttp] is true.
     */
    fun isValidUrl(
        url: String,
        allowInsecurePrivateHttp: Boolean = false,
    ): Boolean {
        val trimmed = url.trim()
        val uri =
            if (trimmed.isNotBlank()) {
                try {
                    URI.create(trimmed)
                } catch (_: IllegalArgumentException) {
                    null
                }
            } else {
                null
            }
        return uri != null && isValidParsedUri(uri, allowInsecurePrivateHttp)
    }

    private fun isValidParsedUri(
        uri: URI,
        allowInsecurePrivateHttp: Boolean,
    ): Boolean {
        val hasEmbeddedUserInfo = uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true
        val scheme = uri.scheme?.lowercase()
        val rawHost = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.') ?: ""
        val unwrappedHost = unwrapIpv4MappedIpv6(rawHost) ?: rawHost
        val hostAllowed =
            !hasEmbeddedUserInfo &&
                rawHost.isNotBlank() &&
                !isMetadataOrLinkLocal(unwrappedHost)
        return hostAllowed &&
            when (scheme) {
                "https" -> true
                "http" -> isLocalhost(unwrappedHost) || (allowInsecurePrivateHttp && (isPrivateIp(unwrappedHost) || isLocalDomain(rawHost)))
                else -> false
            }
    }

    private fun unwrapIpv4MappedIpv6(host: String): String? {
        val mapped = host.removePrefix("::ffff:")
        val hexParts = mapped.split(":")
        val high = hexParts.getOrNull(0)?.toIntOrNull(16)
        val low = hexParts.getOrNull(1)?.toIntOrNull(16)
        return when {
            !host.startsWith("::ffff:") -> null
            parseStrictIpv4(mapped) != null -> mapped
            hexParts.size == 2 && high != null && low != null && high in 0..0xFFFF && low in 0..0xFFFF ->
                "${(high ushr 8) and 0xFF}.${high and 0xFF}.${(low ushr 8) and 0xFF}.${low and 0xFF}"
            else -> null
        }
    }

    private fun isMetadataOrLinkLocal(host: String): Boolean {
        val ipv4 = parseStrictIpv4(host)
        if (ipv4 != null) {
            return ipv4[0] == 169 && ipv4[1] == 254
        }
        val firstSegment = host.substringBefore(':').toIntOrNull(16)
        return host.contains(':') && firstSegment != null && (firstSegment and 0xFFC0) == 0xFE80
    }

    private fun isLocalhost(host: String): Boolean =
        host == "localhost" ||
            host == "127.0.0.1" ||
            host == "::1" ||
            host == "0:0:0:0:0:0:0:1"

    private fun isLocalDomain(host: String): Boolean =
        host.endsWith(".local") ||
            host.endsWith(".lan") ||
            host.endsWith(".internal") ||
            host.endsWith(".ts.net") ||
            host.endsWith(".tailscale.net")

    private fun isPrivateIp(host: String): Boolean {
        val ipParts = parseStrictIpv4(host) ?: return false
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

    private fun parseStrictIpv4(host: String): IntArray? {
        val parts = host.split(".")
        val parsed =
            if (parts.size == 4) {
                parts.mapNotNull { part ->
                    if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) {
                        null
                    } else {
                        part.toIntOrNull()?.takeIf { it in 0..255 }
                    }
                }
            } else {
                emptyList()
            }
        return if (parsed.size == 4) parsed.toIntArray() else null
    }
}
