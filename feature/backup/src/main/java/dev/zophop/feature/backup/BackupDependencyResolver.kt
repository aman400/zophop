package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowGraph
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optJsonArray
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import kotlinx.serialization.json.JsonObject

/**
 * Dependency resolution utility for backup export and import.
 *
 * Automatically inspects selected workflows to discover referenced credential IDs
 * and precondition IDs, ensuring that exporting or importing individual workflows
 * retains all required dependencies.
 */
object BackupDependencyResolver {

    /**
     * Finds all precondition IDs referenced by nodes within the given workflows,
     * including any nested preconditions inside [Precondition.ChainCheck].
     */
    fun findRelatedPreconditionIds(
        workflows: Collection<Workflow>,
    ): Set<String> =
        workflows.flatMap { collectNodePreconditionIds(it.nodes) }.toSet()

    /**
     * Finds all credential IDs referenced in node configurations of the given workflows
     * (including inline [Precondition.ChainCheck] nodes), as well as credentials
     * referenced by attached preconditions and their chained pipeline graphs.
     */
    fun findRelatedCredentialIds(
        workflows: Collection<Workflow>,
        availablePreconditions: Collection<PreconditionEntity> = emptyList(),
    ): Set<String> {
        val precIdSet = findRelatedPreconditionIds(workflows)
        val nodeCredentialIds = workflows.flatMap { collectNodeCredentialIds(it.nodes) }
        val matchedPreconditions = availablePreconditions.filter { it.id in precIdSet }
        val precCredentialIds =
            matchedPreconditions.mapNotNull { it.credentialId.takeIf(String::isNotBlank) }
        val pipelineCredentialIds =
            matchedPreconditions.flatMap { entity ->
                collectNodeCredentialIds(extractPipelineNodes(entity.pipelineJson))
            }

        return (nodeCredentialIds + precCredentialIds + pipelineCredentialIds).toSet()
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

    fun findAllNodeTypes(
        workflows: Collection<Workflow>,
        preconditions: Collection<PreconditionEntity> = emptyList(),
    ): Set<String> =
        buildSet {
            workflows.forEach { wf -> addAll(collectNodeTypes(wf.nodes)) }
            preconditions.forEach { entity ->
                addAll(collectNodeTypes(extractPipelineNodes(entity.pipelineJson)))
            }
        }

    private fun collectNodeTypes(nodes: Collection<WorkflowNode>): Set<String> =
        buildSet {
            nodes.forEach { node ->
                if (node.type.isNotBlank()) add(node.type)
                node.preconditions.forEach { prec ->
                    if (prec is Precondition.ChainCheck) {
                        addAll(collectNodeTypes(prec.nodes))
                    }
                }
            }
        }

    private fun collectNodePreconditionIds(nodes: Collection<WorkflowNode>): Set<String> =
        buildSet {
            nodes.forEach { node ->
                node.preconditions.forEach { prec ->
                    if (prec.id.isNotBlank()) add(prec.id)
                    if (prec is Precondition.ChainCheck) {
                        addAll(collectNodePreconditionIds(prec.nodes))
                    }
                }
            }
        }

    private fun collectNodeCredentialIds(nodes: Collection<WorkflowNode>): Set<String> =
        buildSet {
            nodes.forEach { node ->
                val credId = parseJsonObjectSafe(node.configJson).optString("credentialId")
                if (credId.isNotBlank()) add(credId)
                node.preconditions.forEach { prec ->
                    if (prec is Precondition.ChainCheck) {
                        addAll(collectNodeCredentialIds(prec.nodes))
                    }
                }
            }
        }

    private fun extractPipelineNodes(pipelineJson: String): List<WorkflowNode> =
        if (pipelineJson.isBlank()) {
            emptyList()
        } else {
            try {
                AppJson.decodeFromString<WorkflowGraph>(pipelineJson).nodes
            } catch (_: Exception) {
                parseLegacyPipelineNodes(pipelineJson)
            }
        }

    private fun parseLegacyPipelineNodes(pipelineJson: String): List<WorkflowNode> {
        val nodesArr = parseJsonObjectSafe(pipelineJson).optJsonArray("nodes") ?: return emptyList()
        return nodesArr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            WorkflowNode(
                id = obj.optString("id"),
                name = obj.optString("name"),
                type = obj.optString("type"),
                configJson = obj.optString("configJson", "{}"),
            )
        }
    }
}

data class WorkflowDependencies(
    val preconditionIds: Set<String>,
    val credentialIds: Set<String>,
)
