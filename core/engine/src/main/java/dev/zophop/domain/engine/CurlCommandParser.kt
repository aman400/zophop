package dev.zophop.domain.engine

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ParsedCurlCommand(
    val url: String,
    val method: String,
    val queryParams: String,
    val headers: String,
    val bodyType: String,
    val bodyTemplate: String,
)

sealed class CurlParseResult {
    data class Success(
        val command: ParsedCurlCommand,
    ) : CurlParseResult()

    data class Error(
        val message: String,
    ) : CurlParseResult()
}

/**
 * Parses copied shell `curl` commands into HTTP node configuration fields.
 */
object CurlCommandParser {
    private val SUPPORTED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

    private val IGNORED_VALUE_FLAGS =
        setOf(
            "-o",
            "--output",
            "--connect-timeout",
            "-m",
            "--max-time",
            "--retry",
            "--retry-delay",
            "--retry-max-time",
            "-x",
            "--proxy",
            "--resolve",
            "--cacert",
            "--capath",
            "--cert",
            "--key",
            "-w",
            "--write-out",
            "-c",
            "--cookie-jar",
            "-D",
            "--dump-header",
            "-T",
            "--upload-file",
            "--interface",
            "--limit-rate",
            "--max-redirs",
            "--proto",
        )

    private val DATA_FLAGS =
        setOf(
            "-d",
            "--data",
            "--data-raw",
            "--data-ascii",
            "--data-binary",
            "--data-urlencode",
            "-F",
            "--form",
        )

    fun parse(rawInput: String): CurlParseResult {
        val trimmed = rawInput.trim()
        val earlyError = validateAndTokenize(trimmed)
        return when (earlyError) {
            is EitherTokensOrError.Err -> CurlParseResult.Error(earlyError.message)
            is EitherTokensOrError.Tokens -> parseTokenList(earlyError.tokens)
        }
    }

    private sealed class EitherTokensOrError {
        data class Tokens(
            val tokens: List<String>,
        ) : EitherTokensOrError()

        data class Err(
            val message: String,
        ) : EitherTokensOrError()
    }

    private fun validateAndTokenize(trimmed: String): EitherTokensOrError {
        if (trimmed.isBlank()) {
            return EitherTokensOrError.Err("Paste a cURL command to import.")
        }
        return try {
            val tokens = tokenizeShell(trimmed)
            if (tokens.isEmpty()) {
                EitherTokensOrError.Err("Paste a cURL command to import.")
            } else {
                EitherTokensOrError.Tokens(tokens)
            }
        } catch (e: IllegalArgumentException) {
            EitherTokensOrError.Err(e.message ?: "Invalid quoting in cURL command.")
        }
    }

    private fun parseTokenList(tokens: List<String>): CurlParseResult {
        val startIndex = resolveCommandStartIndex(tokens)
        val result: CurlParseResult
        if (startIndex == null) {
            result = CurlParseResult.Error("Command must start with 'curl' or HTTP flags/URL.")
        } else {
            val state = CurlParseState()
            parseTokens(tokens, startIndex, state)
            result =
                when {
                    state.errorMessage != null -> CurlParseResult.Error(state.errorMessage!!)
                    state.url.isNullOrBlank() -> CurlParseResult.Error("No HTTP/HTTPS URL found in the cURL command.")
                    else -> buildParsedResult(state.url!!, state)
                }
        }
        return result
    }

    private fun resolveCommandStartIndex(tokens: List<String>): Int? {
        val first = tokens.first()
        val executable = first.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return when {
            executable == "curl" || executable == "curl.exe" -> 1
            first.startsWith("-") ||
                first.startsWith("http://", ignoreCase = true) ||
                first.startsWith("https://", ignoreCase = true) -> 0
            else -> null
        }
    }

    private fun parseTokens(
        tokens: List<String>,
        startIndex: Int,
        state: CurlParseState,
    ) {
        var idx = startIndex
        while (idx < tokens.size && state.errorMessage == null) {
            val token = tokens[idx]
            idx = processSingleToken(token, tokens, idx, state)
        }
    }

    private fun processSingleToken(
        token: String,
        tokens: List<String>,
        idx: Int,
        state: CurlParseState,
    ): Int {
        if (token == "--") {
            val nextUrl = tokens.getOrNull(idx + 1)
            if (nextUrl != null && state.url == null) state.url = nextUrl
            return idx + 2
        }

        if (token.startsWith("--")) {
            return processLongFlag(token, tokens, idx, state)
        }

        if (token.startsWith("-") && token.length > 1) {
            return processShortFlag(token, tokens, idx, state)
        }

        if (state.url == null) {
            state.url = token
        }
        return idx + 1
    }

