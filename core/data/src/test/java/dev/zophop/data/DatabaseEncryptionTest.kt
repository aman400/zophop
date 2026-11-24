package dev.zophop.data

import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseEncryptionTest {
    @Test
    fun testEncryptAndDecryptRoundTrip() {
        val originalToken = "sk_live_1234567890abcdef"
        val encrypted = DatabaseEncryption.encrypt(originalToken)

        assertTrue("Encrypted token must start with prefix", DatabaseEncryption.isEncrypted(encrypted))
        assertNotEquals("Encrypted token must not equal plaintext", originalToken, encrypted)

        val decrypted = DatabaseEncryption.decrypt(encrypted)
        assertEquals(originalToken, decrypted)
    }

    @Test
    fun testDecryptLegacyUnencryptedValueReturnsAsIs() {
        val legacyPlaintext = "plain_token_12345"
        val result = DatabaseEncryption.decrypt(legacyPlaintext)
        assertEquals(legacyPlaintext, result)
    }

    @Test
    fun testEmptyOrBlankEncryptionReturnsAsIs() {
        assertEquals("", DatabaseEncryption.encrypt(""))
        assertEquals("   ", DatabaseEncryption.encrypt("   "))
    }

    @Test
    fun testCredentialEntitySavesEncryptedTokenAndRestoresPlaintext() {
        val credential =
            Credential(
                id = "cred-1",
                name = "Ntfy Prod",
                type = CredentialType.NTFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://ntfy.sh",
                        "topic" to "prod_alerts",
                        "token" to "tk_super_secret_999",
                    ),
            )

        val entity = CredentialEntity.fromDomain(credential)

        // dataJson in database must NOT contain raw token plaintext
        assertFalse("dataJson must not contain plaintext token", entity.dataJson.contains("tk_super_secret_999"))
        assertTrue("dataJson must contain encrypted token prefix", entity.dataJson.contains("enc:v1:"))
        assertTrue("dataJson must still contain non-sensitive topic", entity.dataJson.contains("prod_alerts"))

        // When converted back to domain model, decrypted plaintext is seamlessly restored
        val restored = entity.toDomain()
        assertEquals("tk_super_secret_999", restored.dataMap["token"])
        assertEquals("https://ntfy.sh", restored.dataMap["serverUrl"])
        assertEquals("prod_alerts", restored.dataMap["topic"])
    }

    @Test
    fun testWorkflowEntitySavesEncryptedNodeConfigAndRestoresPlaintext() {
        val node =
            WorkflowNode(
                id = "node-ntfy",
                name = "ntfy Node",
                type = "NTFY_ACTION",
                configJson = """{"serverUrl":"https://ntfy.sh","topic":"alerts","token":"override_token_secret"}""",
            )
        val workflow =
            Workflow(
                id = "wf-1",
                name = "Test Workflow",
                nodes = listOf(node),
                connections = emptyList(),
            )

        val entity = WorkflowEntity.fromDomain(workflow)

        // graphJson in database must NOT contain raw token plaintext
        assertFalse("graphJson must not contain plaintext token", entity.graphJson.contains("override_token_secret"))
        assertTrue("graphJson must contain encrypted token prefix", entity.graphJson.contains("enc:v1:"))

        // When restored to domain model, configJson is seamlessly decrypted
        val restored = entity.toDomain()
        val restoredNode = restored.nodes.first()
        assertTrue("Restored node config must contain plaintext token", restoredNode.configJson.contains("override_token_secret"))
        assertFalse("Restored node config must not contain enc:v1:", restoredNode.configJson.contains("enc:v1:"))
    }

    @Test
    fun testHeadersAndSecretsEncryptedAcrossAllCredentialTypes() {
        val testCredentials =
            listOf(
                Credential(
                    id = "c1",
                    name = "Bearer",
                    type = CredentialType.BEARER_TOKEN,
                    dataMap = mapOf("token" to "secret_bearer_key_123"),
                ),
                Credential(
                    id = "c2",
                    name = "Basic",
                    type = CredentialType.BASIC_AUTH,
                    dataMap = mapOf("username" to "admin", "password" to "super_secret_pw_456"),
                ),
                Credential(
                    id = "c3",
                    name = "Gotify",
                    type = CredentialType.GOTIFY_AUTH,
                    dataMap = mapOf("serverUrl" to "https://gotify.net", "appToken" to "gotify_app_token_789"),
                ),
                Credential(
                    id = "c4",
                    name = "Telegram",
                    type = "TELEGRAM_AUTH",
                    dataMap = mapOf("token" to "telegram_bot_token_000"),
                ),
                Credential(
                    id = "c5",
                    name = "Custom Headers",
                    type = CredentialType.HEADER_SET,
                    dataMap = mapOf("headers" to "Authorization: Bearer my_secret_token\nX-Api-Key: custom_key_val"),
                ),
            )

        testCredentials.forEach { cred ->
            val entity = CredentialEntity.fromDomain(cred)
            assertTrue("Must contain enc:v1: for sensitive data in ${cred.type}", entity.dataJson.contains("enc:v1:"))
            cred.dataMap.filter { DatabaseEncryption.isSensitiveKey(it.key) }.values.forEach { secretVal ->
                assertFalse("Database dataJson must NOT contain plaintext secret $secretVal", entity.dataJson.contains(secretVal))
            }

            val restored = entity.toDomain()
            assertEquals("Restored dataMap for ${cred.type} must match original", cred.dataMap, restored.dataMap)
        }
    }

    @Test
    fun testHttpNodeHeadersAndAuthEncryptedInWorkflowEntity() {
        val httpNode =
            WorkflowNode(
                id = "node-http",
                name = "HTTP Webhook",
                type = "HTTP_ACTION",
                configJson = """{"url":"https://api.example.com","headers":"Authorization: Bearer token_999\nX-Secret: secret_val","bearerToken":"inline_bearer_xyz"}""",
            )
        val workflow =
            Workflow(
                id = "wf-http",
                name = "HTTP Workflow",
                nodes = listOf(httpNode),
                connections = emptyList(),
            )

        val entity = WorkflowEntity.fromDomain(workflow)
        assertFalse("graphJson must not contain plaintext header secret", entity.graphJson.contains("token_999"))
        assertFalse("graphJson must not contain plaintext inline bearer token", entity.graphJson.contains("inline_bearer_xyz"))
        assertTrue("graphJson must contain enc:v1: for sensitive keys", entity.graphJson.contains("enc:v1:"))

        val restored = entity.toDomain()
        val restoredHttpNode = restored.nodes.first()
        assertTrue("Restored node config must restore headers", restoredHttpNode.configJson.contains("Authorization: Bearer token_999"))
        assertTrue("Restored node config must restore bearerToken", restoredHttpNode.configJson.contains("inline_bearer_xyz"))
    }
}
