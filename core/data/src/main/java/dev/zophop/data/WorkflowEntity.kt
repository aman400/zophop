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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject

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
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun fromDomain(workflow: Workflow): WorkflowEntity {
            val encryptedNodes =
                workflow.nodes.map { node ->
                    node.copy(configJson = DatabaseEncryption.encryptNodeConfig(node.configJson))
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
                updatedAt = System.currentTimeMillis(),
            )
        }
    }
}

fun WorkflowEntity.toDomain(): Workflow {
    var nodesList: List<WorkflowNode> = emptyList()
    var connectionsList: List<WorkflowConnection> = emptyList()

    if (graphJson.isNotBlank()) {
        try {
            val graph = AppJson.decodeFromString<WorkflowGraph>(graphJson)
            nodesList =
                graph.nodes.map { node ->
                    node.copy(configJson = DatabaseEncryption.decryptNodeConfig(node.configJson))
                }
            connectionsList = graph.connections
        } catch (_: Exception) {
            val legacy = parseLegacyGraph(graphJson)
            nodesList = legacy.first
            connectionsList = legacy.second
        }
    }

    return Workflow(
        id = id,
        name = name,
        description = description,
        isEnabled = isEnabled,
        icon = icon,
        nodes = nodesList,
        connections = connectionsList,
    )
}

private fun parseLegacyGraph(graphJson: String): Pair<List<WorkflowNode>, List<WorkflowConnection>> {
    val nodesList = mutableListOf<WorkflowNode>()
    val connectionsList = mutableListOf<WorkflowConnection>()
    try {
        val json = JSONObject(graphJson)
        val nodesArr = json.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until nodesArr.length()) {
            val nodeObj = nodesArr.getJSONObject(i)
            nodesList.add(parseWorkflowNode(nodeObj))
        }

        val connArr = json.optJSONArray("connections") ?: JSONArray()
        for (i in 0 until connArr.length()) {
            val connObj = connArr.getJSONObject(i)
            connectionsList.add(
                WorkflowConnection(
                    fromNodeId = connObj.getString("fromNodeId"),
                    toNodeId = connObj.getString("toNodeId"),
                    outputPort = connObj.optString("outputPort", "main"),
                ),
            )
        }
    } catch (_: Exception) {
    }
    return Pair(nodesList, connectionsList)
}

private fun parseWorkflowNode(nodeObj: JSONObject): WorkflowNode {
    val id = nodeObj.getString("id")
    val name = nodeObj.getString("name")
    val typeStr = nodeObj.getString("type")
    val isEnabled = nodeObj.optBoolean("isEnabled", true)
    val configJson = DatabaseEncryption.decryptNodeConfig(nodeObj.optString("configJson", "{}"))

    val preconditions = mutableListOf<Precondition>()
    val preArr = nodeObj.optJSONArray("preconditions") ?: JSONArray()
    for (j in 0 until preArr.length()) {
        val pObj = preArr.getJSONObject(j)
        preconditions.add(parsePrecondition(pObj))
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

private fun parsePrecondition(pObj: JSONObject): Precondition {
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
                targetUrl = pObj.optString("targetUrl", ""),
                method = pObj.optString("method", "GET"),
                headers = pObj.optString("headers", ""),
                expectedStatus = pObj.optInt("expectedStatus", 200),
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
                requiredCarrier = pObj.optString("requiredCarrier", ""),
                requiredSlot = pObj.optInt("requiredSlot", -1),
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
                requireGpsEnabled = pObj.optBoolean("requireGpsEnabled", true),
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
                minBatteryPercent = pObj.optInt("minBatteryPercent", 15),
                requireCharging = pObj.optBoolean("requireCharging", false),
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
                pName = pObj.optString("name", ""),
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
    pObj: JSONObject,
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
            val pJson = JSONObject(pJsonStr)
            val chainNodesArr = pJson.optJSONArray("nodes") ?: JSONArray()
            for (k in 0 until chainNodesArr.length()) {
                val nObj = chainNodesArr.getJSONObject(k)
                chainNodesList.add(
                    WorkflowNode(
                        id = nObj.getString("id"),
                        name = nObj.getString("name"),
                        type = nObj.getString("type"),
                        isEnabled = nObj.optBoolean("isEnabled", true),
                        configJson = DatabaseEncryption.decryptNodeConfig(nObj.optString("configJson", "{}")),
                    ),
                )
            }
            val connArr = pJson.optJSONArray("connections") ?: JSONArray()
            for (k in 0 until connArr.length()) {
                val cObj = connArr.getJSONObject(k)
                chainConnectionsList.add(
                    WorkflowConnection(
                        fromNodeId = cObj.getString("fromNodeId"),
                        toNodeId = cObj.getString("toNodeId"),
                        outputPort = cObj.optString("outputPort", "main"),
                    ),
                )
            }
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
