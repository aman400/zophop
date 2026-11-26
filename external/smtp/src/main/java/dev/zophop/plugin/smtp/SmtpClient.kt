package dev.zophop.plugin.smtp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

fun interface SmtpTransport {
    @Throws(SmtpException::class, IOException::class)
    fun sendEmail(request: SmtpMessageRequest)
}

enum class SmtpSecurityMode(
    val defaultPort: Int,
) {
    STARTTLS(587),
    SMTPS(465),
    PLAIN(25),
    ;

    companion object {
        fun fromString(raw: String?): SmtpSecurityMode =
            when (raw?.trim()?.uppercase(Locale.US)) {
                "SMTPS", "SSL", "SSL/TLS", "TLS" -> SMTPS
                "PLAIN", "NONE" -> PLAIN
                else -> STARTTLS
            }
    }
}

data class SmtpMessageRequest(
    val host: String,
    val port: Int,
    val security: SmtpSecurityMode,
    val username: String = "",
    val password: String = "",
    val fromAddress: String,
    val toAddresses: List<String>,
    val ccAddresses: List<String> = emptyList(),
    val subject: String,
    val body: String,
    val contentType: String = CONTENT_TYPE_PLAIN,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val CONTENT_TYPE_PLAIN = "text/plain"
        const val CONTENT_TYPE_HTML = "text/html"
        const val DEFAULT_TIMEOUT_MS = 10_000
    }
}

