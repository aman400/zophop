package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.Workflow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBackupServiceTest {

    private class FakeBackupBridge : BackupBridge {
        val workflows = mutableListOf<Workflow>()
        val credentials = mutableListOf<Credential>()
        val preconditions = mutableListOf<PreconditionEntity>()

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
}