    private val LONG_VALUE_FLAGS =
        setOf("--request", "--url", "--header", "--user-agent", "--referer", "--cookie", "--user")

    private fun processLongFlag(
        token: String,
        tokens: List<String>,
        idx: Int,
        state: CurlParseState,
    ): Int {
        val eqIdx = token.indexOf('=')
        val flagName = if (eqIdx > 0) token.substring(0, eqIdx) else token
        val inlineVal = if (eqIdx > 0) token.substring(eqIdx + 1) else null

        return when (flagName) {
            "--get" -> {
                state.forceGet = true
                idx + 1
            }
            "--head" -> {
                state.forceHead = true
                idx + 1
            }
            in LONG_VALUE_FLAGS, in DATA_FLAGS, in IGNORED_VALUE_FLAGS -> {
                val (value, next) = consumeLongFlagValue(flagName, inlineVal, tokens, idx, state)
                if (value != null) {
                    applyLongFlagValue(flagName, value, state)
                }
                next
            }
            else -> idx + 1
        }
    }

    private fun consumeLongFlagValue(
        flagName: String,
        inlineVal: String?,
        tokens: List<String>,
        idx: Int,
        state: CurlParseState,
    ): Pair<String?, Int> =
        when {
            inlineVal != null -> inlineVal to (idx + 1)
            idx + 1 < tokens.size -> tokens[idx + 1] to (idx + 2)
            else -> {
                state.errorMessage = "Missing value for flag '$flagName'."
                null to (idx + 1)
            }
        }

    private fun applyLongFlagValue(
        flagName: String,
        value: String,
        state: CurlParseState,
    ) {
        when (flagName) {
            "--request" -> state.explicitMethod = value.trim().uppercase()
            "--url" -> state.url = value.trim()
            "--header" -> addRawHeader(state, value)
            "--user-agent" -> upsertHeader(state, "User-Agent", value)
            "--referer" -> upsertHeader(state, "Referer", value)
            "--cookie" -> if (value.contains("=")) upsertHeader(state, "Cookie", value)
            "--user" -> addBasicAuthHeader(state, value)
            in DATA_FLAGS -> recordDataArg(state, flagName, value)
        }
    }

    private val SHORT_VALUE_FLAGS =
        setOf("-X", "-H", "-d", "-F", "-u", "-A", "-e", "-b", "-o", "-m", "-x", "-w", "-c", "-D", "-T")

    private fun processShortFlag(
        token: String,
        tokens: List<String>,
        idx: Int,
        state: CurlParseState,
    ): Int {
        var charPos = 1
        var nextIndex = idx + 1
        var consumedValueFlag = false
        while (charPos < token.length && !consumedValueFlag) {
            val shortFlag = "-${token[charPos]}"
            when (shortFlag) {
                in SHORT_VALUE_FLAGS -> {
                    nextIndex = consumeAndApplyShortFlag(shortFlag, token.substring(charPos + 1), tokens, idx, state)
                    consumedValueFlag = true
                }
                "-G" -> state.forceGet = true
                "-I" -> state.forceHead = true
            }
            charPos++
        }
        return nextIndex
    }

    private fun consumeAndApplyShortFlag(
        shortFlag: String,
        rest: String,
        tokens: List<String>,
        idx: Int,
        state: CurlParseState,
    ): Int =
        when {
            rest.isNotEmpty() -> {
                applyShortFlagValue(shortFlag, rest, state)
                idx + 1
            }
            idx + 1 < tokens.size -> {
                applyShortFlagValue(shortFlag, tokens[idx + 1], state)
                idx + 2
            }
            else -> {
                state.errorMessage = "Missing value for flag '$shortFlag'."
                idx + 1
            }
        }

    private fun applyShortFlagValue(
        shortFlag: String,
        value: String,
        state: CurlParseState,
    ) {
        when (shortFlag) {
            "-X" -> state.explicitMethod = value.trim().uppercase()
            "-H" -> addRawHeader(state, value)
            "-d", "-F" -> recordDataArg(state, shortFlag, value)
            "-u" -> addBasicAuthHeader(state, value)
            "-A" -> upsertHeader(state, "User-Agent", value)
            "-e" -> upsertHeader(state, "Referer", value)
            "-b" -> if (value.contains("=")) upsertHeader(state, "Cookie", value)
        }
    }