class SmtpException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class SmtpClient(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : SmtpTransport {
    @Throws(SmtpException::class, IOException::class)
    override fun sendEmail(request: SmtpMessageRequest) {
        validateRequest(request)
        val fromEnvelope = extractEnvelopeAddress(request.fromAddress)
        val toEnvelopes = request.toAddresses.map { extractEnvelopeAddress(it) }
        val ccEnvelopes = request.ccAddresses.map { extractEnvelopeAddress(it) }
        val allRecipients = (toEnvelopes + ccEnvelopes).distinct()
        val ehloDomain = deriveEhloDomain(fromEnvelope)

        val tcpSocket = connectTcpSocket(request)
        var activeSocket: Socket = tcpSocket
        try {
            if (request.security == SmtpSecurityMode.SMTPS) {
                activeSocket = upgradeToTls(tcpSocket, request.host, request.port, request.timeoutMs)
            }
            var session = openSessionStreams(activeSocket)
            readExpectedResponse(session.reader, 220, "greeting")

            var ehloLines = sendEhlo(session, ehloDomain)
            if (request.security == SmtpSecurityMode.STARTTLS) {
                sendCommand(session.writer, "STARTTLS")
                readExpectedResponse(session.reader, 220, "STARTTLS")
                activeSocket = upgradeToTls(activeSocket, request.host, request.port, request.timeoutMs)
                session = openSessionStreams(activeSocket)
                ehloLines = sendEhlo(session, ehloDomain)
            }

            authenticateIfConfigured(session, request, ehloLines)
            sendEnvelopeAndPayload(session, request, fromEnvelope, allRecipients, ehloDomain)

            try {
                sendCommand(session.writer, "QUIT")
                readSmtpResponse(session.reader)
            } catch (_: IOException) {
            }
        } finally {
            try {
                activeSocket.close()
            } catch (_: IOException) {
            }
            if (activeSocket !== tcpSocket) {
                try {
                    tcpSocket.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun validateRequest(request: SmtpMessageRequest) {
        val hasUser = request.username.isNotBlank()
        val hasPass = request.password.isNotBlank()
        val error =
            when {
                request.host.isBlank() || request.host.contains('\r') || request.host.contains('\n') ->
                    "Invalid SMTP host"
                request.port !in 1..65_535 ->
                    "Invalid SMTP port: ${request.port}"
                request.fromAddress.isBlank() ->
                    "Sender email address is empty"
                request.toAddresses.isEmpty() ->
                    "Recipient email address is empty"
                hasUser != hasPass ->
                    "Both SMTP username and password are required when authentication is configured"
                else -> null
            }
        if (error != null) {
            throw SmtpException(error)
        }
    }

    private fun connectTcpSocket(request: SmtpMessageRequest): Socket {
        val socket = socketFactory.createSocket()
        socket.soTimeout = request.timeoutMs
        socket.connect(InetSocketAddress(request.host.trim(), request.port), request.timeoutMs)
        return socket
    }

    private fun upgradeToTls(
        underlying: Socket,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): Socket {
        val tlsSocket = sslSocketFactory.createSocket(underlying, host.trim(), port, true)
        tlsSocket.soTimeout = timeoutMs
        if (tlsSocket is SSLSocket) {
            tlsSocket.startHandshake()
        }
        return tlsSocket
    }

    private fun openSessionStreams(socket: Socket): SmtpSessionStreams =
        SmtpSessionStreams(
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)),
            writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)),
        )

    private fun sendEhlo(
        session: SmtpSessionStreams,
        ehloDomain: String,
    ): List<String> {
        sendCommand(session.writer, "EHLO $ehloDomain")
        val response = readExpectedResponse(session.reader, 250, "EHLO")
        return response.lines
    }

    private fun authenticateIfConfigured(
        session: SmtpSessionStreams,
        request: SmtpMessageRequest,
        ehloLines: List<String>,
    ) {
        if (request.username.isBlank() && request.password.isBlank()) return

        val mechanisms = parseAuthMechanisms(ehloLines)
        val preferLogin = "LOGIN" in mechanisms && "PLAIN" !in mechanisms

        if (preferLogin) {
            sendCommand(session.writer, "AUTH LOGIN")
            readExpectedResponse(session.reader, 334, "AUTH LOGIN")
            val userB64 = Base64.getEncoder().encodeToString(request.username.toByteArray(StandardCharsets.UTF_8))
            sendCommand(session.writer, userB64)
            readExpectedResponse(session.reader, 334, "AUTH LOGIN username")
            val passB64 = Base64.getEncoder().encodeToString(request.password.toByteArray(StandardCharsets.UTF_8))
            sendCommand(session.writer, passB64)
            readExpectedResponse(session.reader, 235, "AUTH LOGIN authentication")
        } else {
            val payload = "\u0000${request.username}\u0000${request.password}".toByteArray(StandardCharsets.UTF_8)
            val plainB64 = Base64.getEncoder().encodeToString(payload)
            sendCommand(session.writer, "AUTH PLAIN $plainB64")
            readExpectedResponse(session.reader, 235, "AUTH PLAIN authentication")
        }
    }

    private fun parseAuthMechanisms(ehloLines: List<String>): Set<String> =
        ehloLines
            .asSequence()
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.startsWith("AUTH ") || it.startsWith("AUTH=") }
            .flatMap { it.removePrefix("AUTH").trimStart(' ', '=').split(Regex("\\s+")).asSequence() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun sendEnvelopeAndPayload(
        session: SmtpSessionStreams,
        request: SmtpMessageRequest,
        fromEnvelope: String,
        recipients: List<String>,
        ehloDomain: String,
    ) {
        sendCommand(session.writer, "MAIL FROM:<$fromEnvelope>")
        readExpectedResponse(session.reader, 250, "MAIL FROM")

        for (rcpt in recipients) {
            sendCommand(session.writer, "RCPT TO:<$rcpt>")
            val resp = readSmtpResponse(session.reader)
            if (resp.code != 250 && resp.code != 251) {
                throw SmtpException("SMTP RCPT TO rejected (${resp.code}): ${resp.summary}")
            }
        }

        sendCommand(session.writer, "DATA")
        readExpectedResponse(session.reader, 354, "DATA")

        val rfc5322Message = buildRfc5322Message(request, ehloDomain)
        session.writer.write(rfc5322Message)
        session.writer.write("\r\n.\r\n")
        session.writer.flush()
        readExpectedResponse(session.reader, 250, "message delivery")
    }

    private fun buildRfc5322Message(
        request: SmtpMessageRequest,
        ehloDomain: String,
    ): String {
        val dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
        val messageId = "<${UUID.randomUUID()}@$ehloDomain>"
        val cleanFrom = sanitizeHeaderValue(request.fromAddress)
        val cleanTo = request.toAddresses.joinToString(", ") { sanitizeHeaderValue(it) }
        val cleanCc = request.ccAddresses.joinToString(", ") { sanitizeHeaderValue(it) }
        val cleanSubject = encodeHeaderIfNonAscii(sanitizeHeaderValue(request.subject))
        val contentType =
            if (request.contentType.equals(SmtpMessageRequest.CONTENT_TYPE_HTML, ignoreCase = true)) {
                SmtpMessageRequest.CONTENT_TYPE_HTML
            } else {
                SmtpMessageRequest.CONTENT_TYPE_PLAIN
            }
        val base64Body = encodeMimeBase64Body(request.body)

        return buildString {
            append("Date: ").append(dateHeader).append("\r\n")
            append("Message-ID: ").append(messageId).append("\r\n")
            append("From: ").append(cleanFrom).append("\r\n")
            append("To: ").append(cleanTo).append("\r\n")
            if (cleanCc.isNotBlank()) {
                append("Cc: ").append(cleanCc).append("\r\n")
            }
            append("Subject: ").append(cleanSubject).append("\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: ").append(contentType).append("; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(base64Body)
        }
    }

    private fun sendCommand(
        writer: BufferedWriter,
        command: String,
    ) {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
    }

    private fun readExpectedResponse(
        reader: BufferedReader,
        expectedCode: Int,
        stage: String,
    ): SmtpResponse {
        val response = readSmtpResponse(reader)
        if (response.code != expectedCode) {
            throw SmtpException("SMTP $stage failed (${response.code}): ${response.summary}")
        }
        return response
    }

    private fun readSmtpResponse(reader: BufferedReader): SmtpResponse {
        val lines = mutableListOf<String>()
        var responseCode = -1
        var hasMore = true
        while (hasMore) {
            val line = reader.readLine() ?: throw SmtpException("Unexpected end of SMTP stream")
            responseCode = parseResponseStatusCode(line)
            val text = if (line.length > 4) line.substring(4) else ""
            lines.add(text)
            val delimiter = if (line.length == 3) ' ' else line[3]
            if (delimiter == ' ') {
                hasMore = false
            } else if (delimiter != '-') {
                throw SmtpException("Malformed SMTP multiline delimiter: $line")
            }
        }
        return SmtpResponse(responseCode, lines)
    }

    private fun parseResponseStatusCode(line: String): Int {
        val codePrefix = line.take(3)
        if (codePrefix.length < 3 || codePrefix.any { !it.isDigit() }) {
            throw SmtpException("Malformed SMTP server response: $line")
        }
        return codePrefix.toInt()
    }

    private data class SmtpSessionStreams(
        val reader: BufferedReader,
        val writer: BufferedWriter,
    )

    private data class SmtpResponse(
        val code: Int,
        val lines: List<String>,
    ) {
        val summary: String
            get() = lines.lastOrNull().orEmpty().ifBlank { "HTTP/SMTP status $code" }
    }

    companion object {
        private const val MIME_LINE_LENGTH = 76
        private val EMAIL_SIMPLE_REGEX = Regex("^[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+$")
        private val DOMAIN_SAFE_REGEX = Regex("^[A-Za-z0-9.-]+$")

        fun parseAddressList(raw: String): List<String> =
            raw
                .split(',', ';')
                .map { sanitizeHeaderValue(it) }
                .filter { it.isNotBlank() }

        fun sanitizeHeaderValue(value: String): String =
            value
                .replace("\r", "")
                .replace("\n", "")
                .trim()

        fun extractEnvelopeAddress(rawAddress: String): String {
            val clean = sanitizeHeaderValue(rawAddress)
            val ltIdx = clean.lastIndexOf('<')
            val gtIdx = clean.lastIndexOf('>')
            val candidate =
                if (ltIdx >= 0 && gtIdx > ltIdx) {
                    clean.substring(ltIdx + 1, gtIdx).trim()
                } else {
                    clean
                }
            if (!EMAIL_SIMPLE_REGEX.matches(candidate)) {
                throw SmtpException("Invalid email address: $candidate")
            }
            return candidate
        }

        private fun deriveEhloDomain(fromEnvelope: String): String {
            val domain = fromEnvelope.substringAfter('@', "").trim()
            return if (domain.isNotBlank() && DOMAIN_SAFE_REGEX.matches(domain)) {
                domain
            } else {
                "zophop.local"
            }
        }

        private fun encodeHeaderIfNonAscii(value: String): String {
            val isAscii = value.all { ch -> ch.code in 0x20..0x7E }
            if (isAscii) return value
            val b64 = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
            return "=?UTF-8?B?$b64?="
        }

        private fun encodeMimeBase64Body(body: String): String {
            val encoded = Base64.getEncoder().encodeToString(body.toByteArray(StandardCharsets.UTF_8))
            if (encoded.isEmpty()) return ""
            return encoded.chunked(MIME_LINE_LENGTH).joinToString("\r\n")
        }
    }
}
