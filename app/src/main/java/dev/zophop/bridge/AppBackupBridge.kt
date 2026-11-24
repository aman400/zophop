package dev.zophop.bridge

import dev.zophop.data.CredentialRepository
import dev.zophop.data.DatabaseEncryption
import dev.zophop.data.PreconditionEntity
import dev.zophop.data.PreconditionRepository
import dev.zophop.data.WorkflowRepository
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.Workflow
import dev.zophop.feature.backup.BackupBridge

/**
 * Host application implementation of [BackupBridge].
 * Connects the backup & restore module to Room database repositories and state flows.
 */
class AppBackupBridge(
    private val workflowRepository: WorkflowRepository,
    private val credentialRepository: CredentialRepository,
    private val preconditionRepository: PreconditionRepository,
    private val currentWorkflows: () -> List<Workflow>,
    private val currentCredentials: () -> List<Credential>,
    private val currentPreconditions: () -> List<PreconditionEntity>,
) : BackupBridge {

    override suspend fun getWorkflows(): List<Workflow> = currentWorkflows()

    override suspend fun getCredentials(): List<Credential> = currentCredentials()

    override suspend fun getPreconditions(): List<PreconditionEntity> = currentPreconditions()

    override suspend fun saveWorkflows(workflows: List<Workflow>) {
        for (wf in workflows) {
            workflowRepository.saveWorkflow(wf)
        }
    }

    override suspend fun saveCredentials(credentials: List<Credential>) {
        for (cred in credentials) {
            credentialRepository.saveCredential(cred)
        }
    }

    override suspend fun savePreconditions(preconditions: List<PreconditionEntity>) {
        for (prec in preconditions) {
            preconditionRepository.savePrecondition(prec)
        }
    }

    override fun decryptLocalStorageValue(encryptedValue: String): String =
        DatabaseEncryption.decrypt(encryptedValue)
}
