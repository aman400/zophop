package dev.zophop.bridge

import dev.zophop.data.CredentialRepository
import dev.zophop.data.DatabaseEncryption
import dev.zophop.data.PreconditionEntity
import dev.zophop.data.PreconditionRepository
import dev.zophop.data.SettingsRepository
import dev.zophop.data.WorkflowRepository
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.engine.plugin.ExternalPluginRegistry
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.Workflow
import dev.zophop.feature.backup.BackupBridge
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject

/**
 * Host application implementation of [BackupBridge].
 * Connects the backup & restore module to Room database repositories and state flows.
 */
class AppBackupBridge(
    private val workflowRepository: WorkflowRepository,
    private val credentialRepository: CredentialRepository,
    private val preconditionRepository: PreconditionRepository,
    private val currentWorkflows: () -> List<Workflow> = { emptyList() },
    private val currentCredentials: () -> List<Credential> = { emptyList() },
    private val currentPreconditions: () -> List<PreconditionEntity> = { emptyList() },
    private val isBiometricEnabled: () -> Boolean = { false },
    private val externalPluginRegistry: ExternalPluginRegistry? = null,
) : BackupBridge {
    @Inject
    constructor(
        workflowRepository: WorkflowRepository,
        credentialRepository: CredentialRepository,
        preconditionRepository: PreconditionRepository,
        settingsRepository: SettingsRepository,
        externalPluginRegistry: ExternalPluginRegistry,
    ) : this(
        workflowRepository = workflowRepository,
        credentialRepository = credentialRepository,
        preconditionRepository = preconditionRepository,
        currentWorkflows = { emptyList() },
        currentCredentials = { emptyList() },
        currentPreconditions = { emptyList() },
        isBiometricEnabled = { settingsRepository.getSettings().lockCredentialsWithBiometric },
        externalPluginRegistry = externalPluginRegistry,
    )

    override suspend fun getWorkflows(): List<Workflow> {
        val inMemory = currentWorkflows()
        return if (inMemory.isNotEmpty()) {
            inMemory
        } else {
            workflowRepository.getAllWorkflowsFlow().firstOrNull().orEmpty()
        }
    }

    override suspend fun getCredentials(): List<Credential> {
        val inMemory = currentCredentials()
        return if (inMemory.isNotEmpty()) {
            inMemory
        } else {
            credentialRepository.getAllCredentialsFlow().firstOrNull().orEmpty()
        }
    }

    override suspend fun getPreconditions(): List<PreconditionEntity> {
        val inMemory = currentPreconditions()
        return if (inMemory.isNotEmpty()) {
            inMemory
        } else {
            preconditionRepository.getAllPreconditionsFlow().firstOrNull().orEmpty()
        }
    }

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

    override fun isBiometricLockEnabled(): Boolean = isBiometricEnabled()

    override suspend fun getMissingNodeTypes(nodeTypes: Collection<String>): Set<String> {
        val nonBuiltIn =
            nodeTypes
                .asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .filter { BuiltInPluginRegistry.getBuiltInManifestByTypeId(it) == null }
                .toList()
        val registry = externalPluginRegistry
        return when {
            nonBuiltIn.isEmpty() -> emptySet()
            registry == null -> nonBuiltIn.toSet()
            else -> {
                val missingInitially = nonBuiltIn.filter { registry.getPluginByTypeId(it) == null }
                if (missingInitially.isEmpty()) {
                    emptySet()
                } else {
                    registry.discoverPlugins()
                    missingInitially.filter { registry.getPluginByTypeId(it) == null }.toSet()
                }
            }
        }
    }
}
