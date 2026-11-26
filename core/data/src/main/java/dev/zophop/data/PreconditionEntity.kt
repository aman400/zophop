package dev.zophop.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowGraph
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optJsonArray
import dev.zophop.domain.model.optJsonObject
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "preconditions")
data class PreconditionEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val type: String, // "INTERNET_CHECK", "VPN_CHECK", "HEALTH_CHECK", "SIM_CHECK", "GPS_CHECK", "CHAINED_CHECK"
    val isEnabled: Boolean = true,
    val icon: String = "",
    val targetUrl: String = "",
    val method: String = "GET",
    val headers: String = "",
    val expectedStatus: Int = 200,
    val requiredCarrier: String = "",
    val requiredPermission: String = "",
    val credentialId: String = "",
    val pipelineJson: String = "",
    val minBatteryPercent: Int = 15,
    val requireCharging: Boolean = false,
    val onFailureAction: String = "FAIL_IMMEDIATELY", // "FAIL_IMMEDIATELY", "RETRY", or "QUEUE"
    val maxRetries: Int = 3,
    val retryIntervalSeconds: Int = 30,
    val retryStrategy: String = "FIXED", // "FIXED", "EXPONENTIAL", or "RANDOMIZED"
    val queueTimeoutMinutes: Int = 10,
    @Deprecated("Unused. Only workflow execution notifications are used.")
    val showNotificationOnFailure: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

fun PreconditionEntity.toDomain(): Precondition =
    when (type) {
        PreconditionType.INTERNET_CHECK.name ->
            Precondition.InternetCheck(
                id = id,
                isEnabled = isEnabled,
                icon = icon,
                onFailureAction = onFailureAction,
                maxRetries = maxRetries,
                retryIntervalSeconds = retryIntervalSeconds,
                retryStrategy = retryStrategy,
                queueTimeoutMinutes = queueTimeoutMinutes,
            )
        PreconditionType.VPN_CHECK.name ->
            Precondition.VpnCheck(
                id = id,
                isEnabled = isEnabled,
                icon = icon,
                onFailureAction = onFailureAction,
                maxRetries = maxRetries,
                retryIntervalSeconds = retryIntervalSeconds,
                retryStrategy = retryStrategy,
                queueTimeoutMinutes = queueTimeoutMinutes,
            )
        PreconditionType.HEALTH_CHECK.name ->
            Precondition.HealthCheck(
                id = id,
                isEnabled = isEnabled,
                icon = icon,
                targetUrl = targetUrl,
                method = method,
                headers = headers,
                expectedStatus = expectedStatus,
                onFailureAction = onFailureAction,
                maxRetries = maxRetries,
                retryIntervalSeconds = retryIntervalSeconds,
                retryStrategy = retryStrategy,
                queueTimeoutMinutes = queueTimeoutMinutes,
            )
        PreconditionType.SIM_CHECK.name ->
            Precondition.SimCheck(
                id = id,
                isEnabled = isEnabled,
                icon = icon,
                requiredCarrier = requiredCarrier,
                onFailureAction = onFailureAction,
                maxRetries = maxRetries,
                retryIntervalSeconds = retryIntervalSeconds,
                retryStrategy = retryStrategy,
                queueTimeoutMinutes = queueTimeoutMinutes,
            )
        PreconditionType.GPS_CHECK.name ->
            Precondition.GpsCheck(
                id = id,
                isEnabled = isEnabled,
                icon = icon,
                requireGpsEnabled = true,
                onFailureAction = onFailureAction,
                maxRetries = maxRetries,
                retryIntervalSeconds = retryIntervalSeconds,
                retryStrategy = retryStrategy,
                queueTimeoutMinutes = queueTimeoutMinutes,
            )
        PreconditionType.BATTERY_CHECK.name ->
            Precondition.BatteryCheck(
                id = id,
                isEnabled = isEnabled,
                icon = icon,
                minBatteryPercent = minBatteryPercent,
                requireCharging = requireCharging,
                onFailureAction = onFailureAction,
                maxRetries = maxRetries,
                retryIntervalSeconds = retryIntervalSeconds,
                retryStrategy = retryStrategy,
                queueTimeoutMinutes = queueTimeoutMinutes,
            )
        PreconditionType.CHAINED_CHECK.name -> {
            var nodesList: List<WorkflowNode> = emptyList()
            var connectionsList: List<WorkflowConnection> = emptyList()
            if (pipelineJson.isNotBlank()) {
                try {
                    val graph = AppJson.decodeFromString<WorkflowGraph>(pipelineJson)
                    nodesList = graph.nodes.map { node ->
                        node.copy(configJson = DatabaseEncryption.decryptNodeConfig(node.configJson))
                    }
                    connectionsList = graph.connections
                } catch (_: Exception) {
                    val legacy = parseLegacyPipeline(pipelineJson)
                    nodesList = legacy.first
                    connectionsList = legacy.second
                }
            }
            Precondition.ChainCheck(
                id = id,
                name = name,
                icon = icon,
                isEnabled = isEnabled,
                nodes = nodesList,
                connections = connectionsList,
                onFailureAction = onFailureAction,
                maxRetries = maxRetries,
                retryIntervalSeconds = retryIntervalSeconds,
                retryStrategy = retryStrategy,
                queueTimeoutMinutes = queueTimeoutMinutes,
            )
        }
        else ->
            Precondition.InternetCheck(
                id = id,
                isEnabled = isEnabled,
                icon = icon,
                onFailureAction = onFailureAction,
                maxRetries = maxRetries,
                retryIntervalSeconds = retryIntervalSeconds,
                retryStrategy = retryStrategy,
                queueTimeoutMinutes = queueTimeoutMinutes,
            )
    }

