package dev.zophop.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val graphJson: String = "{}",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun fromDomain(workflow: Workflow): WorkflowEntity {
            val json = JSONObject()

            val nodesArr = JSONArray()
            for (node in workflow.nodes) {
                nodesArr.put(serializeNode(node))
            }

            val connArr = JSONArray()
            for (conn in workflow.connections) {
                val connObj =
                    JSONObject().apply {
                        put("fromNodeId", conn.fromNodeId)
                        put("toNodeId", conn.toNodeId)
                        put("outputPort", conn.outputPort)
                        put("inputPort", conn.inputPort)
                    }
                connArr.put(connObj)
            }

            json.put("nodes", nodesArr)
            json.put("connections", connArr)

            return WorkflowEntity(
                id = workflow.id,
                name = workflow.name,
                description = workflow.description,
                isEnabled = workflow.isEnabled,
                graphJson = json.toString(),
                updatedAt = System.currentTimeMillis(),
            )
        }

        private fun serializeNode(node: WorkflowNode): JSONObject =
            JSONObject().apply {
                put("id", node.id)
                put("name", node.name)
                put("type", node.type)
                put("isEnabled", node.isEnabled)
                put("configJson", node.configJson)

                val preArr = JSONArray()
                for (prec in node.preconditions) {
                    preArr.put(serializePrecondition(prec))
                }
                put("preconditions", preArr)
            }

        private fun serializePrecondition(prec: Precondition): JSONObject =
            JSONObject().apply {
                put("id", prec.id)
                put("type", prec.type.name)
                put("onFailureAction", prec.onFailureAction)
                put("maxRetries", prec.maxRetries)
                put("retryIntervalSeconds", prec.retryIntervalSeconds)
                put("showNotificationOnFailure", prec.showNotificationOnFailure)
                when (prec) {
                    is Precondition.InternetCheck -> put("isEnabled", prec.isEnabled)
                    is Precondition.VpnCheck -> put("isEnabled", prec.isEnabled)
                    is Precondition.HealthCheck -> {
                        put("isEnabled", prec.isEnabled)
                        put("targetUrl", prec.targetUrl)
                        put("method", prec.method)
                        put("headers", prec.headers)
                        put("expectedStatus", prec.expectedStatus)
                    }
                    is Precondition.SimCheck -> {
                        put("isEnabled", prec.isEnabled)
                        put("requiredCarrier", prec.requiredCarrier)
                        put("requiredSlot", prec.requiredSlot)
                    }
                    is Precondition.GpsCheck -> {
                        put("isEnabled", prec.isEnabled)
                        put("requireGpsEnabled", prec.requireGpsEnabled)
                    }
                    is Precondition.BatteryCheck -> {
                        put("isEnabled", prec.isEnabled)
                        put("minBatteryPercent", prec.minBatteryPercent)
                        put("requireCharging", prec.requireCharging)
                    }
                    is Precondition.ChainCheck -> {
                        put("isEnabled", prec.isEnabled)
                        put("pipelineJson", serializeChainCheck(prec))
                    }
                }
            }

        private fun serializeChainCheck(prec: Precondition.ChainCheck): String {
            val chainNodesArr = JSONArray()
            prec.nodes.forEach { cNode ->
                chainNodesArr.put(
                    JSONObject().apply {
                        put("id", cNode.id)
                        put("name", cNode.name)
                        put("type", cNode.type)
                        put("isEnabled", cNode.isEnabled)
                        put("configJson", cNode.configJson)
                    },
                )
            }
            val chainConnArr = JSONArray()
            prec.connections.forEach { cConn ->
                chainConnArr.put(
                    JSONObject().apply {
                        put("fromNodeId", cConn.fromNodeId)
                        put("toNodeId", cConn.toNodeId)
                        put("outputPort", cConn.outputPort)
                        put("inputPort", cConn.inputPort)
                    },
                )
            }
            return JSONObject().apply {
                put("nodes", chainNodesArr)
                put("connections", chainConnArr)
            }.toString()
        }
    }
}

