package dev.zophop.plugin.smtp

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.SocketFactory
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class SmtpNodePluginServiceTest {
    @Test
    fun testSmtpPluginManifestStructure() {
        val service = Robolectric.buildService(SmtpNodePluginService::class.java).create().get()
        val binder = service.onBind(null) as INodePluginService
        assertEquals(1, binder.pluginApiVersion)

        val manifests = binder.manifests
        assertEquals(1, manifests.size)
        val smtpAction = manifests.first()

        assertEquals(SmtpPluginModule.TYPE_ID_SMTP_ACTION, smtpAction.typeId)
        assertEquals(NodeCategory.ACTION, smtpAction.category)
        assertEquals("ic_node_smtp", smtpAction.iconResName)
        assertEquals(SmtpPluginModule.CREDENTIAL_TYPE_SMTP_AUTH, smtpAction.requiredCredentialType)
        assertEquals(11, smtpAction.configSchema.size)
        assertNotNull(smtpAction.credentialSpec)
        assertEquals(5, smtpAction.credentialSpec?.fields?.size)
    }

    @Test
    fun testSmtpExecutorCreationAndValidation() {
        val executor = SmtpPluginModule.createExecutor(SmtpPluginModule.TYPE_ID_SMTP_ACTION, OkHttpClient(), null)
        assertNotNull(executor)
        assertTrue(executor is SmtpActionExecutor)

        assertNull(SmtpPluginModule.createExecutor("dev.zophop.plugin.other.OTHER", OkHttpClient(), null))

        assertNotNull(SmtpPluginModule.validateConfig(SmtpPluginModule.TYPE_ID_SMTP_ACTION, """{"toAddresses":"a@b.com"}"""))
        assertNotNull(SmtpPluginModule.validateConfig(SmtpPluginModule.TYPE_ID_SMTP_ACTION, """{"host":"smtp.example.com","port":"99999","toAddresses":"a@b.com"}"""))
        assertNull(SmtpPluginModule.validateConfig(SmtpPluginModule.TYPE_ID_SMTP_ACTION, """{"host":"smtp.example.com","port":"587","toAddresses":"a@b.com"}"""))
        assertNull(SmtpPluginModule.validateConfig(SmtpPluginModule.TYPE_ID_SMTP_ACTION, """{"credentialId":"cred_1","toAddresses":"a@b.com"}"""))
    }

    @Test
    fun testSmtpExecutorInterpolatesAndDispatchesRequest() =
        runTest {
            var captured: SmtpMessageRequest? = null
            val fakeTransport =
                SmtpTransport { req ->
                    captured = req
                }
            val executor = SmtpActionExecutor(smtpTransport = fakeTransport)
            val node =
                WorkflowNode(
                    id = "smtp_1",
                    name = "Email",
                    type = SmtpPluginModule.TYPE_ID_SMTP_ACTION,
                    configJson =
                        """
                        {
                            "host": "smtp.example.com",
                            "port": "465",
                            "security": "SMTPS",
                            "username": "alerts@example.com",
                            "password": "app-password-123",
                            "fromAddress": "ZopHop <noreply@example.com>",
                            "toAddresses": "alice@example.com, bob@example.com",
                            "ccAddresses": "audit@example.com",
                            "subject": "OTP from ${'$'}{sender}",
                            "contentType": "text/html",
                            "bodyTemplate": "<p>${'$'}{body}</p>"
                        }
                        """.trimIndent(),
                )
            val context = ExecutionContext(mapOf("sender" to "BANK", "body" to "Code 918273"))

            val result = executor.execute(node, context, null)

            assertTrue(result is NodeExecutionResult.Success)
            val req = captured!!
            assertEquals("smtp.example.com", req.host)
            assertEquals(465, req.port)
            assertEquals(SmtpSecurityMode.SMTPS, req.security)
            assertEquals("alerts@example.com", req.username)
            assertEquals("app-password-123", req.password)
            assertEquals("ZopHop <noreply@example.com>", req.fromAddress)
            assertEquals(listOf("alice@example.com", "bob@example.com"), req.toAddresses)
            assertEquals(listOf("audit@example.com"), req.ccAddresses)
            assertEquals("OTP from BANK", req.subject)
            assertEquals("<p>Code 918273</p>", req.body)
            assertEquals("text/html", req.contentType)
        }

    @Test
    fun testSmtpExecutorResolvesCredentialFromIpcContext() =
        runTest {
            var captured: SmtpMessageRequest? = null
            val executor = SmtpActionExecutor(smtpTransport = SmtpTransport { captured = it })
            val node =
                WorkflowNode(
                    id = "smtp_ipc",
                    name = "Email",
                    type = SmtpPluginModule.TYPE_ID_SMTP_ACTION,
                    configJson =
                        """
                        {
                            "credentialId": "smtp_cred",
                            "port": "587",
                            "security": "SMTPS",
                            "toAddresses": "target@example.com",
                            "bodyTemplate": "${'$'}{body}"
                        }
                        """.trimIndent(),
                )
            val context =
                ExecutionContext(
                    mapOf(
                        "_cred_host" to "mail.private.net",
                        "_cred_port" to "465",
                        "_cred_username" to "bot@private.net",
                        "_cred_password" to "super_secret_smtp_pw",
                        "body" to "Notification payload",
                    ),
                )

            val result = executor.execute(node, context, null)

            assertTrue(result is NodeExecutionResult.Success)
            val req = captured!!
            assertEquals("mail.private.net", req.host)
            assertEquals(465, req.port)
            assertEquals("bot@private.net", req.username)
            assertEquals("super_secret_smtp_pw", req.password)
            assertEquals("bot@private.net", req.fromAddress)
        }

    @Test
    fun testSmtpExecutorResolvesCredentialViaRepositoryFallback() =
        runTest {
            var captured: SmtpMessageRequest? = null
            val mockRepo =
                object {
                    @Suppress("unused")
                    fun getCredentialById(id: String): Any? =
                        if (id == "cred_repo_smtp") {
                            object {
                                @Suppress("unused")
                                fun getDataMap(): Map<String, String> =
                                    mapOf(
                                        "host" to "smtp.repo.org",
                                        "port" to "2525",
                                        "username" to "repo@repo.org",
                                        "password" to "repo-pw-xyz",
                                        "fromAddress" to "sender@repo.org",
                                    )
                            }
                        } else {
                            null
                        }
                }
            val executor = SmtpActionExecutor(smtpTransport = { captured = it }, credentialRepository = mockRepo)
            val node =
                WorkflowNode(
                    id = "smtp_repo",
                    name = "Email",
                    type = SmtpPluginModule.TYPE_ID_SMTP_ACTION,
                    configJson = """{"credentialId":"cred_repo_smtp","toAddresses":"dest@repo.org"}""",
                )
            val result = executor.execute(node, ExecutionContext(mapOf("body" to "Hello")), null)

            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("smtp.repo.org", captured?.host)
            assertEquals(2525, captured?.port)
            assertEquals("sender@repo.org", captured?.fromAddress)
            assertEquals("repo-pw-xyz", captured?.password)
        }

    @Test
    fun testSmtpExecutorRedactsSecretsOnFailure() =
        runTest {
            val secretPass = "MyUltraSecretSmtpPass99!"
            val failingTransport =
                SmtpTransport {
                    throw SmtpException("Server rejected AUTH PLAIN with secret $secretPass")
                }
            val executor = SmtpActionExecutor(smtpTransport = failingTransport)
            val node =
                WorkflowNode(
                    id = "smtp_err",
                    name = "Email",
                    type = SmtpPluginModule.TYPE_ID_SMTP_ACTION,
                    configJson =
                        """
                        {
                            "host": "smtp.example.com",
                            "username": "user@example.com",
                            "password": "$secretPass",
                            "toAddresses": "dest@example.com"
                        }
                        """.trimIndent(),
                )

            val result = executor.execute(node, ExecutionContext(mapOf("body" to "test")), null)

            assertTrue(result is NodeExecutionResult.Failed)
            val reason = (result as NodeExecutionResult.Failed).reason
            assertFalse("Error message must not contain raw SMTP password", reason.contains(secretPass))
            assertTrue(reason.contains("***"))
        }

    @Test
    fun testSmtpClientAuthPlainAndMimePayloadOverMockSocket() =
        runTest {
            val receivedCommands = mutableListOf<String>()
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val serverJob =
                launch(Dispatchers.IO) {
                    server.use { srv ->
                        srv.accept().use { clientSock ->
                            val reader = BufferedReader(InputStreamReader(clientSock.getInputStream(), StandardCharsets.UTF_8))
                            val writer = BufferedWriter(OutputStreamWriter(clientSock.getOutputStream(), StandardCharsets.UTF_8))
                            writer.write("220 mock.smtp.local ESMTP\r\n")
                            writer.flush()

                            var inData = false
                            var running = true
                            val dataLines = mutableListOf<String>()
                            while (running) {
                                val line = reader.readLine()
                                if (line == null) {
                                    running = false
                                } else if (inData) {
                                    if (line == ".") {
                                        inData = false
                                        receivedCommands.add("DATA_BODY:${dataLines.joinToString("\n")}")
                                        writer.write("250 2.0.0 Ok: queued\r\n")
                                        writer.flush()
                                    } else {
                                        dataLines.add(line)
                                    }
                                } else {
                                    receivedCommands.add(line)
                                    when {
                                        line.startsWith("EHLO ") -> {
                                            writer.write("250-mock.smtp.local\r\n250 AUTH PLAIN LOGIN\r\n")
                                            writer.flush()
                                        }
                                        line.startsWith("AUTH PLAIN ") -> {
                                            writer.write("235 2.7.0 Authentication successful\r\n")
                                            writer.flush()
                                        }
                                        line.startsWith("MAIL FROM:") -> {
                                            writer.write("250 2.1.0 Ok\r\n")
                                            writer.flush()
                                        }
                                        line.startsWith("RCPT TO:") -> {
                                            writer.write("250 2.1.5 Ok\r\n")
                                            writer.flush()
                                        }
                                        line == "DATA" -> {
                                            inData = true
                                            writer.write("354 End data with <CR><LF>.<CR><LF>\r\n")
                                            writer.flush()
                                        }
                                        line == "QUIT" -> {
                                            writer.write("221 2.0.0 Bye\r\n")
                                            writer.flush()
                                            running = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            val client = SmtpClient()
            client.sendEmail(
                SmtpMessageRequest(
                    host = "127.0.0.1",
                    port = server.localPort,
                    security = SmtpSecurityMode.PLAIN,
                    username = "sender@example.com",
                    password = "pw-value",
                    fromAddress = "ZopHop <sender@example.com>",
                    toAddresses = listOf("a@example.com"),
                    ccAddresses = listOf("b@example.com"),
                    subject = "Alert 🚀\r\nBcc: injected@evil.com",
                    body = "OTP is 123456\n.leading dot test",
                    contentType = SmtpMessageRequest.CONTENT_TYPE_PLAIN,
                ),
            )
            serverJob.join()

            assertTrue(receivedCommands.any { it == "EHLO example.com" })
            val expectedAuth = Base64.getEncoder().encodeToString("\u0000sender@example.com\u0000pw-value".toByteArray())
            assertTrue(receivedCommands.contains("AUTH PLAIN $expectedAuth"))
            assertTrue(receivedCommands.contains("MAIL FROM:<sender@example.com>"))
            assertTrue(receivedCommands.contains("RCPT TO:<a@example.com>"))
            assertTrue(receivedCommands.contains("RCPT TO:<b@example.com>"))
            val dataBlock = receivedCommands.first { it.startsWith("DATA_BODY:") }
            assertTrue(dataBlock.contains("Subject: =?UTF-8?B?"))
            assertFalse("CRLF injection must never introduce a separate Bcc header line", dataBlock.contains("\nBcc:"))
            val bodyBase64 = dataBlock.substringAfter("\n\n").replace("\n", "")
            val decodedBody = String(Base64.getDecoder().decode(bodyBase64), StandardCharsets.UTF_8)
            assertEquals("OTP is 123456\n.leading dot test", decodedBody)
        }

    @Test
    fun testSmtpClientStartTlsAndAuthLogin() =
        runTest {
            val commandsAfterTls = mutableListOf<String>()
            var handshakeCompleted = false
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val serverJob =
                launch(Dispatchers.IO) {
                    server.use { srv ->
                        srv.accept().use { clientSock ->
                            val reader = BufferedReader(InputStreamReader(clientSock.getInputStream(), StandardCharsets.UTF_8))
                            val writer = BufferedWriter(OutputStreamWriter(clientSock.getOutputStream(), StandardCharsets.UTF_8))
                            writer.write("220 starttls.local ESMTP\r\n")
                            writer.flush()

                            var tlsUpgraded = false
                            var authStep = 0
                            var inData = false
                            var running = true
                            while (running) {
                                val line = reader.readLine()
                                if (line == null) {
                                    running = false
                                } else if (inData) {
                                    if (line == ".") {
                                        inData = false
                                        writer.write("250 Ok\r\n")
                                        writer.flush()
                                    }
                                } else {
                                    if (tlsUpgraded) commandsAfterTls.add(line)
                                    when {
                                        line.startsWith("EHLO ") && !tlsUpgraded -> {
                                            writer.write("250-starttls.local\r\n250 STARTTLS\r\n")
                                            writer.flush()
                                        }
                                        line == "STARTTLS" -> {
                                            tlsUpgraded = true
                                            writer.write("220 Ready to start TLS\r\n")
                                            writer.flush()
                                        }
                                        line.startsWith("EHLO ") && tlsUpgraded -> {
                                            writer.write("250-starttls.local\r\n250 AUTH LOGIN\r\n")
                                            writer.flush()
                                        }
                                        line == "AUTH LOGIN" -> {
                                            authStep = 1
                                            writer.write("334 VXNlcm5hbWU6\r\n")
                                            writer.flush()
                                        }
                                        authStep == 1 -> {
                                            authStep = 2
                                            writer.write("334 UGFzc3dvcmQ6\r\n")
                                            writer.flush()
                                        }
                                        authStep == 2 -> {
                                            authStep = 0
                                            writer.write("235 Authentication succeeded\r\n")
                                            writer.flush()
                                        }
                                        line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:") -> {
                                            writer.write("250 Ok\r\n")
                                            writer.flush()
                                        }
                                        line == "DATA" -> {
                                            inData = true
                                            writer.write("354 Start mail input\r\n")
                                            writer.flush()
                                        }
                                        line == "QUIT" -> {
                                            writer.write("221 Bye\r\n")
                                            writer.flush()
                                            running = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            val passthroughSslFactory =
                object : SSLSocketFactory() {
                    override fun getDefaultCipherSuites(): Array<String> = emptyArray()

                    override fun getSupportedCipherSuites(): Array<String> = emptyArray()

                    override fun createSocket(
                        s: Socket,
                        host: String?,
                        port: Int,
                        autoClose: Boolean,
                    ): Socket = PassThroughSslSocket(s) { handshakeCompleted = true }

                    override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()

                    override fun createSocket(
                        host: String?,
                        port: Int,
                        localHost: InetAddress?,
                        localPort: Int,
                    ): Socket = throw UnsupportedOperationException()

                    override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()

                    override fun createSocket(
                        address: InetAddress?,
                        port: Int,
                        localAddress: InetAddress?,
                        localPort: Int,
                    ): Socket = throw UnsupportedOperationException()
                }

            val client = SmtpClient(SocketFactory.getDefault(), passthroughSslFactory)
            client.sendEmail(
                SmtpMessageRequest(
                    host = "127.0.0.1",
                    port = server.localPort,
                    security = SmtpSecurityMode.STARTTLS,
                    username = "loginuser@example.com",
                    password = "loginpass",
                    fromAddress = "loginuser@example.com",
                    toAddresses = listOf("dest@example.com"),
                    subject = "STARTTLS Test",
                    body = "Encrypted channel test",
                ),
            )
            serverJob.join()

            assertTrue(handshakeCompleted)
            assertTrue(commandsAfterTls.contains("EHLO example.com"))
            assertTrue(commandsAfterTls.contains("AUTH LOGIN"))
            val expectedUserB64 = Base64.getEncoder().encodeToString("loginuser@example.com".toByteArray())
            val expectedPassB64 = Base64.getEncoder().encodeToString("loginpass".toByteArray())
            assertTrue(commandsAfterTls.contains(expectedUserB64))
            assertTrue(commandsAfterTls.contains(expectedPassB64))
        }

    @Test
    fun testSmtpServiceFailsCleanlyOnMissingFields() {
        val service = Robolectric.buildService(SmtpNodePluginService::class.java).create().get()
        val binder = service.onBind(null) as INodePluginService

        val resultJson = binder.executeNode("s1", """{"host":"","toAddresses":""}""", "{}")
        val json = parseJsonObjectSafe(resultJson)

        assertFalse(json.optBoolean("isSuccess"))
        assertTrue(json.optString("errorReason").contains("empty"))
    }
}

private class PassThroughSslSocket(
    private val delegate: Socket,
    private val onHandshake: () -> Unit,
) : SSLSocket() {
    override fun getInputStream() = delegate.getInputStream()

    override fun getOutputStream() = delegate.getOutputStream()

    override fun close() = delegate.close()

    override fun getSupportedCipherSuites(): Array<String> = emptyArray()

    override fun getEnabledCipherSuites(): Array<String> = emptyArray()

    override fun setEnabledCipherSuites(suites: Array<out String>?) {}

    override fun getSupportedProtocols(): Array<String> = emptyArray()

    override fun getEnabledProtocols(): Array<String> = emptyArray()

    override fun setEnabledProtocols(protocols: Array<out String>?) {}

    override fun getSession(): SSLSession? = null

    override fun addHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {}

    override fun removeHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {}

    override fun startHandshake() {
        onHandshake()
    }

    override fun setUseClientMode(mode: Boolean) {}

    override fun getUseClientMode(): Boolean = true

    override fun setNeedClientAuth(need: Boolean) {}

    override fun getNeedClientAuth(): Boolean = false

    override fun setWantClientAuth(want: Boolean) {}

    override fun getWantClientAuth(): Boolean = false

    override fun setEnableSessionCreation(flag: Boolean) {}

    override fun getEnableSessionCreation(): Boolean = true
}
