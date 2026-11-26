package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe

/**
 * Dependency resolution utility for backup export and import.
 *
 * Automatically inspects selected workflows to discover referenced credential IDs
 * and precondition IDs, ensuring that exporting or importing individual workflows
 * retains all required dependencies.
 */
object BackupDependencyResolver {

    /**
     * Finds all precondition IDs referenced by nodes within the given workflows.
     */
    fun findRelatedPreconditionIds(
        workflows: Collection<Workflow>,
    ): Set<String> =
        workflows.flatMap { it.nodes }
            .flatMap { it.preconditions }
            .mapNotNull { it.id.takeIf(String::isNotBlank) }
            .toSet()

    /**
     * Finds all credential IDs referenced in node configurations of the given workflows,
     * as well as credentials referenced by attached preconditions.
     */
    fun findRelatedCredentialIds(
        workflows: Collection<Workflow>,
        availablePreconditions: Collection<PreconditionEntity> = emptyList(),
    ): Set<String> {
        val precIdSet = findRelatedPreconditionIds(workflows)

        val nodeCredentialIds = workflows.flatMap { it.nodes }
            .mapNotNull { node ->
                val credId = parseJsonObjectSafe(node.configJson).optString("credentialId")
                credId.takeIf(String::isNotBlank)
            }

        val precCredentialIds = availablePreconditions
            .filter { it.id in precIdSet && it.credentialId.isNotBlank() }
            .map { it.credentialId }

        return (nodeCredentialIds + precCredentialIds).toSet()
    }

    /**
     * Resolves the full set of precondition IDs and credential IDs required by the selected workflows.
     */
    fun resolveWorkflowDependencies(
        selectedWorkflowIds: Set<String>,
        availableWorkflows: Collection<Workflow>,
        availablePreconditions: Collection<PreconditionEntity>,
    ): WorkflowDependencies {
        val selectedWfs = availableWorkflows.filter { it.id in selectedWorkflowIds }
        val relatedPrecIds = findRelatedPreconditionIds(selectedWfs)
        val relatedCredIds = findRelatedCredentialIds(selectedWfs, availablePreconditions)
        return WorkflowDependencies(
            preconditionIds = relatedPrecIds,
            credentialIds = relatedCredIds,
        )
    }
}

data class WorkflowDependencies(
    val preconditionIds: Set<String>,
    val credentialIds: Set<String>,
)
