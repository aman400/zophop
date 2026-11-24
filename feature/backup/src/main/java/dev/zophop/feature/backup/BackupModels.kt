package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.Workflow

enum class ImportConflictStrategy {
    OVERWRITE,
    KEEP_BOTH,
    SKIP_EXISTING,
}

data class ParsedBackupData(
    val workflows: List<Workflow> = emptyList(),
    val credentials: List<Credential> = emptyList(),
    val preconditions: List<PreconditionEntity> = emptyList(),
    val isEncrypted: Boolean = false,
    val verificationToken: String? = null,
) {
    val totalCount: Int get() = workflows.size + credentials.size + preconditions.size
    val isEmpty: Boolean get() = totalCount == 0
}

data class ExportOptions(
    val includeWorkflows: Boolean = true,
    val includeCredentials: Boolean = true,
    val includePreconditions: Boolean = true,
    val encryptSecrets: Boolean = false,
    val password: String? = null,
)

data class ImportResult(
    val importedWorkflowsCount: Int,
    val importedCredentialsCount: Int,
    val importedPreconditionsCount: Int,
    val message: String = "",
)

data class BackupCounts(
    val workflowsCount: Int = 0,
    val credentialsCount: Int = 0,
    val preconditionsCount: Int = 0,
) {
    val totalCount: Int get() = workflowsCount + credentialsCount + preconditionsCount
}
