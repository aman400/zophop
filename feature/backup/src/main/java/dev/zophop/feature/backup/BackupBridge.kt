package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.Workflow

/**
 * Bridge interface allowing the host application to provide workflows, credentials,
 * and preconditions to the backup feature, and to persist restored entities back to storage.
 *
 * This decouples the backup module from specific Room repositories or local database layers.
 */
interface BackupBridge {
    suspend fun getWorkflows(): List<Workflow>

    suspend fun getCredentials(): List<Credential>

    suspend fun getPreconditions(): List<PreconditionEntity>

    suspend fun saveWorkflows(workflows: List<Workflow>)

    suspend fun saveCredentials(credentials: List<Credential>)

    suspend fun savePreconditions(preconditions: List<PreconditionEntity>)

    /**
     * Decrypts values that were encrypted by local database storage (e.g. AndroidKeyStore)
     * so that the exported backup can be used on other devices.
     */
    fun decryptLocalStorageValue(encryptedValue: String): String = encryptedValue
}
