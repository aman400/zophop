package dev.zophop.feature.backup

/**
 * Service interface for orchestrating backup export, parsing, decryption,
 * and importing with conflict resolution.
 *
 * This decouples the UI from the underlying backup serialization engine and storage bridge.
 */
interface BackupService {
    suspend fun getAvailableCounts(): BackupCounts

    suspend fun exportBackup(options: ExportOptions): String

    suspend fun parseImport(jsonText: String): ParsedBackupData

    suspend fun decryptImport(data: ParsedBackupData, password: CharSequence): ParsedBackupData

    suspend fun executeImport(data: ParsedBackupData, strategy: ImportConflictStrategy): ImportResult
}
