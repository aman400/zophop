package dev.zophop.feature.backup

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

    override suspend fun exportBackup(options: ExportOptions): String = withContext(dispatcher) {
        val workflows = if (options.includeWorkflows) bridge.getWorkflows() else emptyList()
        val credentials = if (options.includeCredentials) bridge.getCredentials() else emptyList()
        val preconditions = if (options.includePreconditions) bridge.getPreconditions() else emptyList()
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
