package dev.zophop.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowGraph
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optInt
import dev.zophop.domain.model.optJsonArray
import dev.zophop.domain.model.optJsonObject
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

@Serializable
@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val icon: String = "",
    val graphJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun fromDomain(workflow: Workflow): WorkflowEntity {
            val encryptedNodes =
                workflow.nodes.map { node ->
                    node.copy(
                        configJson = DatabaseEncryption.encryptNodeConfig(node.configJson),
                    )
                }

            val graph =
                WorkflowGraph(
                    nodes = encryptedNodes,
                    connections = workflow.connections,
                )

            return WorkflowEntity(
                id = workflow.id,
                name = workflow.name,
                description = workflow.description,
                isEnabled = workflow.isEnabled,
                icon = workflow.icon,
                graphJson = AppJson.encodeToString(graph),
                createdAt = workflow.createdAt,
                updatedAt = workflow.updatedAt,
            )
        }
    }

    fun toDomain(): Workflow {
        val (nodes, connections) =
            try {
                val graph = AppJson.decodeFromString<WorkflowGraph>(graphJson)
                val decryptedNodes =
                    graph.nodes.map { node ->
                        node.copy(
                            configJson = DatabaseEncryption.decryptNodeConfig(node.configJson),
                        )
                    }
                Pair(decryptedNodes, graph.connections)
            } catch (_: Exception) {
                parseLegacyGraph(graphJson)
            }

        return Workflow(
            id = id,
            name = name,
            description = description,
            isEnabled = isEnabled,
            icon = icon,
            nodes = nodes,
            connections = connections,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}

private fun parseLegacyGraph(graphJson: String): Pair<List<WorkflowNode>, List<WorkflowConnection>> {
    val nodesList = mutableListOf<WorkflowNode>()
    val connectionsList = mutableListOf<WorkflowConnection>()
    try {
        val json = parseJsonObjectSafe(graphJson)
        val nodesArr = json.optJsonArray("nodes")
        if (nodesArr != null) {
            for (i in 0 until nodesArr.size) {
                val nodeObj = nodesArr.optJsonObject(i) ?: continue
                nodesList.add(parseWorkflowNode(nodeObj))
            }
        }

        val connArr = json.optJsonArray("connections")
        if (connArr != null) {
            for (i in 0 until connArr.size) {
                val connObj = connArr.optJsonObject(i) ?: continue
                connectionsList.add(
                    WorkflowConnection(
                        fromNodeId = connObj.optString("fromNodeId"),
                        toNodeId = connObj.optString("toNodeId"),
                        outputPort = connObj.optString("outputPort", "main"),
                        inputPort = connObj.optString("inputPort", "main"),
                    ),
                )
            }
        }
    } catch (_: Exception) {
    }
    return Pair(nodesList, connectionsList)
}

private fun parseWorkflowNode(nodeObj: JsonObject): WorkflowNode {
    val id = nodeObj.optString("id")
    val name = nodeObj.optString("name")
    val typeStr = nodeObj.optString("type")
    val isEnabled = nodeObj.optBoolean("isEnabled", true)
    val configJson = DatabaseEncryption.decryptNodeConfig(nodeObj.optString("configJson", "{}"))

    val preconditions = mutableListOf<Precondition>()
    val preArr = nodeObj.optJsonArray("preconditions")
    if (preArr != null) {
        for (j in 0 until preArr.size) {
            val pObj = preArr.optJsonObject(j) ?: continue
            preconditions.add(parsePrecondition(pObj))
        }
    }

    return WorkflowNode(
        id = id,
        name = name,
        type = typeStr,
        isEnabled = isEnabled,
        configJson = configJson,
        preconditions = preconditions,
    )
}

private fun parsePrecondition(pObj: JsonObject): Precondition {
    val pId = pObj.optString("id", "")
    val pTypeStr = pObj.optString("type", "")
    val pIcon = pObj.optString("icon", "")
    val isPEnabled = pObj.optBoolean("isEnabled", true)
    val pFailureAction = pObj.optString("onFailureAction", "FAIL_IMMEDIATELY")
    val pMaxRetries = pObj.optInt("maxRetries", 3)
    val pRetryInterval = pObj.optInt("retryIntervalSeconds", 30)
    val pRetryStrategy = pObj.optString("retryStrategy", "FIXED")
    val pQueueTimeout = pObj.optInt("queueTimeoutMinutes", 10)
    val pShowNotif = pObj.optBoolean("showNotificationOnFailure", false)

    val precType =
        try {
            PreconditionType.valueOf(pTypeStr.uppercase().trim())
        } catch (_: IllegalArgumentException) {
            PreconditionType.INTERNET_CHECK
        }

    return when (precType) {
        PreconditionType.INTERNET_CHECK ->
            Precondition.InternetCheck(
                id = pId,
                isEnabled = isPEnabled,
                icon = pIcon,
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.VPN_CHECK ->
            Precondition.VpnCheck(
                id = pId,
                isEnabled = isPEnabled,
                icon = pIcon,
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.HEALTH_CHECK ->
            Precondition.HealthCheck(
                id = pId,
                isEnabled = isPEnabled,
                icon = pIcon,
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.SIM_CHECK ->
            Precondition.SimCheck(
                id = pId,
                isEnabled = isPEnabled,
                icon = pIcon,
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.GPS_CHECK ->
            Precondition.GpsCheck(
                id = pId,
                isEnabled = isPEnabled,
                icon = pIcon,
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.BATTERY_CHECK ->
            Precondition.BatteryCheck(
                id = pId,
                isEnabled = isPEnabled,
                icon = pIcon,
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.CHAINED_CHECK ->
            parseChainedPrecondition(
                pObj = pObj,
                pId = pId,
                pIcon = pIcon,
                isPEnabled = isPEnabled,
                pFailureAction = pFailureAction,
                pMaxRetries = pMaxRetries,
                pRetryInterval = pRetryInterval,
                pRetryStrategy = pRetryStrategy,
                pQueueTimeout = pQueueTimeout,
                pShowNotif = pShowNotif,
            )
    }
}

private fun parseChainedPrecondition(
    pObj: JsonObject,
    pId: String,
    pName: String = "",
    pIcon: String = "",
    isPEnabled: Boolean,
    pFailureAction: String,
    pMaxRetries: Int,
    pRetryInterval: Int,
    pRetryStrategy: String = "FIXED",
    pQueueTimeout: Int = 10,
    pShowNotif: Boolean,
): Precondition.ChainCheck {
    val chainNodesList = mutableListOf<WorkflowNode>()
    val chainConnectionsList = mutableListOf<WorkflowConnection>()
    val pJsonStr = pObj.optString("pipelineJson", "")
    if (pJsonStr.isNotBlank()) {
        try {
            val graph = AppJson.decodeFromString<WorkflowGraph>(pJsonStr)
            chainNodesList.addAll(
                graph.nodes.map { n ->
                    n.copy(configJson = DatabaseEncryption.decryptNodeConfig(n.configJson))
                },
            )
            chainConnectionsList.addAll(graph.connections)
        } catch (_: Exception) {
            parseLegacyChainedPipeline(pJsonStr, chainNodesList, chainConnectionsList)
        }
    }
    return Precondition.ChainCheck(
        id = pId,
        name = pName,
        icon = pIcon,
        isEnabled = isPEnabled,
        nodes = chainNodesList,
        connections = chainConnectionsList,
        onFailureAction = pFailureAction,
        maxRetries = pMaxRetries,
        retryIntervalSeconds = pRetryInterval,
        retryStrategy = pRetryStrategy,
        queueTimeoutMinutes = pQueueTimeout,
        showNotificationOnFailure = pShowNotif,
    )
}

private fun parseLegacyChainedPipeline(
    pJsonStr: String,
    nodes: MutableList<WorkflowNode>,
    conns: MutableList<WorkflowConnection>,
) {
    val pJson = parseJsonObjectSafe(pJsonStr)
    val chainNodesArr = pJson.optJsonArray("nodes")
    if (chainNodesArr != null) {
        for (k in chainNodesArr.indices) {
            val nObj = chainNodesArr.optJsonObject(k) ?: continue
            nodes.add(
                WorkflowNode(
                    id = nObj.optString("id"),
                    name = nObj.optString("name"),
                    type = nObj.optString("type"),
                    isEnabled = nObj.optBoolean("isEnabled", true),
                    configJson = DatabaseEncryption.decryptNodeConfig(nObj.optString("configJson", "{}")),
                ),
            )
        }
    }
    val connArr = pJson.optJsonArray("connections")
    if (connArr != null) {
        for (k in connArr.indices) {
            val cObj = connArr.optJsonObject(k) ?: continue
            conns.add(
                WorkflowConnection(
                    fromNodeId = cObj.optString("fromNodeId"),
                    toNodeId = cObj.optString("toNodeId"),
                    outputPort = cObj.optString("outputPort", "main"),
                    inputPort = cObj.optString("inputPort", "main"),
                ),
            )
        }
    }
}