fun WorkflowEntity.toDomain(): Workflow {
    val nodesList = mutableListOf<WorkflowNode>()
    val connectionsList = mutableListOf<WorkflowConnection>()

    if (graphJson.isNotBlank()) {
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
    }

    return Workflow(
        id = id,
        name = name,
        description = description,
        isEnabled = isEnabled,
        nodes = nodesList,
        connections = connectionsList,
    )
}

private fun parseWorkflowNode(nodeObj: JSONObject): WorkflowNode {
    val id = nodeObj.getString("id")
    val name = nodeObj.getString("name")
    val typeStr = nodeObj.getString("type")
    val isEnabled = nodeObj.optBoolean("isEnabled", true)
    val configJson = nodeObj.optString("configJson", "{}")

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
    val pTypeStr = pObj.getString("type")
    val isPEnabled = pObj.optBoolean("isEnabled", true)
    val pFailureAction = pObj.optString("onFailureAction", "FAIL_IMMEDIATELY")
    val pMaxRetries = pObj.optInt("maxRetries", 3)
    val pRetryInterval = pObj.optInt("retryIntervalSeconds", 30)
    val pShowNotif = pObj.optBoolean("showNotificationOnFailure", false)

    return when (PreconditionType.valueOf(pTypeStr)) {
        PreconditionType.INTERNET_CHECK ->
            Precondition.InternetCheck(
                id = pId,
                isEnabled = isPEnabled,
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.VPN_CHECK ->
            Precondition.VpnCheck(
                id = pId,
                isEnabled = isPEnabled,
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.HEALTH_CHECK ->
            Precondition.HealthCheck(
                id = pId,
                isEnabled = isPEnabled,
                targetUrl = pObj.optString("targetUrl", ""),
                method = pObj.optString("method", "GET"),
                headers = pObj.optString("headers", ""),
                expectedStatus = pObj.optInt("expectedStatus", 200),
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.SIM_CHECK ->
            Precondition.SimCheck(
                id = pId,
                isEnabled = isPEnabled,
                requiredCarrier = pObj.optString("requiredCarrier", ""),
                requiredSlot = pObj.optInt("requiredSlot", -1),
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.GPS_CHECK ->
            Precondition.GpsCheck(
                id = pId,
                isEnabled = isPEnabled,
                requireGpsEnabled = pObj.optBoolean("requireGpsEnabled", true),
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.BATTERY_CHECK ->
            Precondition.BatteryCheck(
                id = pId,
                isEnabled = isPEnabled,
                minBatteryPercent = pObj.optInt("minBatteryPercent", 15),
                requireCharging = pObj.optBoolean("requireCharging", false),
                onFailureAction = pFailureAction,
                maxRetries = pMaxRetries,
                retryIntervalSeconds = pRetryInterval,
                showNotificationOnFailure = pShowNotif,
            )
        PreconditionType.CHAINED_CHECK ->
            parseChainedPrecondition(
                pObj = pObj,
                pId = pId,
                isPEnabled = isPEnabled,
                pFailureAction = pFailureAction,
                pMaxRetries = pMaxRetries,
                pRetryInterval = pRetryInterval,
                pShowNotif = pShowNotif,
            )
    }
}

private fun parseChainedPrecondition(
    pObj: JSONObject,
    pId: String,
    isPEnabled: Boolean,
    pFailureAction: String,
    pMaxRetries: Int,
    pRetryInterval: Int,
    pShowNotif: Boolean,
): Precondition.ChainCheck {
    val chainNodesList = mutableListOf<WorkflowNode>()
    val chainConnectionsList = mutableListOf<WorkflowConnection>()
    val pJsonStr = pObj.optString("pipelineJson", "")
    if (pJsonStr.isNotBlank()) {
        try {
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
                        configJson = nObj.optString("configJson", "{}"),
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
        } catch (_: Exception) {
        }
    }
    return Precondition.ChainCheck(
        id = pId,
        isEnabled = isPEnabled,
        nodes = chainNodesList,
        connections = chainConnectionsList,
        onFailureAction = pFailureAction,
        maxRetries = pMaxRetries,
        retryIntervalSeconds = pRetryInterval,
        showNotificationOnFailure = pShowNotif,
    )
}
