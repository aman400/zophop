package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.Workflow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Default implementation of [BackupService] backed by [BackupBridge] and [BackupSerializer].
 */
class DefaultBackupService(
    private val bridge: BackupBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupService {

    override suspend fun getAvailableCounts(): BackupCounts = withContext(dispatcher) {
        BackupCounts(
            workflowsCount = bridge.getWorkflows().size,
            credentialsCount = bridge.getCredentials().size,
            preconditionsCount = bridge.getPreconditions().size,
        )
    }

    override suspend fun getAvailableWorkflows(): List<Workflow> = withContext(dispatcher) {
        bridge.getWorkflows()
    }

    override suspend fun getAvailableCredentials(): List<Credential> = withContext(dispatcher) {
        bridge.getCredentials()
    }

    override suspend fun getAvailablePreconditions(): List<PreconditionEntity> = withContext(dispatcher) {
        bridge.getPreconditions()
    }

    override fun isBiometricLockEnabled(): Boolean = bridge.isBiometricLockEnabled()

    override suspend fun exportBackup(options: ExportOptions): String = withContext(dispatcher) {
        val allWorkflows = bridge.getWorkflows()
        val allCredentials = bridge.getCredentials()
        val allPreconditions = bridge.getPreconditions()

        val workflows = if (!options.includeWorkflows) {
            emptyList()
        } else if (options.selectedWorkflowIds != null) {
            allWorkflows.filter { it.id in options.selectedWorkflowIds }
        } else {
            allWorkflows
        }

        val relatedDeps = if (options.autoIncludeDependencies && workflows.isNotEmpty()) {
            BackupDependencyResolver.resolveWorkflowDependencies(
                selectedWorkflowIds = workflows.map { it.id }.toSet(),
                availableWorkflows = workflows,
                availablePreconditions = allPreconditions,
            )
        } else {
            WorkflowDependencies(preconditionIds = emptySet(), credentialIds = emptySet())
        }

        val credentials = if (!options.includeCredentials) {
            if (options.autoIncludeDependencies) {
                allCredentials.filter { it.id in relatedDeps.credentialIds }
            } else {
                emptyList()
            }
        } else if (options.selectedCredentialIds != null) {
            allCredentials.filter {
                it.id in options.selectedCredentialIds ||
                    (options.autoIncludeDependencies && it.id in relatedDeps.credentialIds)
            }
        } else {
            allCredentials
        }

        val preconditions = if (!options.includePreconditions) {
            if (options.autoIncludeDependencies) {
                allPreconditions.filter { it.id in relatedDeps.preconditionIds }
            } else {
                emptyList()
            }
        } else if (options.selectedPreconditionIds != null) {
            allPreconditions.filter {
                it.id in options.selectedPreconditionIds ||
                    (options.autoIncludeDependencies && it.id in relatedDeps.preconditionIds)
            }
        } else {
            allPreconditions
        }

        val password = if (options.encryptSecrets && !options.password.isNullOrBlank()) options.password else null

        BackupSerializer.createBackupJson(
            workflows = workflows,
            credentials = credentials,
            preconditions = preconditions,
            password = password,
            decryptLocalStorageValue = { bridge.decryptLocalStorageValue(it) },
        )
    }

    override suspend fun parseImport(jsonText: String): ParsedBackupData = withContext(dispatcher) {
        BackupSerializer.parseImport(jsonText)
    }

    override suspend fun decryptImport(
        data: ParsedBackupData,
        password: CharSequence,
    ): ParsedBackupData = withContext(dispatcher) {
        BackupSerializer.decryptBackupData(data, password)
    }

    override suspend fun executeImport(
        data: ParsedBackupData,
        strategy: ImportConflictStrategy,
    ): ImportResult = withContext(dispatcher) {
        val existingWorkflows = bridge.getWorkflows()
        val existingCredentials = bridge.getCredentials()
        val existingPreconditions = bridge.getPreconditions()

        val resolved = BackupSerializer.resolveConflicts(
            parsed = data,
            strategy = strategy,
            existingWorkflows = existingWorkflows,
            existingCredentials = existingCredentials,
            existingPreconditions = existingPreconditions,
        )

        bridge.saveCredentials(resolved.credentials)
        bridge.savePreconditions(resolved.preconditions)
        bridge.saveWorkflows(resolved.workflows)

        ImportResult(
            importedWorkflowsCount = resolved.workflows.size,
            importedCredentialsCount = resolved.credentials.size,
            importedPreconditionsCount = resolved.preconditions.size,
            message = "Import completed successfully",
        )
    }
}
