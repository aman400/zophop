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

    @Test
    fun testResolveConflicts_keepBoth_assignsEightCharUppercaseAlphanumericWorkflowId() {
        val existingWf = createSampleWorkflow("Flow", "n1", "n2", id = "existing_wf")
        val resolved = BackupSerializer.resolveConflicts(
            parsed = ParsedBackupData(workflows = listOf(existingWf)),
            strategy = ImportConflictStrategy.KEEP_BOTH,
            existingWorkflows = listOf(existingWf),
            existingCredentials = emptyList(),
            existingPreconditions = emptyList(),
        )

        val newId = resolved.workflows.single().id
        assertTrue("Expected 8-char uppercase alphanumeric workflow ID, got '$newId'", Regex("^[A-Z0-9]{8}$").matches(newId))
    }

    @Test
    fun testCreateBackupJson_decryptsDatabaseEncryptedNodeConfigFields() {
        val encryptedConfig = dev.zophop.data.DatabaseEncryption.encryptNodeConfig(
            """{"url":"https://example.com","bearerToken":"my_secret_bearer_token"}""",
        )
        assertTrue(encryptedConfig.contains("enc:v1:"))

        val wf = Workflow(
            id = "WFENC123",
            name = "Encrypted Node Flow",
            nodes = listOf(
                WorkflowNode(
                    id = "n1",
                    name = "HTTP",
                    type = NodeType.HTTP_ACTION,
                    configJson = encryptedConfig,
                ),
            ),
            connections = emptyList(),
        )
        val exportedJson = BackupSerializer.createBackupJson(workflows = listOf(wf), password = null)

        assertFalse("Exported backup must decrypt enc:v1: node secrets", exportedJson.contains("enc:v1:"))
        assertTrue("Exported backup must contain decrypted secret when unencrypted", exportedJson.contains("my_secret_bearer_token"))

        // Also test parseImport on a workflow JSON whose node still holds enc:v1:
        val singleWfJson = """{"id":"WF123456","name":"Direct","nodes":[{"id":"n1","name":"HTTP","type":"HTTP_ACTION","configJson":${dev.zophop.domain.model.AppJson.encodeToString(encryptedConfig)}}],"connections":[]}"""
        val parsed = BackupSerializer.parseImport(singleWfJson)
        val parsedConfig = parsed.workflows.single().nodes.single().configJson
        assertFalse(parsedConfig.contains("enc:v1:"))
        assertTrue(parsedConfig.contains("my_secret_bearer_token"))
    }

    @Test
    fun testResolveConflicts_keepBoth_jsonSafeCredentialRemappingDoesNotCorruptSubstrings() {
        val shortCredId = "1"
        val cred = Credential(id = shortCredId, name = "Short ID Cred", type = CredentialType.BEARER_TOKEN)
        val wf = Workflow(
            id = "WFSUB123",
            name = "Substring Test",
            nodes = listOf(
                WorkflowNode(
                    id = "n1",
                    name = "HTTP",
                    type = NodeType.HTTP_ACTION,
                    configJson = """{"credentialId":"1","url":"https://api.example.com/v1/items/123","timeout":1000}""",
                ),
            ),
            connections = emptyList(),
        )

        val resolved = BackupSerializer.resolveConflicts(
            parsed = ParsedBackupData(workflows = listOf(wf), credentials = listOf(cred)),
            strategy = ImportConflictStrategy.KEEP_BOTH,
            existingWorkflows = emptyList(),
            existingCredentials = listOf(cred),
            existingPreconditions = emptyList(),
        )

        val newCredId = resolved.credentials.single().id
        val remappedConfig = resolved.workflows.single().nodes.first().configJson
        assertTrue(remappedConfig.contains("\"credentialId\":\"$newCredId\""))
        assertTrue("URL containing '1' must remain intact", remappedConfig.contains("https://api.example.com/v1/items/123"))
        assertTrue("Numeric timeout 1000 must remain intact", remappedConfig.contains("1000"))
    }

    @Test
    fun testParseImport_preservesChainedCheckNodesAndConnections() {
        val legacyBundleJson = """
            {
              "type": "ZOPHOP_BACKUP",
              "version": 1,
              "workflows": [
                {
                  "id": "WFCHAIN1",
                  "name": "Chain Flow",
                  "nodes": [
                    {
                      "id": "n1",
                      "name": "Trigger",
                      "type": "MANUAL_TRIGGER",
                      "configJson": "{}",
                      "preconditions": [
                        {
                          "id": "chain_1",
                          "type": "CHAINED_CHECK",
                          "name": "Auth Chain",
                          "nodes": [
                            {"id": "cn1", "name": "Step 1", "type": "HTTP_ACTION", "configJson": "{}"}
                          ],
                          "connections": [
                            {"fromNodeId": "cn1", "toNodeId": "cn2", "outputPort": "main", "inputPort": "main"}
                          ]
                        }
                      ]
                    }
                  ],
                  "connections": []
                }
              ],
              "credentials": [],
              "preconditions": []
            }
        """.trimIndent()

        val parsed = BackupSerializer.parseImport(legacyBundleJson)
        val chain = parsed.workflows.single().nodes.single().preconditions.single() as Precondition.ChainCheck
        assertEquals(1, chain.nodes.size)
        assertEquals("cn1", chain.nodes.single().id)
        assertEquals(1, chain.connections.size)
        assertEquals("cn2", chain.connections.single().toNodeId)
    }

    @Test
    fun testEncryptedBackupProtectsPreconditionHeadersChainsAndInlinePreconditions() {
        val pin = "StrongPin99"
        val chainPipelineJson = dev.zophop.domain.model.AppJson.encodeToString(
            dev.zophop.domain.model.WorkflowGraph(
                nodes = listOf(
                    WorkflowNode(
                        id = "cn1",
                        name = "Auth Step",
                        type = NodeType.HTTP_ACTION,
                        configJson = """{"url":"https://auth.example.com","bearerToken":"chain_entity_secret_111"}""",
                    ),
                ),
            ),
        )
        val precEntity = PreconditionEntity(
            id = "prec_hc_secret",
            name = "Protected Healthcheck",
            type = "HEALTH_CHECK",
            targetUrl = "https://api.example.com/health",
            headers = "Authorization: Bearer prec_entity_header_secret_222",
            pipelineJson = chainPipelineJson,
        )
        val workflow = Workflow(
            id = "WFPREC99",
            name = "Inline Prec Flow",
            nodes = listOf(
                WorkflowNode(
                    id = "n1",
                    name = "Webhook",
                    type = NodeType.HTTP_ACTION,
                    configJson = """{"url":"https://api.example.com","nested":{"access_token":"nested_node_secret_333"}}""",
                    preconditions = listOf(
                        Precondition.HealthCheck(
                            id = "inline_hc",
                            targetUrl = "https://api.example.com/ping",
                            headers = "X-Api-Key: inline_hc_secret_444",
                        ),
                        Precondition.ChainCheck(
                            id = "inline_cc",
                            name = "Inline Chain",
                            nodes = listOf(
                                WorkflowNode(
                                    id = "icn1",
                                    name = "Token Step",
                                    type = NodeType.HTTP_ACTION,
                                    configJson = """{"url":"https://api.example.com","bearerToken":"inline_chain_secret_555"}""",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val exported = BackupSerializer.createBackupJson(
            workflows = listOf(workflow),
            credentials = emptyList(),
            preconditions = listOf(precEntity),
            password = pin,
        )

        assertFalse("PreconditionEntity pipeline secret must not appear in plaintext", exported.contains("chain_entity_secret_111"))
        assertFalse("PreconditionEntity header secret must not appear in plaintext", exported.contains("prec_entity_header_secret_222"))
        assertFalse("Nested node JSON secret must not appear in plaintext", exported.contains("nested_node_secret_333"))
        assertFalse("Inline HealthCheck header secret must not appear in plaintext", exported.contains("inline_hc_secret_444"))
        assertFalse("Inline ChainCheck node secret must not appear in plaintext", exported.contains("inline_chain_secret_555"))

        val parsed = BackupSerializer.parseImport(exported)
        assertTrue(parsed.isEncrypted)

        val decrypted = BackupSerializer.decryptBackupData(parsed, pin)
        val restoredPrec = decrypted.preconditions.single()
        assertEquals("Authorization: Bearer prec_entity_header_secret_222", restoredPrec.headers)
        assertTrue(restoredPrec.pipelineJson.contains("chain_entity_secret_111"))

        val restoredNode = decrypted.workflows.single().nodes.single()
        assertTrue(restoredNode.configJson.contains("nested_node_secret_333"))
        val restoredInlineHc = restoredNode.preconditions[0] as Precondition.HealthCheck
        assertEquals("X-Api-Key: inline_hc_secret_444", restoredInlineHc.headers)
        val restoredInlineCc = restoredNode.preconditions[1] as Precondition.ChainCheck
        assertTrue(restoredInlineCc.nodes.single().configJson.contains("inline_chain_secret_555"))
    }

    @Test
    fun testResolveConflicts_keepBothRemapsChainedPipelineAndInlineChainCredentials() {
        val oldCredId = "old_cred_chain"
        val oldPrecId = "old_chain_prec"
        val cred = Credential(id = oldCredId, name = "SMTP Cred", type = "SMTP_AUTH")

        val pipelineJson = dev.zophop.domain.model.AppJson.encodeToString(
            dev.zophop.domain.model.WorkflowGraph(
                nodes = listOf(
                    WorkflowNode(
                        id = "pn1",
                        name = "Step 1",
                        type = NodeType.HTTP_ACTION,
                        configJson = """{"credentialId":"$oldCredId"}""",
                    ),
                    WorkflowNode(
                        id = "pn2",
                        name = "Step 2",
                        type = NodeType.TEMPLATE_FORMATTER,
                    ),
                ),
                connections = listOf(WorkflowConnection(fromNodeId = "pn1", toNodeId = "pn2")),
            ),
        )
        val chainEntity = PreconditionEntity(
            id = oldPrecId,
            name = "Chain Rule",
            type = "CHAINED_CHECK",
            pipelineJson = pipelineJson,
        )
        val workflow = Workflow(
            id = "wf_old",
            name = "Chained Flow",
            nodes = listOf(
                WorkflowNode(
                    id = "wn1",
                    name = "SMTP",
                    type = "dev.zophop.plugin.smtp.SMTP_ACTION",
                    configJson = """{"credentialId":"$oldCredId"}""",
                    preconditions = listOf(
                        Precondition.ChainCheck(
                            id = oldPrecId,
                            name = "Chain Rule",
                            nodes = listOf(
                                WorkflowNode(
                                    id = "in1",
                                    name = "Inline 1",
                                    type = NodeType.HTTP_ACTION,
                                    configJson = """{"credentialId":"$oldCredId"}""",
                                ),
                                WorkflowNode(
                                    id = "in2",
                                    name = "Inline 2",
                                    type = NodeType.TEMPLATE_FORMATTER,
                                ),
                            ),
                            connections = listOf(WorkflowConnection(fromNodeId = "in1", toNodeId = "in2")),
                        ),
                    ),
                ),
            ),
        )

        val resolved = BackupSerializer.resolveConflicts(
            parsed = ParsedBackupData(
                workflows = listOf(workflow),
                credentials = listOf(cred),
                preconditions = listOf(chainEntity),
            ),
            strategy = ImportConflictStrategy.KEEP_BOTH,
            existingWorkflows = emptyList(),
            existingCredentials = listOf(cred),
            existingPreconditions = listOf(chainEntity),
        )

        val newCredId = resolved.credentials.single().id
        val newPrec = resolved.preconditions.single()
        assertNotEquals(oldCredId, newCredId)
        assertNotEquals(oldPrecId, newPrec.id)

        val remappedPipelineGraph = dev.zophop.domain.model.AppJson.decodeFromString<dev.zophop.domain.model.WorkflowGraph>(
            newPrec.pipelineJson,
        )
        assertTrue(remappedPipelineGraph.nodes[0].configJson.contains(newCredId))
        assertFalse(remappedPipelineGraph.nodes[0].configJson.contains(oldCredId))
        assertNotEquals("pn1", remappedPipelineGraph.nodes[0].id)
        assertEquals(remappedPipelineGraph.nodes[0].id, remappedPipelineGraph.connections.single().fromNodeId)
        assertEquals(remappedPipelineGraph.nodes[1].id, remappedPipelineGraph.connections.single().toNodeId)

        val inlineChain = resolved.workflows.single().nodes.single().preconditions.single() as Precondition.ChainCheck
        assertEquals(newPrec.id, inlineChain.id)
        assertTrue(inlineChain.nodes[0].configJson.contains(newCredId))
        assertFalse(inlineChain.nodes[0].configJson.contains(oldCredId))
        assertNotEquals("in1", inlineChain.nodes[0].id)
        assertEquals(inlineChain.nodes[0].id, inlineChain.connections.single().fromNodeId)
        assertEquals(inlineChain.nodes[1].id, inlineChain.connections.single().toNodeId)
    }

    @Test
    fun testEncryptedBackupProtectsSmtpAndTelegramPluginCredentials() {
        val pin = "PluginPin#2026"
        val smtpCred = Credential(
            id = "cred_smtp_1",
            name = "Work SMTP",
            type = "SMTP_AUTH",
            dataMap = mapOf(
                "host" to "smtp.gmail.com",
                "port" to "587",
                "username" to "alerts@example.com",
                "password" to "smtp_app_password_secret",
            ),
        )
        val telegramCred = Credential(
            id = "cred_tg_1",
            name = "Alert Bot",
            type = "TELEGRAM_AUTH",
            dataMap = mapOf(
                "botToken" to "123456:ABC-DEF_telegram_secret",
                "chatId" to "-100123456",
            ),
        )
        val smtpWorkflow = Workflow(
            id = "WFSMTP01",
            name = "Forward via SMTP",
            nodes = listOf(
                WorkflowNode(
                    id = "n_smtp",
                    name = "Send Email",
                    type = "dev.zophop.plugin.smtp.SMTP_ACTION",
                    configJson = """{"credentialId":"cred_smtp_1","password":"inline_smtp_password","toAddresses":"ops@example.com"}""",
                ),
            ),
        )

        val exported = BackupSerializer.createBackupJson(
            workflows = listOf(smtpWorkflow),
            credentials = listOf(smtpCred, telegramCred),
            preconditions = emptyList(),
            password = pin,
        )

        assertFalse(exported.contains("smtp_app_password_secret"))
        assertFalse(exported.contains("123456:ABC-DEF_telegram_secret"))
        assertFalse(exported.contains("inline_smtp_password"))
        assertTrue(exported.contains("smtp.gmail.com"))

        val parsed = BackupSerializer.parseImport(exported)
        assertTrue(parsed.isEncrypted)

        val decrypted = BackupSerializer.decryptBackupData(parsed, pin)
        assertFalse(decrypted.isEncrypted)
        assertEquals("smtp_app_password_secret", decrypted.credentials[0].dataMap["password"])
        assertEquals("123456:ABC-DEF_telegram_secret", decrypted.credentials[1].dataMap["botToken"])
        assertTrue(decrypted.workflows.single().nodes.single().configJson.contains("inline_smtp_password"))
    }
}