    private fun recordDataArg(
        state: CurlParseState,
        flag: String,
        rawValue: String,
    ) {
        state.hasDataFlag = true
        when (flag) {
            "--data-urlencode" -> {
                state.isUrlEncodedData = true
                state.dataParts.add(encodeDataUrlParam(rawValue))
            }
            "-F", "--form" -> {
                state.isFormFlag = true
                state.dataParts.add(rawValue)
            }
            else -> state.dataParts.add(rawValue)
        }
    }

    private fun encodeDataUrlParam(raw: String): String {
        val eqIdx = raw.indexOf('=')
        return if (eqIdx >= 0) {
            val key = raw.substring(0, eqIdx)
            val value = raw.substring(eqIdx + 1)
            val encodedVal = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
            "$key=$encodedVal"
        } else {
            URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
    }

    private fun addRawHeader(
        state: CurlParseState,
        rawHeader: String,
    ) {
        val colonIdx = rawHeader.indexOf(':')
        if (colonIdx <= 0) return
        val name = rawHeader.substring(0, colonIdx).trim()
        val value = HttpHeaderUtils.sanitizeHeaderValue(rawHeader.substring(colonIdx + 1))
        if (HttpHeaderUtils.isValidHeaderName(name) && value.isNotEmpty()) {
            upsertHeader(state, name, value)
        }
    }

    private fun upsertHeader(
        state: CurlParseState,
        name: String,
        value: String,
    ) {
        val existingIdx = state.headers.indexOfFirst { it.first.equals(name, ignoreCase = true) }
        if (existingIdx >= 0) {
            state.headers[existingIdx] = name to value
        } else {
            state.headers.add(name to value)
        }
    }

    private fun addBasicAuthHeader(
        state: CurlParseState,
        userPass: String,
    ) {
        val encoded = Base64.getEncoder().encodeToString(userPass.toByteArray(StandardCharsets.UTF_8))
        upsertHeader(state, "Authorization", "Basic $encoded")
    }

    private fun buildParsedResult(
        rawUrl: String,
        state: CurlParseState,
    ): CurlParseResult {
        val normalizedUrl = normalizeScheme(rawUrl.trim())
        val isHttpUrl =
            normalizedUrl.startsWith("http://", ignoreCase = true) ||
                normalizedUrl.startsWith("https://", ignoreCase = true)
        if (!isHttpUrl) {
            return CurlParseResult.Error("URL must start with http:// or https://.")
        }

        val withoutFragment = normalizedUrl.substringBefore('#')
        val qIdx = withoutFragment.indexOf('?')
        val baseUrl = if (qIdx >= 0) withoutFragment.substring(0, qIdx) else withoutFragment
        val urlQuery = if (qIdx >= 0) withoutFragment.substring(qIdx + 1) else ""

        return if (baseUrl.removePrefix("http://").removePrefix("https://").isBlank()) {
            CurlParseResult.Error("URL host cannot be empty.")
        } else {
            createSuccessResult(baseUrl, urlQuery, state)
        }
    }

    private fun createSuccessResult(
        baseUrl: String,
        urlQuery: String,
        state: CurlParseState,
    ): CurlParseResult.Success {
        val method = resolveMethod(state)
        val combinedBody = state.dataParts.joinToString("&")
        val queryParamsList = mutableListOf<String>()
        if (urlQuery.isNotBlank()) {
            queryParamsList.add(urlQuery)
        }
        val finalBody =
            if (state.forceGet && combinedBody.isNotBlank()) {
                queryParamsList.add(combinedBody)
                ""
            } else {
                combinedBody
            }

        val bodyType = resolveBodyType(state, method, finalBody)
        val headersStr = state.headers.joinToString("\n") { (k, v) -> "$k: $v" }

        return CurlParseResult.Success(
            ParsedCurlCommand(
                url = baseUrl,
                method = method,
                queryParams = queryParamsList.filter { it.isNotBlank() }.joinToString("&"),
                headers = headersStr,
                bodyType = bodyType,
                bodyTemplate = finalBody,
            ),
        )
    }

    private fun normalizeScheme(url: String): String {
        val hasScheme = url.contains("://")
        return if (!hasScheme && url.contains('.') && !url.contains(' ')) {
            "http://$url"
        } else {
            url
        }
    }

    private fun resolveMethod(state: CurlParseState): String {
        val explicit = state.explicitMethod?.takeIf { it in SUPPORTED_METHODS }
        return when {
            explicit != null -> explicit
            state.forceHead -> "HEAD"
            state.forceGet -> "GET"
            state.hasDataFlag -> "POST"
            else -> "GET"
        }
    }

    private fun resolveBodyType(
        state: CurlParseState,
        method: String,
        body: String,
    ): String {
        if (body.isBlank() || method == "GET" || method == "HEAD") {
            return "NONE"
        }
        val contentType =
            state.headers
                .firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
                ?.second
                ?.lowercase()
                .orEmpty()

        val trimmedBody = body.trimStart()
        return when {
            contentType.contains("json") -> "JSON"
            contentType.contains("x-www-form-urlencoded") || contentType.contains("multipart/form-data") -> "FORM_DATA"
            state.isUrlEncodedData || state.isFormFlag -> "FORM_DATA"
            trimmedBody.startsWith("{") || trimmedBody.startsWith("[") -> "JSON"
            else -> "RAW"
        }
    }

    private fun tokenizeShell(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var hasToken = false
        var i = 0

        while (i < input.length) {
            val ch = input[i]
            when {
                ch.isWhitespace() -> {
                    if (hasToken) {
                        tokens.add(current.toString())
                        current.clear()
                        hasToken = false
                    }
                    i++
                }
                ch == '\\' && i + 1 < input.length && (input[i + 1] == '\n' || input[i + 1] == '\r') -> {
                    i += 2
                    if (i < input.length && input[i - 1] == '\r' && input[i] == '\n') i++
                }
                ch == '\'' -> {
                    hasToken = true
                    i = readSingleQuoted(input, i + 1, current)
                }
                ch == '"' -> {
                    hasToken = true
                    i = readDoubleQuoted(input, i + 1, current)
                }
                ch == '$' && i + 1 < input.length && input[i + 1] == '\'' -> {
                    hasToken = true
                    i = readAnsiCQuoted(input, i + 2, current)
                }
                ch == '\\' && i + 1 < input.length -> {
                    hasToken = true
                    current.append(input[i + 1])
                    i += 2
                }
                else -> {
                    hasToken = true
                    current.append(ch)
                    i++
                }
            }
        }
        if (hasToken) {
            tokens.add(current.toString())
        }
        return tokens
    }

    private fun readSingleQuoted(
        input: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var i = start
        while (i < input.length) {
            val ch = input[i]
            if (ch == '\'') return i + 1
            out.append(ch)
            i++
        }
        throw IllegalArgumentException("Unclosed single quote (') in cURL command.")
    }

    private fun readDoubleQuoted(
        input: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var i = start
        while (i < input.length) {
            val ch = input[i]
            if (ch == '"') return i + 1
            if (ch == '\\' && i + 1 < input.length) {
                i = readDoubleQuotedEscape(input, i, out)
            } else {
                out.append(ch)
                i++
            }
        }
        throw IllegalArgumentException("Unclosed double quote (\") in cURL command.")
    }

    private fun readDoubleQuotedEscape(
        input: String,
        slashIdx: Int,
        out: StringBuilder,
    ): Int {
        val next = input[slashIdx + 1]
        return when (next) {
            '\n' -> slashIdx + 2
            '\r' -> slashIdx + if (slashIdx + 2 < input.length && input[slashIdx + 2] == '\n') 3 else 2
            '"', '\\', '$', '`' -> {
                out.append(next)
                slashIdx + 2
            }
            else -> {
                out.append('\\').append(next)
                slashIdx + 2
            }
        }
    }

    private fun readAnsiCQuoted(
        input: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var i = start
        while (i < input.length) {
            val ch = input[i]
            if (ch == '\'') return i + 1
            if (ch == '\\' && i + 1 < input.length) {
                val next = input[i + 1]
                val decoded =
                    when (next) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '\\', '\'', '"' -> next
                        else -> null
                    }
                if (decoded != null) {
                    out.append(decoded)
                    i += 2
                } else {
                    out.append(ch)
                    i++
                }
            } else {
                out.append(ch)
                i++
            }
        }
        throw IllegalArgumentException("Unclosed \$'...' quote in cURL command.")
    }

    private class CurlParseState {
        var url: String? = null
        var explicitMethod: String? = null
        var forceGet: Boolean = false
        var forceHead: Boolean = false
        var hasDataFlag: Boolean = false
        var isFormFlag: Boolean = false
        var isUrlEncodedData: Boolean = false
        val headers: MutableList<Pair<String, String>> = mutableListOf()
        val dataParts: MutableList<String> = mutableListOf()
        var errorMessage: String? = null
    }
}
