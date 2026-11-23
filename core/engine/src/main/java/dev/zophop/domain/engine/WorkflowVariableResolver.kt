package dev.zophop.domain.engine

import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.NodePluginManifest
import org.json.JSONException
import org.json.JSONObject

object WorkflowVariableResolver {
    /**
     * Resolves all upstream nodes that feed into [targetNodeId] in the workflow.
     * Traverses backwards along [connections]. If [connections] is empty or does
     * not connect to [targetNodeId], falls back to sequential nodes preceding [targetNodeId].
     */
    fun getUpstreamNodes(
        targetNodeId: String,
        nodes: List<WorkflowNode>,
        connections: List<WorkflowConnection> = emptyList(),
    ): List<WorkflowNode> {
        val targetIndex = nodes.indexOfFirst { it.id == targetNodeId }
        if (targetIndex < 0) return emptyList()

        val incomingConnections = connections.filter { it.toNodeId == targetNodeId }
        return if (incomingConnections.isNotEmpty()) {
            val visitedIds = traverseIncomingConnections(incomingConnections, connections)
            nodes.filter { visitedIds.contains(it.id) }
        } else if (targetIndex > 0) {
            nodes.subList(0, targetIndex)
        } else {
            emptyList()
        }
    }

    private fun traverseIncomingConnections(
        incomingConnections: List<WorkflowConnection>,
        connections: List<WorkflowConnection>,
    ): Set<String> {
        val visitedIds = linkedSetOf<String>()
        val queue = ArrayDeque<String>()

        fun enqueue(nodeId: String) {
            if (visitedIds.add(nodeId)) {
                queue.add(nodeId)
            }
        }
        incomingConnections.forEach { enqueue(it.fromNodeId) }
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            connections.filter { it.toNodeId == currentId }.forEach { enqueue(it.fromNodeId) }
        }
        return visitedIds
    }

    /**
     * Returns the contract-defined variables returned by this specific node.
     * Incorporates both static manifest declared [NodePluginManifest.outputVariables]
     * and dynamic configuration keys (such as `outputKey`).
     */
    fun getNodeOutputVariables(
        node: WorkflowNode,
        manifest: NodePluginManifest? = null,
    ): List<String> {
        val resolvedManifest = manifest ?: BuiltInPluginRegistry.getBuiltInManifestByTypeId(node.type)
        val result = linkedSetOf<String>()

        if (resolvedManifest != null) {
            result.addAll(resolvedManifest.getProducedVariables(node.configJson))
        } else {
            result.addAll(getFallbackVariablesForType(node.type))
            result.addAll(extractDynamicConfigVariables(node.configJson))
        }

        return result.toList()
    }

    private fun getFallbackVariablesForType(type: String): List<String> = when {
        type.contains("SMS_TRIGGER", ignoreCase = true) -> listOf("sender", "body", "header", "text")
        type.contains("CALL_TRIGGER", ignoreCase = true) -> listOf("caller_number", "sender", "header")
        type.contains("NOTIFICATION_TRIGGER", ignoreCase = true) ->
            listOf("package_name", "title", "text", "sender", "body")
        type.contains("MANUAL_TRIGGER", ignoreCase = true) -> listOf("body", "text", "timestamp")
        type.contains("CRON_TRIGGER", ignoreCase = true) -> listOf("body", "text", "sender", "timestamp")
        type.contains("PARSE_NOTIFICATION", ignoreCase = true) ||
            type.contains("REGEX_PARSER", ignoreCase = true) -> listOf("code", "otp")
        type.contains("JSON_PARSER", ignoreCase = true) -> listOf("json_output")
        type.contains("TEMPLATE_FORMATTER", ignoreCase = true) -> listOf("formatted_payload", "payload")
        type.contains("HTTP_ACTION", ignoreCase = true) -> listOf("http_response", "http_status")
        type.contains("CALL_ACTION", ignoreCase = true) -> listOf("call_status")
        type.contains("VPN_CHECK", ignoreCase = true) -> listOf("vpn_connected", "vpn_interface", "vpn_type")
        type.contains("GPS_CHECK", ignoreCase = true) ||
            type.contains("GET_LOCATION", ignoreCase = true) ->
            listOf("gps_enabled", "latitude", "longitude", "accuracy", "maps_url", "location_provider")
        type.contains("INTERNET_CHECK", ignoreCase = true) ->
            listOf("internet_connected", "network_type", "is_metered")
        type.contains("BLUETOOTH_CHECK", ignoreCase = true) ->
            listOf(
                "bluetooth_enabled",
                "bluetooth_connected",
                "connected_device_name",
                "connected_device_address",
                "bonded_device_count",
            )
        type.contains("SIM_CHECK", ignoreCase = true) ->
            listOf("sim_ready", "carrier_name", "sim_count", "is_roaming")
        type.contains("BATTERY_CHECK", ignoreCase = true) ->
            listOf("battery_percent", "is_charging", "charging_status")
        else -> emptyList()
    }

    private fun extractDynamicConfigVariables(configJson: String): List<String> {
        if (configJson.isBlank()) return emptyList()
        val dynamicVars = mutableListOf<String>()
        try {
            val json = JSONObject(configJson)
            val dynamicKey = json.optString("outputKey", "").trim()
            if (dynamicKey.isNotEmpty()) dynamicVars.add(dynamicKey)

            json.optJSONObject("mappings")?.keys()?.forEach {
                if (it.isNotBlank()) dynamicVars.add(it)
            }
            extractMappingsArray(json, dynamicVars)
            extractNamedRegexGroups(json.optString("regex", ""), dynamicVars)
        } catch (_: JSONException) {
        }
        return dynamicVars
    }

    private fun extractMappingsArray(json: JSONObject, result: MutableList<String>) {
        val mappingsArr = json.optJSONArray("mappings") ?: json.optJSONArray("extractions") ?: return
        for (i in 0 until mappingsArr.length()) {
            val item = mappingsArr.optJSONObject(i)
            val key = item?.optString("outputKey", item.optString("key", ""))?.trim()
            if (!key.isNullOrEmpty()) result.add(key)
        }
    }

    private fun extractNamedRegexGroups(regexStr: String, result: MutableList<String>) {
        if (regexStr.isBlank()) return
        val namedGroupRegex = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")
        namedGroupRegex.findAll(regexStr).forEach {
            val name = it.groupValues[1]
            if (name.isNotBlank()) result.add(name)
        }
    }

    /**
     * Resolves the variables available to [targetNodeId] by collecting only the variables
     * returned by its upstream nodes.
     */
    fun getAvailableUpstreamVariables(
        targetNodeId: String,
        nodes: List<WorkflowNode>,
        connections: List<WorkflowConnection> = emptyList(),
        manifests: Map<String, NodePluginManifest> = emptyMap(),
    ): List<String> {
        val upstreamNodes = getUpstreamNodes(targetNodeId, nodes, connections)
        val availableVars = linkedSetOf<String>()

        for (upstreamNode in upstreamNodes) {
            val manifest = manifests[upstreamNode.type] ?: BuiltInPluginRegistry.getBuiltInManifestByTypeId(upstreamNode.type)
            val nodeVars = getNodeOutputVariables(upstreamNode, manifest)
            availableVars.addAll(nodeVars)
        }

        return availableVars.toList()
    }
}