private fun parseLegacyPipeline(pipelineJson: String): Pair<List<WorkflowNode>, List<WorkflowConnection>> {
    val nodesList = mutableListOf<WorkflowNode>()
    val connectionsList = mutableListOf<WorkflowConnection>()
    try {
        val json = parseJsonObjectSafe(pipelineJson)
        val nodesArr = json.optJsonArray("nodes")
        if (nodesArr != null) {
            for (i in nodesArr.indices) {
                val nodeObj = nodesArr.optJsonObject(i) ?: continue
                nodesList.add(
                    WorkflowNode(
                        id = nodeObj.optString("id"),
                        name = nodeObj.optString("name"),
                        type = nodeObj.optString("type"),
                        isEnabled = nodeObj.optBoolean("isEnabled", true),
                        configJson = DatabaseEncryption.decryptNodeConfig(nodeObj.optString("configJson", "{}")),
                    ),
                )
            }
        }
        val connArr = json.optJsonArray("connections")
        if (connArr != null) {
            for (i in connArr.indices) {
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

fun PreconditionEntity.hasNodeType(vararg targetTypes: String): Boolean {
    if (targetTypes.any { it.equals(type, ignoreCase = true) }) return true
    if (pipelineJson.isBlank()) return false
    return try {
        val graph = AppJson.decodeFromString<WorkflowGraph>(pipelineJson)
        graph.nodes.any { node -> targetTypes.any { it.equals(node.type, ignoreCase = true) } }
    } catch (_: Exception) {
        val legacy = parseLegacyPipeline(pipelineJson)
        legacy.first.any { node -> targetTypes.any { it.equals(node.type, ignoreCase = true) } }
    }
}

fun PreconditionEntity.getStepCount(): Int {
    if (pipelineJson.isBlank()) return 1
    return try {
        val graph = AppJson.decodeFromString<WorkflowGraph>(pipelineJson)
        graph.nodes.size.coerceAtLeast(1)
    } catch (_: Exception) {
        val legacy = parseLegacyPipeline(pipelineJson)
        legacy.first.size.coerceAtLeast(1)
    }
}
