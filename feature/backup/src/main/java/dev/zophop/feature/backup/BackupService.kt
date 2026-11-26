package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.Workflow

/**
 * Service interface for orchestrating backup export, parsing, decryption,
 * and importing with conflict resolution.
 *
 * This decouples the UI from the underlying backup serialization engine and storage bridge.
 */
interface BackupService {
    suspend fun getAvailableCounts(): BackupCounts

    suspend fun getAvailableWorkflows(): List<Workflow> = emptyList()

    suspend fun getAvailableCredentials(): List<Credential> = emptyList()

    suspend fun getAvailablePreconditions(): List<PreconditionEntity> = emptyList()

    fun isBiometricLockEnabled(): Boolean = false

    suspend fun exportBackup(options: ExportOptions): String

    suspend fun parseImport(jsonText: String): ParsedBackupData

    suspend fun decryptImport(data: ParsedBackupData, password: CharSequence): ParsedBackupData

    suspend fun executeImport(data: ParsedBackupData, strategy: ImportConflictStrategy): ImportResult

    suspend fun findMissingNodeTypes(data: ParsedBackupData): Set<String> = emptySet()
}
