package dev.zophop.bridge

import dev.zophop.data.CredentialRepository
import dev.zophop.data.DatabaseEncryption
import dev.zophop.data.PreconditionEntity
import dev.zophop.data.PreconditionRepository
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.data.WorkflowRepository
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.Workflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackupBridgeTest {

    private class FakeWorkflowRepo : WorkflowRepository {
        val saved = mutableListOf<Workflow>()

        override fun getAllWorkflowsFlow(): Flow<List<Workflow>> = emptyFlow()

        override suspend fun getActiveWorkflows(): List<Workflow> = saved.filter { it.isEnabled }

        override suspend fun getWorkflowById(id: String): Workflow? = saved.find { it.id == id }

        override suspend fun saveWorkflow(workflow: Workflow) {
            saved.add(workflow)
        }

        override suspend fun setWorkflowEnabled(id: String, isEnabled: Boolean) {}

        override suspend fun deleteWorkflow(id: String) {
            saved.removeAll { it.id == id }
        }

        override suspend fun getWorkflowCount(): Int = saved.size

        override suspend fun saveExecutionLog(log: WorkflowExecutionLogEntity) {}

        override suspend fun getExecutionLogById(id: String): WorkflowExecutionLogEntity? = null

        override fun getRecentExecutionLogsFlow(limit: Int): Flow<List<WorkflowExecutionLogEntity>> = emptyFlow()

        override fun getLogsForWorkflowFlow(workflowId: String, limit: Int): Flow<List<WorkflowExecutionLogEntity>> = emptyFlow()

        override suspend fun clearLogsForWorkflow(workflowId: String) {}

        override fun getQueuedWorkflowLogsFlow(): Flow<List<WorkflowExecutionLogEntity>> = emptyFlow()

        override suspend fun getQueuedWorkflowLogs(): List<WorkflowExecutionLogEntity> = emptyList()

        override suspend fun deleteExecutionLogById(id: String) {}
    }

    private class FakeCredentialRepo : CredentialRepository {
        val saved = mutableListOf<Credential>()

        override fun getAllCredentialsFlow(): Flow<List<Credential>> = emptyFlow()

        override suspend fun getCredentialById(id: String): Credential? = saved.find { it.id == id }

        override suspend fun saveCredential(credential: Credential) {
            saved.add(credential)
        }

        override suspend fun deleteCredential(id: String) {
            saved.removeAll { it.id == id }
        }
    }

    private class FakePreconditionRepo : PreconditionRepository {
        val saved = mutableListOf<PreconditionEntity>()

        override fun getAllPreconditionsFlow(): Flow<List<PreconditionEntity>> = emptyFlow()

        override suspend fun getPreconditionById(id: String): PreconditionEntity? = saved.find { it.id == id }

        override suspend fun savePrecondition(precondition: PreconditionEntity) {
            saved.add(precondition)
        }

        override suspend fun deletePrecondition(id: String) {
            saved.removeAll { it.id == id }
        }
    }

    @Test
    fun testGetMethods_invokeProvidedLambdas() = runTest {
        val wfList = listOf(Workflow(id = "wf_1", name = "Test WF", nodes = emptyList()))
        val credList = listOf(Credential(id = "c_1", name = "Cred", type = CredentialType.BEARER_TOKEN))
        val precList = listOf(PreconditionEntity(id = "p_1", name = "Prec", type = "INTERNET_CHECK"))

        val bridge = AppBackupBridge(
            workflowRepository = FakeWorkflowRepo(),
            credentialRepository = FakeCredentialRepo(),
            preconditionRepository = FakePreconditionRepo(),
            currentWorkflows = { wfList },
            currentCredentials = { credList },
            currentPreconditions = { precList },
            isBiometricEnabled = { true },
        )

        assertEquals(wfList, bridge.getWorkflows())
        assertEquals(credList, bridge.getCredentials())
        assertEquals(precList, bridge.getPreconditions())
        assertTrue(bridge.isBiometricLockEnabled())
    }

    @Test
    fun testSaveMethods_persistToRepositories() = runTest {
        val wfRepo = FakeWorkflowRepo()
        val credRepo = FakeCredentialRepo()
        val precRepo = FakePreconditionRepo()

        val bridge = AppBackupBridge(
            workflowRepository = wfRepo,
            credentialRepository = credRepo,
            preconditionRepository = precRepo,
            currentWorkflows = { emptyList() },
            currentCredentials = { emptyList() },
            currentPreconditions = { emptyList() },
        )

        val wf = Workflow(id = "wf_new", name = "New WF", nodes = emptyList())
        val cred = Credential(id = "cred_new", name = "New Cred", type = CredentialType.BEARER_TOKEN)
        val prec = PreconditionEntity(id = "prec_new", name = "New Prec", type = "VPN_CHECK")

        bridge.saveWorkflows(listOf(wf))
        bridge.saveCredentials(listOf(cred))
        bridge.savePreconditions(listOf(prec))

        assertEquals(listOf(wf), wfRepo.saved)
        assertEquals(listOf(cred), credRepo.saved)
        assertEquals(listOf(prec), precRepo.saved)
    }

    @Test
    fun testIsBiometricLockEnabled_reflectsDynamicLambda() {
        var isLocked = false
        val bridge = AppBackupBridge(
            workflowRepository = FakeWorkflowRepo(),
            credentialRepository = FakeCredentialRepo(),
            preconditionRepository = FakePreconditionRepo(),
            currentWorkflows = { emptyList() },
            currentCredentials = { emptyList() },
            currentPreconditions = { emptyList() },
            isBiometricEnabled = { isLocked },
        )

        assertFalse(bridge.isBiometricLockEnabled())
        isLocked = true
        assertTrue(bridge.isBiometricLockEnabled())
    }

    @Test
    fun testDecryptLocalStorageValue_decryptsEncryptedValue() {
        val plain = "plaintext_secret_123"
        val encrypted = DatabaseEncryption.encrypt(plain)

        val bridge = AppBackupBridge(
            workflowRepository = FakeWorkflowRepo(),
            credentialRepository = FakeCredentialRepo(),
            preconditionRepository = FakePreconditionRepo(),
            currentWorkflows = { emptyList() },
            currentCredentials = { emptyList() },
            currentPreconditions = { emptyList() },
        )

        val decrypted = bridge.decryptLocalStorageValue(encrypted)
        assertEquals(plain, decrypted)
    }

    @Test
    fun testGetMissingNodeTypes_distinguishesBuiltInDiscoveredAndMissingExternalPlugins() = runTest {
        val smtpManifest =
            dev.zophop.plugin.api.NodePluginManifest(
                typeId = "dev.zophop.plugin.smtp.SMTP_ACTION",
                displayName = "Send Email (SMTP)",
                description = "SMTP email",
                category = dev.zophop.plugin.api.NodeCategory.ACTION,
            )
        var discoveredCalled = false
        val fakeExternalRegistry =
            object : dev.zophop.domain.engine.plugin.ExternalPluginRegistry(context = null) {
                private var plugins = emptyList<dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo>()

                override suspend fun discoverPlugins(): List<dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo> {
                    discoveredCalled = true
                    plugins =
                        listOf(
                            dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo(
                                packageName = "dev.zophop.plugin.smtp",
                                serviceClassName = "dev.zophop.plugin.smtp.SmtpNodePluginService",
                                manifest = smtpManifest,
                                apiVersion = 1,
                            ),
                        )
                    return plugins
                }

                override fun getPluginByTypeId(typeId: String): dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo? =
                    plugins.find {
                        it.manifest.typeId.equals(typeId, ignoreCase = true) ||
                            it.manifest.typeId.endsWith(".$typeId", ignoreCase = true)
                    }
            }

        val bridge = AppBackupBridge(
            workflowRepository = FakeWorkflowRepo(),
            credentialRepository = FakeCredentialRepo(),
            preconditionRepository = FakePreconditionRepo(),
            externalPluginRegistry = fakeExternalRegistry,
        )

        val missing =
            bridge.getMissingNodeTypes(
                listOf(
                    "HTTP_ACTION",
                    "SMS_TRIGGER",
                    "dev.zophop.plugin.smtp.SMTP_ACTION",
                    "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                ),
            )

        assertTrue(discoveredCalled)
        assertEquals(setOf("dev.zophop.plugin.telegram.TELEGRAM_ACTION"), missing)
    }
}
