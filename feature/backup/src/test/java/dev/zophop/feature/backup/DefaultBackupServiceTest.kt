package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBackupServiceTest {

    private class FakeBackupBridge(
        var biometricLockEnabled: Boolean = false,
    ) : BackupBridge {
        val workflows = mutableListOf<Workflow>()
        val credentials = mutableListOf<Credential>()
        val preconditions = mutableListOf<PreconditionEntity>()
        val installedNodeTypes = mutableSetOf("HTTP_ACTION", "MANUAL_TRIGGER")

        override suspend fun getWorkflows(): List<Workflow> = workflows.toList()

        override suspend fun getCredentials(): List<Credential> = credentials.toList()

        override suspend fun getPreconditions(): List<PreconditionEntity> = preconditions.toList()

        override suspend fun saveWorkflows(workflows: List<Workflow>) {
            this.workflows.addAll(workflows)
        }

        override suspend fun saveCredentials(credentials: List<Credential>) {
            this.credentials.addAll(credentials)
        }

        override suspend fun savePreconditions(preconditions: List<PreconditionEntity>) {
            this.preconditions.addAll(preconditions)
        }

        override fun decryptLocalStorageValue(encryptedValue: String): String =
            encryptedValue.removePrefix("local_enc:")

        override fun isBiometricLockEnabled(): Boolean = biometricLockEnabled

        override suspend fun getMissingNodeTypes(nodeTypes: Collection<String>): Set<String> =
            nodeTypes.filter { it.isNotBlank() && it !in installedNodeTypes }.toSet()
    }

    @Test
    fun testBackupService_exportAndImportEndToEnd() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge, testDispatcher)

        bridge.workflows.add(
            Workflow(
                id = "wf_1",
                name = "Test Workflow",
                description = "Desc",
                isEnabled = true,
                nodes = emptyList(),
                connections = emptyList(),
            ),
        )
        bridge.credentials.add(
            Credential(
                id = "cred_1",
                name = "API Key",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to "local_enc:secret_token_123"),
            ),
        )

        val counts = service.getAvailableCounts()
        assertEquals(1, counts.workflowsCount)
        assertEquals(1, counts.credentialsCount)
        assertEquals(0, counts.preconditionsCount)

        val exportedJson = service.exportBackup(
            ExportOptions(
                includeWorkflows = true,
                includeCredentials = true,
                includePreconditions = true,
                encryptSecrets = false,
            ),
        )

        assertTrue(exportedJson.contains("Test Workflow"))
        assertTrue(exportedJson.contains("secret_token_123"))

        val parsed = service.parseImport(exportedJson)
        assertEquals(1, parsed.workflows.size)
        assertEquals(1, parsed.credentials.size)

        // Clear target bridge and import
        val targetBridge = FakeBackupBridge()
        val targetService = DefaultBackupService(targetBridge, testDispatcher)

        val result = targetService.executeImport(parsed, ImportConflictStrategy.OVERWRITE)
        assertEquals(1, result.importedWorkflowsCount)
        assertEquals(1, result.importedCredentialsCount)
        assertEquals(0, result.importedPreconditionsCount)
        assertEquals(1, targetBridge.workflows.size)
        assertEquals(1, targetBridge.credentials.size)
    }

    @Test
    fun testExportBackup_selectiveWorkflowsAutoIncludesDependencies() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge, testDispatcher)

        val node1 = WorkflowNode(
            id = "n_1",
            name = "HTTP",
            type = "HTTP_ACTION",
            configJson = """{"credentialId":"cred_wf1"}""",
            preconditions = listOf(Precondition.InternetCheck(id = "prec_wf1")),
        )
        val wf1 = Workflow(id = "wf_1", name = "Workflow 1", nodes = listOf(node1))

        val node2 = WorkflowNode(
            id = "n_2",
            name = "Other",
            type = "HTTP_ACTION",
            configJson = """{"credentialId":"cred_wf2"}""",
        )
        val wf2 = Workflow(id = "wf_2", name = "Workflow 2", nodes = listOf(node2))

        bridge.workflows.addAll(listOf(wf1, wf2))
        bridge.credentials.add(Credential(id = "cred_wf1", name = "Cred 1", type = CredentialType.BEARER_TOKEN))
        bridge.credentials.add(Credential(id = "cred_wf2", name = "Cred 2", type = CredentialType.BEARER_TOKEN))
        bridge.preconditions.add(PreconditionEntity(id = "prec_wf1", name = "Rule 1", type = "INTERNET_CHECK"))

        // Export ONLY wf_1 with autoIncludeDependencies = true, includeCredentials = false
        val exportedJson = service.exportBackup(
            ExportOptions(
                includeWorkflows = true,
                selectedWorkflowIds = setOf("wf_1"),
                includeCredentials = false,
                includePreconditions = false,
                autoIncludeDependencies = true,
            ),
        )

        val parsed = service.parseImport(exportedJson)
        assertEquals(1, parsed.workflows.size)
        assertEquals("wf_1", parsed.workflows.first().id)

        // cred_wf1 and prec_wf1 should be auto-included!
        assertEquals(1, parsed.credentials.size)
        assertEquals("cred_wf1", parsed.credentials.first().id)
        assertEquals(1, parsed.preconditions.size)
        assertEquals("prec_wf1", parsed.preconditions.first().id)
    }

    @Test
    fun testExportBackup_autoIncludeDependenciesFalse_doesNotIncludeDependencies() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge, testDispatcher)

        val node = WorkflowNode(
            id = "n1",
            name = "Node",
            type = "HTTP_ACTION",
            configJson = """{"credentialId":"cred_1"}""",
            preconditions = listOf(Precondition.InternetCheck(id = "prec_1")),
        )
        val wf = Workflow(id = "wf_1", name = "Workflow", nodes = listOf(node))

        bridge.workflows.add(wf)
        bridge.credentials.add(Credential(id = "cred_1", name = "Cred 1", type = CredentialType.BEARER_TOKEN))
        bridge.preconditions.add(PreconditionEntity(id = "prec_1", name = "Rule 1", type = "INTERNET_CHECK"))

        val exportedJson = service.exportBackup(
            ExportOptions(
                includeWorkflows = true,
                selectedWorkflowIds = setOf("wf_1"),
                includeCredentials = false,
                includePreconditions = false,
                autoIncludeDependencies = false,
            ),
        )

        val parsed = service.parseImport(exportedJson)
        assertEquals(1, parsed.workflows.size)
        assertTrue(parsed.credentials.isEmpty())
        assertTrue(parsed.preconditions.isEmpty())
    }

    @Test
    fun testExportBackup_includeWorkflowsFalse_exportsNoWorkflows() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge, testDispatcher)

        bridge.workflows.add(Workflow(id = "wf_1", name = "WF", nodes = emptyList()))
        bridge.credentials.add(Credential(id = "cred_1", name = "Cred 1", type = CredentialType.BEARER_TOKEN))

        val exportedJson = service.exportBackup(
            ExportOptions(
                includeWorkflows = false,
                includeCredentials = true,
                includePreconditions = false,
            ),
        )

        val parsed = service.parseImport(exportedJson)
        assertTrue(parsed.workflows.isEmpty())
        assertEquals(1, parsed.credentials.size)
    }

    @Test
    fun testExportBackup_explicitSelectedCredentialsCombinedWithDependencies() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge, testDispatcher)

        val node = WorkflowNode(
            id = "n1",
            name = "Node",
            type = "HTTP_ACTION",
            configJson = """{"credentialId":"cred_dep"}""",
        )
        val wf = Workflow(id = "wf_1", name = "WF", nodes = listOf(node))

        bridge.workflows.add(wf)
        bridge.credentials.add(Credential(id = "cred_dep", name = "Dependency Cred", type = CredentialType.BEARER_TOKEN))
        bridge.credentials.add(Credential(id = "cred_standalone", name = "Standalone Cred", type = CredentialType.BEARER_TOKEN))
        bridge.credentials.add(Credential(id = "cred_other", name = "Other Cred", type = CredentialType.BEARER_TOKEN))

        val exportedJson = service.exportBackup(
            ExportOptions(
                includeWorkflows = true,
                selectedWorkflowIds = setOf("wf_1"),
                includeCredentials = true,
                selectedCredentialIds = setOf("cred_standalone"),
                autoIncludeDependencies = true,
            ),
        )

        val parsed = service.parseImport(exportedJson)
        assertEquals(2, parsed.credentials.size)
        val credIds = parsed.credentials.map { it.id }.toSet()
        assertEquals(setOf("cred_dep", "cred_standalone"), credIds)
    }

    @Test
    fun testExportAndDecrypt_passwordProtectedBackup() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge, testDispatcher)

        bridge.credentials.add(
            Credential(
                id = "cred_secret",
                name = "Secret Cred",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to "super_secret_payload_999"),
            ),
        )

        val exportedJson = service.exportBackup(
            ExportOptions(
                includeWorkflows = false,
                includeCredentials = true,
                encryptSecrets = true,
                password = "Password#9876",
            ),
        )

        // Raw JSON must not contain cleartext secret
        assertFalse(exportedJson.contains("super_secret_payload_999"))

        val parsed = service.parseImport(exportedJson)
        assertTrue(parsed.isEncrypted)
        assertNotNull(parsed.verificationToken)

        // Decrypt with wrong password throws SecurityException
        try {
            service.decryptImport(parsed, "WrongPassword")
            fail("Expected SecurityException on wrong password")
        } catch (_: SecurityException) {
            // Expected
        }

        // Decrypt with correct password
        val decrypted = service.decryptImport(parsed, "Password#9876")
        assertFalse(decrypted.isEncrypted)
        assertEquals("super_secret_payload_999", decrypted.credentials[0].dataMap["token"])
    }

    @Test
    fun testExecuteImport_conflictStrategyKeepBoth() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge, testDispatcher)

        val existingWf = Workflow(id = "wf_1", name = "Existing WF", nodes = emptyList())
        bridge.workflows.add(existingWf)

        val importedData = ParsedBackupData(
            workflows = listOf(Workflow(id = "wf_1", name = "Existing WF", nodes = emptyList())),
        )

        val result = service.executeImport(importedData, ImportConflictStrategy.KEEP_BOTH)
        assertEquals(1, result.importedWorkflowsCount)

        // In bridge, there should now be the original + the renamed imported workflow
        assertEquals(2, bridge.workflows.size)
        assertTrue(bridge.workflows.any { it.id == "wf_1" && it.name == "Existing WF" })
        assertTrue(bridge.workflows.any { it.id != "wf_1" && it.name == "Existing WF (Imported)" })
    }

    @Test
    fun testExecuteImport_conflictStrategySkipExisting() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge, testDispatcher)

        bridge.workflows.add(Workflow(id = "wf_1", name = "Original", nodes = emptyList()))

        val importedData = ParsedBackupData(
            workflows = listOf(
                Workflow(id = "wf_1", name = "Attempted Overwrite", nodes = emptyList()),
                Workflow(id = "wf_2", name = "Brand New", nodes = emptyList()),
            ),
        )

        val result = service.executeImport(importedData, ImportConflictStrategy.SKIP_EXISTING)
        assertEquals(1, result.importedWorkflowsCount)

        assertEquals(2, bridge.workflows.size)
        assertEquals("Original", bridge.workflows.first { it.id == "wf_1" }.name)
        assertTrue(bridge.workflows.any { it.id == "wf_2" })
    }

    @Test
    fun testGetAvailableItems_returnsBridgeItems() = runTest {
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge)

        bridge.workflows.add(Workflow(id = "w1", name = "W1", nodes = emptyList()))
        bridge.credentials.add(Credential(id = "c1", name = "C1", type = "BEARER"))
        bridge.preconditions.add(PreconditionEntity(id = "p1", name = "P1", type = "CHECK"))

        assertEquals(1, service.getAvailableWorkflows().size)
        assertEquals("w1", service.getAvailableWorkflows().first().id)
        assertEquals(1, service.getAvailableCredentials().size)
        assertEquals("c1", service.getAvailableCredentials().first().id)
        assertEquals(1, service.getAvailablePreconditions().size)
        assertEquals("p1", service.getAvailablePreconditions().first().id)
    }

    @Test
    fun testBiometricLockQuery() {
        val bridge = FakeBackupBridge(biometricLockEnabled = true)
        val service = DefaultBackupService(bridge)
        assertTrue(service.isBiometricLockEnabled())

        bridge.biometricLockEnabled = false
        assertFalse(service.isBiometricLockEnabled())
    }

    @Test
    fun testExportAndImport_chainedPreconditionSmtpWorkflow_keepBothEndToEnd() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sourceBridge = FakeBackupBridge()
        val sourceService = DefaultBackupService(sourceBridge, testDispatcher)

        val smtpCred = Credential(
            id = "cred_smtp",
            name = "SMTP Account",
            type = "SMTP_AUTH",
            dataMap = mapOf(
                "host" to "smtp.example.com",
                "username" to "bot@example.com",
                "password" to "local_enc:smtp_secret_pass",
            ),
        )
        val chainPipelineCred = Credential(
            id = "cred_chain_http",
            name = "Chain Auth",
            type = CredentialType.BEARER_TOKEN,
            dataMap = mapOf("token" to "local_enc:chain_token_xyz"),
        )
        val chainPipelineJson = dev.zophop.domain.model.AppJson.encodeToString(
            dev.zophop.domain.model.WorkflowGraph(
                nodes = listOf(
                    WorkflowNode(
                        id = "cn_1",
                        name = "Auth Ping",
                        type = "HTTP_ACTION",
                        configJson = """{"credentialId":"cred_chain_http","url":"https://auth.example.com"}""",
                    ),
                ),
            ),
        )
        val chainPrec = PreconditionEntity(
            id = "prec_chain",
            name = "Auth Chain",
            type = "CHAINED_CHECK",
            pipelineJson = chainPipelineJson,
        )
        val workflow = Workflow(
            id = "wf_smtp",
            name = "Email Alert",
            nodes = listOf(
                WorkflowNode(
                    id = "n_smtp",
                    name = "SMTP Action",
                    type = "dev.zophop.plugin.smtp.SMTP_ACTION",
                    configJson = """{"credentialId":"cred_smtp","toAddresses":"ops@example.com"}""",
                    preconditions = listOf(Precondition.ChainCheck(id = "prec_chain", name = "Auth Chain")),
                ),
            ),
        )

        sourceBridge.workflows.add(workflow)
        sourceBridge.credentials.addAll(listOf(smtpCred, chainPipelineCred))
        sourceBridge.preconditions.add(chainPrec)

        val exportedJson = sourceService.exportBackup(
            ExportOptions(
                includeWorkflows = true,
                selectedWorkflowIds = setOf("wf_smtp"),
                includeCredentials = false,
                includePreconditions = false,
                autoIncludeDependencies = true,
                encryptSecrets = true,
                password = "Pin#4321",
            ),
        )

        assertFalse(exportedJson.contains("smtp_secret_pass"))
        assertFalse(exportedJson.contains("chain_token_xyz"))

        // Target already has same IDs -> import with KEEP_BOTH
        val targetBridge = FakeBackupBridge()
        targetBridge.workflows.add(workflow)
        targetBridge.credentials.addAll(listOf(smtpCred, chainPipelineCred))
        targetBridge.preconditions.add(chainPrec)
        val targetService = DefaultBackupService(targetBridge, testDispatcher)

        val parsed = targetService.parseImport(exportedJson)
        val decrypted = targetService.decryptImport(parsed, "Pin#4321")
        val result = targetService.executeImport(decrypted, ImportConflictStrategy.KEEP_BOTH)

        assertEquals(1, result.importedWorkflowsCount)
        assertEquals(2, result.importedCredentialsCount)
        assertEquals(1, result.importedPreconditionsCount)

        val importedCreds = targetBridge.credentials.filter { it.name.endsWith("(Imported)") }
        assertEquals(2, importedCreds.size)
        val importedSmtpCredId = importedCreds.first { it.type == "SMTP_AUTH" }.id
        val importedChainCredId = importedCreds.first { it.type == CredentialType.BEARER_TOKEN }.id

        val importedPrec = targetBridge.preconditions.first { it.name == "Auth Chain (Imported)" }
        assertTrue(importedPrec.pipelineJson.contains(importedChainCredId))
        assertFalse(importedPrec.pipelineJson.contains("cred_chain_http"))

        val importedWf = targetBridge.workflows.first { it.name == "Email Alert (Imported)" }
        assertTrue(importedWf.nodes.single().configJson.contains(importedSmtpCredId))
        assertEquals(importedPrec.id, importedWf.nodes.single().preconditions.single().id)
    }

    @Test
    fun testFindMissingNodeTypes_detectsMissingExternalNodesAcrossWorkflowAndChains() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakeBackupBridge()
        val service = DefaultBackupService(bridge, testDispatcher)

        val chainGraphJson = dev.zophop.domain.model.AppJson.encodeToString(
            dev.zophop.domain.model.WorkflowGraph(
                nodes = listOf(
                    WorkflowNode(
                        id = "cn_ext",
                        name = "Telegram Ping",
                        type = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                    ),
                ),
            ),
        )
        val data = ParsedBackupData(
            workflows = listOf(
                Workflow(
                    id = "wf_ext",
                    name = "External Flow",
                    nodes = listOf(
                        WorkflowNode(id = "n1", name = "Manual", type = "MANUAL_TRIGGER"),
                        WorkflowNode(
                            id = "n2",
                            name = "Send Email",
                            type = "dev.zophop.plugin.smtp.SMTP_ACTION",
                            preconditions = listOf(
                                Precondition.ChainCheck(
                                    id = "inline_c",
                                    name = "Inline",
                                    nodes = listOf(
                                        WorkflowNode(
                                            id = "in1",
                                            name = "Inline Ext",
                                            type = "com.example.slack.SLACK_ACTION",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            preconditions = listOf(
                PreconditionEntity(
                    id = "prec_c",
                    name = "Entity Chain",
                    type = "CHAINED_CHECK",
                    pipelineJson = chainGraphJson,
                ),
            ),
        )

        val missing = service.findMissingNodeTypes(data)
        assertEquals(
            setOf(
                "dev.zophop.plugin.smtp.SMTP_ACTION",
                "com.example.slack.SLACK_ACTION",
                "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
            ),
            missing,
        )
    }
}
