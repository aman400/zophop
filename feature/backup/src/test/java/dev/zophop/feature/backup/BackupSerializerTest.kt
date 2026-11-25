package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class BackupSerializerTest {

    @Test
    fun testPasswordBasedEncryption_encryptAndDecryptSuccess() {
        val originalSecret = "my_super_secret_api_token_12345"
        val password = "StrongPassword#2026"

        val encrypted = PasswordBasedEncryption.encrypt(originalSecret, password)
        assertTrue(encrypted.startsWith(PasswordBasedEncryption.PREFIX))
        assertNotEquals(originalSecret, encrypted)

        val decrypted = PasswordBasedEncryption.decrypt(encrypted, password)
        assertEquals(originalSecret, decrypted)
    }

    @Test
    fun testPasswordBasedEncryption_wrongPasswordThrowsSecurityException() {
        val originalSecret = "telegram_bot_token_secret"
        val password = "CorrectPin123"
        val wrongPassword = "WrongPin999"

        val encrypted = PasswordBasedEncryption.encrypt(originalSecret, password)
        try {
            PasswordBasedEncryption.decrypt(encrypted, wrongPassword)
            fail("Expected SecurityException on wrong password")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    @Test
    fun testBackupExportAndImport_unencryptedPlaintext() {
        val workflow = createSampleWorkflow("Sync Workflow", "node_1", "node_2")
        val credential = Credential(
            id = "cred_ntfy_1",
            name = "Ntfy Alerts",
            type = CredentialType.NTFY_AUTH,
            dataMap = mapOf("topic" to "test_topic", "token" to "ntfy_token_abc"),
        )
        val precondition = PreconditionEntity(
            id = "prec_wifi_1",
            name = "WiFi Required",
            type = "INTERNET_CHECK",
        )

        val json = BackupSerializer.createBackupJson(
            workflows = listOf(workflow),
            credentials = listOf(credential),
            preconditions = listOf(precondition),
            password = null,
        )

        assertFalse(json.contains(PasswordBasedEncryption.PREFIX))
        assertTrue(json.contains("Sync Workflow"))
        assertTrue(json.contains("ntfy_token_abc"))

        val parsed = BackupSerializer.parseImport(json)
        assertFalse(parsed.isEncrypted)
        assertEquals(1, parsed.workflows.size)
        assertEquals(1, parsed.credentials.size)
        assertEquals(1, parsed.preconditions.size)

        assertEquals("Sync Workflow", parsed.workflows[0].name)
        assertEquals("ntfy_token_abc", parsed.credentials[0].dataMap["token"])
        assertEquals("WiFi Required", parsed.preconditions[0].name)
    }

    @Test
    fun testBackupExportAndImport_encryptedSecretsWithPin() {
        val pin = "1234"
        val workflow = createSampleWorkflow("Encrypted Workflow", "n1", "n2", token = "wf_secret_token")
        val credential = Credential(
            id = "cred_bearer_1",
            name = "Prod API",
            type = CredentialType.BEARER_TOKEN,
            dataMap = mapOf("token" to "super_secret_bearer_999"),
        )
        val precondition = PreconditionEntity(
            id = "prec_vpn_1",
            name = "Work VPN",
            type = "VPN_CHECK",
        )

        val json = BackupSerializer.createBackupJson(
            workflows = listOf(workflow),
            credentials = listOf(credential),
            preconditions = listOf(precondition),
            password = pin,
        )

        assertTrue(json.contains(PasswordBasedEncryption.PREFIX))
        assertFalse(json.contains("super_secret_bearer_999"))
        assertFalse(json.contains("wf_secret_token"))

        val parsed = BackupSerializer.parseImport(json)
        assertTrue(parsed.isEncrypted)
        assertNotNull(parsed.verificationToken)

        // Try decrypting with wrong PIN
        try {
            BackupSerializer.decryptBackupData(parsed, "wrong_pin")
            fail("Expected SecurityException when decrypting with wrong PIN")
        } catch (_: SecurityException) {
            // Expected
        }

        // Decrypt with correct PIN
        val decrypted = BackupSerializer.decryptBackupData(parsed, pin)
        assertFalse(decrypted.isEncrypted)
        assertEquals("super_secret_bearer_999", decrypted.credentials[0].dataMap["token"])
        assertTrue(decrypted.workflows[0].nodes[0].configJson.contains("wf_secret_token"))
    }

    @Test
    fun testParseImport_singleWorkflowObject() {
        val singleWorkflowJson = """
            {
              "id": "wf_standalone",
              "name": "Standalone Notification Forwarder",
              "description": "Forward bank SMS",
              "isEnabled": true,
              "nodes": [
                {
                  "id": "node_sms",
                  "name": "SMS Trigger",
                  "type": "SMS_TRIGGER",
                  "configJson": "{}"
                }
              ],
              "connections": []
            }
        """.trimIndent()

        val parsed = BackupSerializer.parseImport(singleWorkflowJson)
        assertEquals(1, parsed.workflows.size)
        assertEquals("Standalone Notification Forwarder", parsed.workflows[0].name)
        assertEquals("node_sms", parsed.workflows[0].nodes[0].id)
    }

    @Test
    fun testParseImport_singleCredentialObject() {
        val singleCredJson = """
            {
              "id": "cred_gotify",
              "name": "Gotify Server",
              "type": "GOTIFY_AUTH",
              "data": {
                "serverUrl": "https://push.example.com",
                "appToken": "A123456789"
              }
            }
        """.trimIndent()

        val parsed = BackupSerializer.parseImport(singleCredJson)
        assertEquals(1, parsed.credentials.size)
        assertEquals("Gotify Server", parsed.credentials[0].name)
        assertEquals("A123456789", parsed.credentials[0].dataMap["appToken"])
    }

    @Test
    fun testParseImport_arrayOfWorkflows() {
        val arrayJson = """
            [
              {
                "id": "wf_1",
                "name": "Workflow One",
                "nodes": [],
                "connections": []
              },
              {
                "id": "wf_2",
                "name": "Workflow Two",
                "nodes": [],
                "connections": []
              }
            ]
        """.trimIndent()

        val parsed = BackupSerializer.parseImport(arrayJson)
        assertEquals(2, parsed.workflows.size)
        assertEquals("Workflow One", parsed.workflows[0].name)
        assertEquals("Workflow Two", parsed.workflows[1].name)
    }

    @Test
    fun testResolveConflicts_keepBothGeneratesNewIdsAndUpdatesReferences() {
        val existingCredId = "existing_cred_id"
        val existingWfId = "existing_wf_id"
        val existingNodeId = "n1"

        val credential = Credential(id = existingCredId, name = "My API Key", type = CredentialType.BEARER_TOKEN)
        val workflow = Workflow(
            id = existingWfId,
            name = "Existing Workflow Name",
            nodes = listOf(
                WorkflowNode(
                    id = existingNodeId,
                    name = "HTTP Call",
                    type = NodeType.HTTP_ACTION,
                    configJson = """{"credentialId":"$existingCredId","url":"https://api.com"}""",
                ),
                WorkflowNode(
                    id = "n2",
                    name = "Log Action",
                    type = NodeType.TEMPLATE_FORMATTER,
                ),
            ),
            connections = listOf(
                WorkflowConnection(fromNodeId = existingNodeId, toNodeId = "n2"),
            ),
        )

        val parsed = ParsedBackupData(
            workflows = listOf(workflow),
            credentials = listOf(credential),
            preconditions = emptyList(),
        )

        val resolved = BackupSerializer.resolveConflicts(
            parsed = parsed,
            strategy = ImportConflictStrategy.KEEP_BOTH,
            existingWorkflows = listOf(workflow),
            existingCredentials = listOf(credential),
            existingPreconditions = emptyList(),
        )

        // Credential should have new ID and renamed
        val resolvedCred = resolved.credentials[0]
        assertNotEquals(existingCredId, resolvedCred.id)
        assertEquals("My API Key (Imported)", resolvedCred.name)

        // Workflow should have new ID, renamed, new node IDs, and updated connection and credential reference
        val resolvedWf = resolved.workflows[0]
        assertNotEquals(existingWfId, resolvedWf.id)
        assertEquals("Existing Workflow Name (Imported)", resolvedWf.name)

        val resolvedNode1 = resolvedWf.nodes[0]
        assertNotEquals(existingNodeId, resolvedNode1.id)
        assertTrue(resolvedNode1.configJson.contains(resolvedCred.id))
        assertFalse(resolvedNode1.configJson.contains(existingCredId))

        val resolvedConn = resolvedWf.connections[0]
        assertEquals(resolvedNode1.id, resolvedConn.fromNodeId)
        assertEquals(resolvedWf.nodes[1].id, resolvedConn.toNodeId)
    }

    @Test
    fun testResolveConflicts_skipExisting() {
        val existingWfId = "wf_existing"
        val newWfId = "wf_new"

        val parsed = ParsedBackupData(
            workflows = listOf(
                createSampleWorkflow("Existing", "n1", "n2", id = existingWfId),
                createSampleWorkflow("New One", "n3", "n4", id = newWfId),
            ),
        )

        val resolved = BackupSerializer.resolveConflicts(
            parsed = parsed,
            strategy = ImportConflictStrategy.SKIP_EXISTING,
            existingWorkflows = listOf(createSampleWorkflow("Existing", "n1", "n2", id = existingWfId)),
            existingCredentials = emptyList(),
            existingPreconditions = emptyList(),
        )

        assertEquals(1, resolved.workflows.size)
        assertEquals(newWfId, resolved.workflows[0].id)
    }

    private fun createSampleWorkflow(
        name: String,
        fromNodeId: String,
        toNodeId: String,
        id: String = UUID.randomUUID().toString(),
        token: String? = null,
    ): Workflow {
        val configJson = if (token != null) """{"url":"https://api.test","token":"$token"}""" else "{}"
        return Workflow(
            id = id,
            name = name,
            description = "Test workflow description",
            isEnabled = true,
            nodes = listOf(
                WorkflowNode(
                    id = fromNodeId,
                    name = "Trigger Node",
                    type = NodeType.MANUAL_TRIGGER,
                    configJson = configJson,
                    preconditions = listOf(Precondition.InternetCheck(id = "p1")),
                ),
                WorkflowNode(
                    id = toNodeId,
                    name = "Action Node",
                    type = NodeType.SEND_NOTIFICATION_ACTION,
                    configJson = "{}",
                ),
            ),
            connections = listOf(
                WorkflowConnection(fromNodeId = fromNodeId, toNodeId = toNodeId),
            ),
        )
    }

    @Test
    fun testResolveConflicts_keepBothRemapsPreconditionEntityCredentialId() {
        val existingCredId = "existing_cred"
        val existingPrecId = "existing_prec"

        val credential = Credential(id = existingCredId, name = "Existing Cred", type = CredentialType.BEARER_TOKEN)
        val precondition = PreconditionEntity(
            id = existingPrecId,
            name = "Existing Rule",
            type = "INTERNET_CHECK",
            credentialId = existingCredId,
        )

        val parsed = ParsedBackupData(
            workflows = emptyList(),
            credentials = listOf(credential),
            preconditions = listOf(precondition),
        )

        val resolved = BackupSerializer.resolveConflicts(
            parsed = parsed,
            strategy = ImportConflictStrategy.KEEP_BOTH,
            existingWorkflows = emptyList(),
            existingCredentials = listOf(credential),
            existingPreconditions = listOf(precondition),
        )

        val resolvedCred = resolved.credentials[0]
        val resolvedPrec = resolved.preconditions[0]

        assertNotEquals(existingCredId, resolvedCred.id)
        assertNotEquals(existingPrecId, resolvedPrec.id)
        assertEquals("Existing Cred (Imported)", resolvedCred.name)
        assertEquals("Existing Rule (Imported)", resolvedPrec.name)
        // Check that the precondition's credentialId was remapped to the new credential ID!
        assertEquals(resolvedCred.id, resolvedPrec.credentialId)
    }

    @Test
    fun testResolveConflicts_overwriteRetainsOriginalIds() {
        val cred = Credential(id = "cred_1", name = "Cred 1 Updated", type = CredentialType.BEARER_TOKEN)
        val prec = PreconditionEntity(id = "prec_1", name = "Prec 1 Updated", type = "INTERNET_CHECK")

        val parsed = ParsedBackupData(
            workflows = emptyList(),
            credentials = listOf(cred),
            preconditions = listOf(prec),
        )

        val resolved = BackupSerializer.resolveConflicts(
            parsed = parsed,
            strategy = ImportConflictStrategy.OVERWRITE,
            existingWorkflows = emptyList(),
            existingCredentials = listOf(Credential(id = "cred_1", name = "Old Name", type = CredentialType.BEARER_TOKEN)),
            existingPreconditions = listOf(PreconditionEntity(id = "prec_1", name = "Old Prec", type = "INTERNET_CHECK")),
        )

        assertEquals("cred_1", resolved.credentials[0].id)
        assertEquals("Cred 1 Updated", resolved.credentials[0].name)
        assertEquals("prec_1", resolved.preconditions[0].id)
        assertEquals("Prec 1 Updated", resolved.preconditions[0].name)
    }

    @Test
    fun testParseImport_malformedJson_throwsIllegalArgumentException() {
        try {
            BackupSerializer.parseImport("{ this is definitely not valid json")
            fail("Expected IllegalArgumentException on malformed JSON")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testPasswordBasedEncryption_tamperedCiphertextThrowsSecurityException() {
        val encrypted = PasswordBasedEncryption.encrypt("secret_value", "mypassword")
        // Alter some characters in the ciphertext
        val tampered = encrypted.dropLast(4) + "AAAA"
        try {
            PasswordBasedEncryption.decrypt(tampered, "mypassword")
            fail("Expected SecurityException when decrypting tampered payload")
        } catch (_: SecurityException) {
            // Expected
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testCreateBackupJson_decryptLocalStorageValueApplied() {
        val cred = Credential(
            id = "c1",
            name = "Local Enc Cred",
            type = CredentialType.BEARER_TOKEN,
            dataMap = mapOf("token" to "enc:raw_token_xyz"),
        )
        val json = BackupSerializer.createBackupJson(
            workflows = emptyList(),
            credentials = listOf(cred),
            preconditions = emptyList(),
            password = null,
            decryptLocalStorageValue = { it.removePrefix("enc:") },
        )

        assertTrue(json.contains("raw_token_xyz"))
        assertFalse(json.contains("enc:raw_token_xyz"))
    }
}
