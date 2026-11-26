package dev.zophop.feature.backup

import dev.zophop.data.DatabaseEncryption
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowGraph
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.generateShortWorkflowId
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optInt
import dev.zophop.domain.model.optJsonArray
import dev.zophop.domain.model.optJsonObject
import dev.zophop.domain.model.optLong
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonArraySafe
import dev.zophop.domain.model.parseJsonObjectSafe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.GeneralSecurityException
import java.util.UUID

object BackupSerializer {
    const val BACKUP_TYPE = "ZOPHOP_BACKUP"
    const val CURRENT_VERSION = 1
    const val VERIFICATION_SENTINEL = "ZOPHOP_SECRET_VERIFY_OK"

    /**
     * Creates a JSON export string of the selected workflows, credentials, and preconditions.
     * If [password] is provided and non-blank, sensitive keys will be encrypted using AES-256-GCM.
     */
    fun createBackupJson(
        workflows: List<Workflow> = emptyList(),
        credentials: List<Credential> = emptyList(),
        preconditions: List<PreconditionEntity> = emptyList(),
        password: String? = null,
        decryptLocalStorageValue: (String) -> String = { DatabaseEncryption.decrypt(it) },
    ): String {
        val hasPassword = !password.isNullOrBlank()
        val verificationToken =
            if (hasPassword) {
                PasswordBasedEncryption.encrypt(VERIFICATION_SENTINEL, password)
            } else {
                null
            }

        val processedWorkflows =
            workflows.map { wf ->
                wf.copy(
                    nodes =
                        wf.nodes.map { node ->
                            exportWorkflowNodeSecrets(node, password, decryptLocalStorageValue)
                        },
                )
            }

        val processedCredentials =
            credentials.map { cred ->
                val dataMap =
                    cred.dataMap.mapValues { (k, v) ->
                        val cleanVal = decryptLocalStorageValue(v)
                        if (!password.isNullOrBlank() && DatabaseEncryption.isSensitiveKey(k) && cleanVal.isNotBlank()) {
                            PasswordBasedEncryption.encrypt(cleanVal, password)
                        } else {
                            cleanVal
                        }
                    }
                cred.copy(dataMap = dataMap)
            }

        val processedPreconditions =
            preconditions.map { prec ->
                val cleanHeaders = decryptLocalStorageValue(prec.headers)
                val headers =
                    if (!password.isNullOrBlank() && cleanHeaders.isNotBlank()) {
                        PasswordBasedEncryption.encrypt(cleanHeaders, password)
                    } else {
                        cleanHeaders
                    }
                val pipeline = exportPipelineJsonSecrets(prec.pipelineJson, password, decryptLocalStorageValue)
                prec.copy(headers = headers, pipelineJson = pipeline)
            }

        val bundle =
            BackupBundle(
                version = CURRENT_VERSION,
                type = BACKUP_TYPE,
                exportedAt = System.currentTimeMillis(),
                isEncrypted = hasPassword,
                verificationToken = verificationToken,
                workflows = processedWorkflows,
                credentials = processedCredentials,
                preconditions = processedPreconditions,
            )

        return AppJson.encodeToString(bundle)
    }

    /**
     * Parses an import JSON string which may be:
     * 1. A full ZopHop backup bundle
     * 2. A single Workflow or array of Workflows
     * 3. A single Credential or array of Credentials
     * 4. A single PreconditionEntity or array of PreconditionEntities
     */
    fun parseImport(jsonString: String): ParsedBackupData {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) return ParsedBackupData()

        require(trimmed.startsWith("[") || trimmed.startsWith("{")) {
            "Invalid JSON format: expected object or array"
        }

        return if (trimmed.startsWith("[")) {
            parseJsonArray(parseJsonArraySafe(trimmed))
        } else {
            parseJsonObject(parseJsonObjectSafe(trimmed))
        }
    }

    private fun parseJsonObject(obj: JsonObject): ParsedBackupData {
        if (isBundleObject(obj)) {
            return parseBundle(obj)
        }
        if (isWorkflowObject(obj)) {
            val wf = parseWorkflow(obj)
            val hasEncrypted = workflowHasEncryptedSecrets(wf)
            return ParsedBackupData(
                workflows = listOf(wf),
                isEncrypted = hasEncrypted,
            )
        }
        if (isCredentialObject(obj)) {
            val cred = parseCredential(obj)
            val hasEncrypted = cred.dataMap.values.any { PasswordBasedEncryption.isEncryptedWithPassword(it) }
            return ParsedBackupData(
                credentials = listOf(cred),
                isEncrypted = hasEncrypted,
            )
        }
        if (isPreconditionObject(obj)) {
            val prec = parsePreconditionEntity(obj)
            val hasEncrypted = prec.headers.contains(PasswordBasedEncryption.PREFIX) ||
                prec.pipelineJson.contains(PasswordBasedEncryption.PREFIX)
            return ParsedBackupData(
                preconditions = listOf(prec),
                isEncrypted = hasEncrypted,
            )
        }

        throw IllegalArgumentException("Unrecognized JSON schema: does not match Workflows, Credentials, or Preconditions")
    }

    private fun isBundleObject(obj: JsonObject): Boolean =
        obj.optString("type") == BACKUP_TYPE ||
            (obj.containsKey("workflows") && (obj.containsKey("credentials") || obj.containsKey("preconditions")))

    private fun isWorkflowObject(obj: JsonObject): Boolean =
        obj.containsKey("nodes") || obj.containsKey("graphJson") || (obj.containsKey("name") && obj.containsKey("connections"))

    private fun isCredentialObject(obj: JsonObject): Boolean =
        obj.containsKey("type") && (obj.containsKey("data") || obj.containsKey("dataMap") || obj.containsKey("dataJson"))

    private fun isPreconditionObject(obj: JsonObject): Boolean =
        obj.containsKey("type") && (obj.containsKey("onFailureAction") || obj.containsKey("targetUrl") || obj.containsKey("minBatteryPercent"))

    private fun parseBundle(obj: JsonObject): ParsedBackupData {
        try {
            val bundle = AppJson.decodeFromString<BackupBundle>(obj.toString())
            val hasEncryptedItems =
                bundle.isEncrypted ||
                    bundle.verificationToken != null ||
                    bundle.credentials.any { it.dataMap.values.any { v -> PasswordBasedEncryption.isEncryptedWithPassword(v) } } ||
                    bundle.workflows.any { workflowHasEncryptedSecrets(it) } ||
                    bundle.preconditions.any { it.headers.contains(PasswordBasedEncryption.PREFIX) || it.pipelineJson.contains(PasswordBasedEncryption.PREFIX) }

            return ParsedBackupData(
                workflows = bundle.workflows,
                credentials = bundle.credentials,
                preconditions = bundle.preconditions,
                isEncrypted = hasEncryptedItems,
                verificationToken = bundle.verificationToken,
            )
        } catch (_: Exception) {
        }

        val verificationToken = obj.optString("verificationToken").takeIf { it.isNotBlank() }
        val isEncryptedFlag = obj.optBoolean("isEncrypted", false)

        val workflows = mutableListOf<Workflow>()
        val wfArr = obj.optJsonArray("workflows") ?: JsonArray(emptyList())
        for (elem in wfArr) {
            val o = elem as? JsonObject ?: continue
            workflows.add(parseWorkflow(o))
        }

        val credentials = mutableListOf<Credential>()
        val credArr = obj.optJsonArray("credentials") ?: JsonArray(emptyList())
        for (elem in credArr) {
            val o = elem as? JsonObject ?: continue
            credentials.add(parseCredential(o))
        }

        val preconditions = mutableListOf<PreconditionEntity>()
        val precArr = obj.optJsonArray("preconditions") ?: JsonArray(emptyList())
        for (elem in precArr) {
            val o = elem as? JsonObject ?: continue
            preconditions.add(parsePreconditionEntity(o))
        }

        val hasEncryptedItems = isEncryptedFlag ||
            verificationToken != null ||
            credentials.any { it.dataMap.values.any { v -> PasswordBasedEncryption.isEncryptedWithPassword(v) } } ||
            workflows.any { workflowHasEncryptedSecrets(it) } ||
            preconditions.any {
                it.headers.contains(PasswordBasedEncryption.PREFIX) ||
                    it.pipelineJson.contains(PasswordBasedEncryption.PREFIX)
            }

        return ParsedBackupData(
            workflows = workflows,
            credentials = credentials,
            preconditions = preconditions,
            isEncrypted = hasEncryptedItems,
            verificationToken = verificationToken,
        )
    }

    private fun parseJsonArray(arr: JsonArray): ParsedBackupData {
        val workflows = mutableListOf<Workflow>()
        val credentials = mutableListOf<Credential>()
        val preconditions = mutableListOf<PreconditionEntity>()

        for (elem in arr) {
            val item = elem as? JsonObject ?: continue
            val parsedItem = try {
                parseJsonObject(item)
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue

            workflows.addAll(parsedItem.workflows)
            credentials.addAll(parsedItem.credentials)
            preconditions.addAll(parsedItem.preconditions)
        }

        val hasEncrypted = credentials.any { it.dataMap.values.any { v -> PasswordBasedEncryption.isEncryptedWithPassword(v) } } ||
            workflows.any { workflowHasEncryptedSecrets(it) } ||
            preconditions.any {
                it.headers.contains(PasswordBasedEncryption.PREFIX) ||
                    it.pipelineJson.contains(PasswordBasedEncryption.PREFIX)
            }

        return ParsedBackupData(
            workflows = workflows,
            credentials = credentials,
            preconditions = preconditions,
            isEncrypted = hasEncrypted,
        )
    }

    /**
     * Decrypts password-encrypted secrets within the [parsed] backup data.
     * Throws [SecurityException] if the password is incorrect or decryption fails.
     */
    fun decryptBackupData(
        parsed: ParsedBackupData,
        password: CharSequence,
    ): ParsedBackupData {
        // Fast-path verification using verificationToken if present
        if (!parsed.verificationToken.isNullOrBlank()) {
            val verify = try {
                PasswordBasedEncryption.decrypt(parsed.verificationToken, password)
            } catch (e: GeneralSecurityException) {
                throw SecurityException("Incorrect PIN or password", e)
            }
            if (verify != VERIFICATION_SENTINEL) {
                throw SecurityException("Incorrect PIN or password")
            }
        }

        // Decrypt Credentials
        val decryptedCreds = parsed.credentials.map { cred ->
            val updatedMap = cred.dataMap.mapValues { (_, v) ->
                if (PasswordBasedEncryption.isEncryptedWithPassword(v)) {
                    PasswordBasedEncryption.decrypt(v, password)
                } else {
                    v
                }
            }
            cred.copy(dataMap = updatedMap)
        }

        // Decrypt Workflows
        val decryptedWorkflows = parsed.workflows.map { wf ->
            val updatedNodes = wf.nodes.map { node -> decryptWorkflowNodeSecrets(node, password) }
            wf.copy(nodes = updatedNodes)
        }

        // Decrypt Preconditions
        val decryptedPreconditions = parsed.preconditions.map { prec ->
            val updatedHeaders =
                if (PasswordBasedEncryption.isEncryptedWithPassword(prec.headers)) {
                    PasswordBasedEncryption.decrypt(prec.headers, password)
                } else {
                    decryptSensitiveJsonKeys(prec.headers, password)
                }
            val updatedPipeline = decryptPipelineJsonSecrets(prec.pipelineJson, password)
            prec.copy(headers = updatedHeaders, pipelineJson = updatedPipeline)
        }

        return ParsedBackupData(
            workflows = decryptedWorkflows,
            credentials = decryptedCreds,
            preconditions = decryptedPreconditions,
            isEncrypted = false,
            verificationToken = null,
        )
    }

    /**
     * Resolves ID and name conflicts between imported data and existing database entities.
     */
    fun resolveConflicts(
        parsed: ParsedBackupData,
        strategy: ImportConflictStrategy,
        existingWorkflows: List<Workflow>,
        existingCredentials: List<Credential>,
        existingPreconditions: List<PreconditionEntity>,
    ): ParsedBackupData =
        when (strategy) {
            ImportConflictStrategy.OVERWRITE -> parsed
            ImportConflictStrategy.SKIP_EXISTING -> {
                val existingWfIds = existingWorkflows.map { it.id }.toSet()
                val existingCredIds = existingCredentials.map { it.id }.toSet()
                val existingPrecIds = existingPreconditions.map { it.id }.toSet()

                parsed.copy(
                    workflows = parsed.workflows.filterNot { it.id in existingWfIds },
                    credentials = parsed.credentials.filterNot { it.id in existingCredIds },
                    preconditions = parsed.preconditions.filterNot { it.id in existingPrecIds },
                )
            }
            ImportConflictStrategy.KEEP_BOTH -> {
                remapDataWithNewIds(parsed, existingWorkflows, existingCredentials, existingPreconditions)
            }
        }

    private fun remapDataWithNewIds(
        parsed: ParsedBackupData,
        existingWorkflows: List<Workflow>,
        existingCredentials: List<Credential>,
        existingPreconditions: List<PreconditionEntity>,
    ): ParsedBackupData {
        val existingWfNames = existingWorkflows.map { it.name }.toSet()
        val existingCredNames = existingCredentials.map { it.name }.toSet()
        val existingPrecNames = existingPreconditions.map { it.name }.toSet()

        val credIdMap = mutableMapOf<String, String>()
        val remappedCreds = parsed.credentials.map { cred ->
            val newId = UUID.randomUUID().toString()
            credIdMap[cred.id] = newId
            val newName = if (cred.name in existingCredNames) "${cred.name} (Imported)" else cred.name
            cred.copy(id = newId, name = newName)
        }

        val precIdMap = mutableMapOf<String, String>()
        val remappedPrecs = parsed.preconditions.map { prec ->
            val newId = UUID.randomUUID().toString()
            precIdMap[prec.id] = newId
            val newName = if (prec.name in existingPrecNames) "${prec.name} (Imported)" else prec.name
            val remappedCredId = credIdMap[prec.credentialId] ?: prec.credentialId
            prec.copy(id = newId, name = newName, credentialId = remappedCredId)
        }

        val remappedWorkflows = parsed.workflows.map { wf ->
            val newWfId = generateShortWorkflowId()
            val newWfName = if (wf.name in existingWfNames) "${wf.name} (Imported)" else wf.name

            val nodeIdMap = mutableMapOf<String, String>()
            val remappedNodes = wf.nodes.map { node ->
                val newNodeId = UUID.randomUUID().toString()
                nodeIdMap[node.id] = newNodeId

                val updatedConfig = remapCredentialIdsInConfigJson(node.configJson, credIdMap)

                val remappedNodePrecs = node.preconditions.map { p ->
                    val newPId = precIdMap[p.id] ?: p.id
                    when (p) {
                        is Precondition.InternetCheck -> p.copy(id = newPId)
                        is Precondition.VpnCheck -> p.copy(id = newPId)
                        is Precondition.HealthCheck -> p.copy(id = newPId)
                        is Precondition.SimCheck -> p.copy(id = newPId)
                        is Precondition.GpsCheck -> p.copy(id = newPId)
                        is Precondition.BatteryCheck -> p.copy(id = newPId)
                        is Precondition.ChainCheck -> p.copy(id = newPId)
                    }
                }

                node.copy(
                    id = newNodeId,
                    configJson = updatedConfig,
                    preconditions = remappedNodePrecs,
                )
            }

            val remappedConnections = wf.connections.map { conn ->
                conn.copy(
                    fromNodeId = nodeIdMap[conn.fromNodeId] ?: conn.fromNodeId,
                    toNodeId = nodeIdMap[conn.toNodeId] ?: conn.toNodeId,
                )
            }

            wf.copy(
                id = newWfId,
                name = newWfName,
                nodes = remappedNodes,
                connections = remappedConnections,
            )
        }

        return parsed.copy(
            workflows = remappedWorkflows,
            credentials = remappedCreds,
            preconditions = remappedPrecs,
        )
    }

    // --- Deserialization Helpers ---

    private fun parseWorkflow(obj: JsonObject): Workflow {
        val id = obj.optString("id").ifBlank { generateShortWorkflowId() }
        val name = obj.optString("name", "Imported Workflow")
        val description = obj.optString("description", "")
        val isEnabled = obj.optBoolean("isEnabled", true)
        val icon = obj.optString("icon", "")
        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())

        val nodesList = mutableListOf<WorkflowNode>()
        val nodesArr = extractNodesArray(obj)
        for (elem in nodesArr) {
            val nObj = elem as? JsonObject ?: continue
            nodesList.add(parseWorkflowNode(nObj))
        }

        val connectionsList = mutableListOf<WorkflowConnection>()
        val connArr = extractConnectionsArray(obj)
        for (elem in connArr) {
            val cObj = elem as? JsonObject ?: continue
            connectionsList.add(
                WorkflowConnection(
                    fromNodeId = cObj.optString("fromNodeId"),
                    toNodeId = cObj.optString("toNodeId"),
                    outputPort = cObj.optString("outputPort", "main"),
                    inputPort = cObj.optString("inputPort", "main"),
                ),
            )
        }

        return Workflow(
            id = id,
            name = name,
            description = description,
            isEnabled = isEnabled,
            icon = icon,
            nodes = nodesList,
            connections = connectionsList,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun extractNodesArray(obj: JsonObject): JsonArray {
        val directNodes = obj.optJsonArray("nodes")
        if (directNodes != null) return directNodes

        val graphJsonStr = obj.optString("graphJson")
        return if (graphJsonStr.isNotBlank()) {
            try {
                parseJsonObjectSafe(graphJsonStr).optJsonArray("nodes") ?: JsonArray(emptyList())
            } catch (_: Exception) {
                JsonArray(emptyList())
            }
        } else {
            JsonArray(emptyList())
        }
    }

    private fun extractConnectionsArray(obj: JsonObject): JsonArray {
        val directConnections = obj.optJsonArray("connections")
        if (directConnections != null) return directConnections

        val graphJsonStr = obj.optString("graphJson")
        return if (graphJsonStr.isNotBlank()) {
            try {
                parseJsonObjectSafe(graphJsonStr).optJsonArray("connections") ?: JsonArray(emptyList())
            } catch (_: Exception) {
                JsonArray(emptyList())
            }
        } else {
            JsonArray(emptyList())
        }
    }

    private fun parseWorkflowNode(nodeObj: JsonObject): WorkflowNode {
        val id = nodeObj.optString("id", UUID.randomUUID().toString())
        val name = nodeObj.optString("name", "Node")
        val typeStr = nodeObj.optString("type", "")
        val isEnabled = nodeObj.optBoolean("isEnabled", true)
        val rawConfig = nodeObj.optString("configJson", "{}")
        val configJson = DatabaseEncryption.decryptNodeConfig(rawConfig)

        val preconditions = mutableListOf<Precondition>()
        val preArr = nodeObj.optJsonArray("preconditions") ?: JsonArray(emptyList())
        for (elem in preArr) {
            val pObj = elem as? JsonObject ?: continue
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

    private fun parsePrecondition(pObj: JsonObject): Precondition {
        val pId = pObj.optString("id", UUID.randomUUID().toString())
        val pTypeStr = pObj.optString("type", "")
        val pIcon = pObj.optString("icon", "")
        val isPEnabled = pObj.optBoolean("isEnabled", true)
        val pFailureAction = pObj.optString("onFailureAction", "FAIL_IMMEDIATELY")
        val pMaxRetries = pObj.optInt("maxRetries", 3)
        val pRetryInterval = pObj.optInt("retryIntervalSeconds", 30)
        val pRetryStrategy = pObj.optString("retryStrategy", "FIXED")
        val pQueueTimeout = pObj.optInt("queueTimeoutMinutes", 10)

        val precType = try {
            PreconditionType.valueOf(pTypeStr.uppercase().trim())
        } catch (_: IllegalArgumentException) {
            PreconditionType.INTERNET_CHECK
        }

        return when (precType) {
            PreconditionType.INTERNET_CHECK -> Precondition.InternetCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
            )
            PreconditionType.VPN_CHECK -> Precondition.VpnCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
            )
            PreconditionType.HEALTH_CHECK -> Precondition.HealthCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, targetUrl = pObj.optString("targetUrl", ""),
                method = pObj.optString("method", "GET"), headers = pObj.optString("headers", ""),
                expectedStatus = pObj.optInt("expectedStatus", 200), onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
            )
            PreconditionType.SIM_CHECK -> Precondition.SimCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, requiredCarrier = pObj.optString("requiredCarrier", ""),
                requiredSlot = pObj.optInt("requiredSlot", 0), onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
            )
            PreconditionType.GPS_CHECK -> Precondition.GpsCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, requireGpsEnabled = pObj.optBoolean("requireGpsEnabled", true),
                onFailureAction = pFailureAction, maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval,
                retryStrategy = pRetryStrategy, queueTimeoutMinutes = pQueueTimeout,
            )
            PreconditionType.BATTERY_CHECK -> Precondition.BatteryCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, minBatteryPercent = pObj.optInt("minBatteryPercent", 15),
                requireCharging = pObj.optBoolean("requireCharging", false), onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout,
            )
            PreconditionType.CHAINED_CHECK -> {
                val (chainNodes, chainConnections) = parseChainedCheckGraph(pObj)
                Precondition.ChainCheck(
                    id = pId, name = pObj.optString("name", "Chain"), icon = pIcon, isEnabled = isPEnabled,
                    nodes = chainNodes, connections = chainConnections, onFailureAction = pFailureAction,
                    maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                    queueTimeoutMinutes = pQueueTimeout,
                )
            }
        }
    }

    private fun parseChainedCheckGraph(pObj: JsonObject): Pair<List<WorkflowNode>, List<WorkflowConnection>> {
        val directNodesArr = pObj.optJsonArray("nodes")
        val directConnArr = pObj.optJsonArray("connections")
        val pJsonStr = pObj.optString("pipelineJson", "")

        return when {
            directNodesArr != null || directConnArr != null -> {
                val nodes =
                    directNodesArr
                        ?.mapNotNull { elem -> (elem as? JsonObject)?.let(::parseWorkflowNode) }
                        ?: emptyList()
                val connections =
                    directConnArr
                        ?.mapNotNull { elem ->
                            val cObj = elem as? JsonObject ?: return@mapNotNull null
                            WorkflowConnection(
                                fromNodeId = cObj.optString("fromNodeId"),
                                toNodeId = cObj.optString("toNodeId"),
                                outputPort = cObj.optString("outputPort", "main"),
                                inputPort = cObj.optString("inputPort", "main"),
                            )
                        }
                        ?: emptyList()
                nodes to connections
            }
            pJsonStr.isNotBlank() -> {
                try {
                    val graph = AppJson.decodeFromString<WorkflowGraph>(pJsonStr)
                    graph.nodes.map { n ->
                        n.copy(configJson = DatabaseEncryption.decryptNodeConfig(n.configJson))
                    } to graph.connections
                } catch (_: Exception) {
                    parseChainedCheckGraph(parseJsonObjectSafe(pJsonStr))
                }
            }
            else -> emptyList<WorkflowNode>() to emptyList()
        }
    }

    private fun remapCredentialIdsInConfigJson(
        configJson: String,
        credIdMap: Map<String, String>,
    ): String {
        if (configJson.isBlank() || credIdMap.isEmpty()) return configJson
        return try {
            val json = parseJsonObjectSafe(configJson)
            if (json.isEmpty()) return configJson
            var modified = false

            fun remapElement(element: JsonElement): JsonElement =
                when (element) {
                    is JsonPrimitive -> {
                        val replacement = if (element.isString) credIdMap[element.content] else null
                        if (replacement != null) {
                            modified = true
                            JsonPrimitive(replacement)
                        } else {
                            element
                        }
                    }
                    is JsonObject -> JsonObject(element.mapValues { (_, v) -> remapElement(v) })
                    is JsonArray -> JsonArray(element.map { remapElement(it) })
                }
            val remapped = remapElement(json) as JsonObject
            if (modified) AppJson.encodeToString(remapped) else configJson
        } catch (_: Exception) {
            configJson
        }
    }

    private fun parseCredential(obj: JsonObject): Credential {
        val id = obj.optString("id", UUID.randomUUID().toString())
        val name = obj.optString("name", "Imported Credential")
        val type = obj.optString("type", CredentialType.BEARER_TOKEN)
        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())

        val dataMap = mutableMapOf<String, String>()
        val dataObj = obj.optJsonObject("data") ?: obj.optJsonObject("dataMap")
        if (dataObj != null) {
            for ((k, v) in dataObj) {
                dataMap[k] = v.optString()
            }
        } else if (obj.containsKey("dataJson")) {
            try {
                val parsedDataJson = parseJsonObjectSafe(obj.optString("dataJson"))
                for ((k, v) in parsedDataJson) {
                    dataMap[k] = v.optString()
                }
            } catch (_: Exception) {
            }
        }

        return Credential(
            id = id,
            name = name,
            type = type,
            dataMap = dataMap,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun parsePreconditionEntity(obj: JsonObject): PreconditionEntity =
        PreconditionEntity(
            id = obj.optString("id", UUID.randomUUID().toString()),
            name = obj.optString("name", "Imported Precondition"),
            type = obj.optString("type", PreconditionType.INTERNET_CHECK.name),
            isEnabled = obj.optBoolean("isEnabled", true),
            icon = obj.optString("icon", ""),
            targetUrl = obj.optString("targetUrl", ""),
            method = obj.optString("method", "GET"),
            headers = obj.optString("headers", ""),
            expectedStatus = obj.optInt("expectedStatus", 200),
            requiredCarrier = obj.optString("requiredCarrier", ""),
            requiredPermission = obj.optString("requiredPermission", ""),
            credentialId = obj.optString("credentialId", ""),
            pipelineJson = obj.optString("pipelineJson", ""),
            minBatteryPercent = obj.optInt("minBatteryPercent", 15),
            requireCharging = obj.optBoolean("requireCharging", false),
            onFailureAction = obj.optString("onFailureAction", "FAIL_IMMEDIATELY"),
            maxRetries = obj.optInt("maxRetries", 3),
            retryIntervalSeconds = obj.optInt("retryIntervalSeconds", 30),
            retryStrategy = obj.optString("retryStrategy", "FIXED"),
            queueTimeoutMinutes = obj.optInt("queueTimeoutMinutes", 10),
            showNotificationOnFailure = obj.optBoolean("showNotificationOnFailure", false),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
        )

    private fun encryptSensitiveJsonKeys(jsonString: String, password: String): String {
        if (jsonString.isBlank()) return jsonString
        return try {
            val json = parseJsonObjectSafe(jsonString)
            if (json.isEmpty()) return jsonString
            val (transformed, modified) = transformBackupJsonObject(json) { key, value ->
                val shouldEncrypt = DatabaseEncryption.isSensitiveKey(key) &&
                    value.isNotBlank() &&
                    !PasswordBasedEncryption.isEncryptedWithPassword(value)
                if (shouldEncrypt) PasswordBasedEncryption.encrypt(value, password) else value
            }
            if (modified) AppJson.encodeToString(transformed) else jsonString
        } catch (_: Exception) {
            jsonString
        }
    }

    private fun decryptSensitiveJsonKeys(jsonString: String, password: CharSequence): String {
        if (jsonString.isBlank()) return jsonString
        return try {
            val json = parseJsonObjectSafe(jsonString)
            if (json.isEmpty()) return jsonString
            val (transformed, modified) = transformBackupJsonObject(json) { _, value ->
                if (PasswordBasedEncryption.isEncryptedWithPassword(value)) {
                    PasswordBasedEncryption.decrypt(value, password)
                } else {
                    value
                }
            }
            if (modified) AppJson.encodeToString(transformed) else jsonString
        } catch (_: Exception) {
            jsonString
        }
    }

    private fun transformBackupJsonObject(
        obj: JsonObject,
        transform: (key: String, value: String) -> String,
    ): Pair<JsonObject, Boolean> {
        var modified = false
        val newMap = obj.toMutableMap()
        for ((key, value) in obj) {
            val (updated, changed) = transformBackupElement(key, value, transform)
            if (changed) {
                newMap[key] = updated
                modified = true
            }
        }
        return JsonObject(newMap) to modified
    }

    private fun transformBackupElement(
        key: String,
        value: JsonElement,
        transform: (key: String, value: String) -> String,
    ): Pair<JsonElement, Boolean> =
        when (value) {
            is JsonPrimitive -> {
                if (value.isString) {
                    val updated = transform(key, value.content)
                    if (updated != value.content) JsonPrimitive(updated) to true else value to false
                } else {
                    value to false
                }
            }
            is JsonObject -> transformBackupJsonObject(value, transform)
            is JsonArray -> {
                var arrModified = false
                val mapped = value.map { elem ->
                    val (updated, changed) = transformBackupElement(key, elem, transform)
                    if (changed) arrModified = true
                    updated
                }
                JsonArray(mapped) to arrModified
            }
        }

    private fun exportWorkflowNodeSecrets(
        node: WorkflowNode,
        password: String?,
        decryptLocalStorageValue: (String) -> String,
    ): WorkflowNode {
        val rawConfig = DatabaseEncryption.decryptNodeConfig(node.configJson)
        val exportedConfig =
            if (!password.isNullOrBlank()) encryptSensitiveJsonKeys(rawConfig, password) else rawConfig
        val exportedPrecs =
            node.preconditions.map { prec ->
                exportPreconditionSecrets(prec, password, decryptLocalStorageValue)
            }
        return node.copy(configJson = exportedConfig, preconditions = exportedPrecs)
    }

    private fun exportPreconditionSecrets(
        prec: Precondition,
        password: String?,
        decryptLocalStorageValue: (String) -> String,
    ): Precondition =
        when (prec) {
            is Precondition.HealthCheck -> {
                val cleanHeaders = decryptLocalStorageValue(prec.headers)
                val exportedHeaders =
                    if (!password.isNullOrBlank() && cleanHeaders.isNotBlank()) {
                        PasswordBasedEncryption.encrypt(cleanHeaders, password)
                    } else {
                        cleanHeaders
                    }
                prec.copy(headers = exportedHeaders)
            }
            is Precondition.ChainCheck ->
                prec.copy(
                    nodes = prec.nodes.map { n -> exportWorkflowNodeSecrets(n, password, decryptLocalStorageValue) },
                )
            else -> prec
        }

    private fun exportPipelineJsonSecrets(
        pipelineJson: String,
        password: String?,
        decryptLocalStorageValue: (String) -> String,
    ): String {
        if (pipelineJson.isBlank()) return pipelineJson
        return try {
            val graph = AppJson.decodeFromString<WorkflowGraph>(pipelineJson)
            val updatedNodes = graph.nodes.map { n -> exportWorkflowNodeSecrets(n, password, decryptLocalStorageValue) }
            AppJson.encodeToString(graph.copy(nodes = updatedNodes))
        } catch (_: Exception) {
            if (!password.isNullOrBlank()) encryptSensitiveJsonKeys(pipelineJson, password) else pipelineJson
        }
    }

    private fun decryptWorkflowNodeSecrets(
        node: WorkflowNode,
        password: CharSequence,
    ): WorkflowNode {
        val decryptedConfig = decryptSensitiveJsonKeys(node.configJson, password)
        val decryptedPrecs = node.preconditions.map { prec -> decryptPreconditionSecrets(prec, password) }
        return node.copy(configJson = decryptedConfig, preconditions = decryptedPrecs)
    }

    private fun decryptPreconditionSecrets(
        prec: Precondition,
        password: CharSequence,
    ): Precondition =
        when (prec) {
            is Precondition.HealthCheck -> {
                val decryptedHeaders =
                    if (PasswordBasedEncryption.isEncryptedWithPassword(prec.headers)) {
                        PasswordBasedEncryption.decrypt(prec.headers, password)
                    } else {
                        prec.headers
                    }
                prec.copy(headers = decryptedHeaders)
            }
            is Precondition.ChainCheck ->
                prec.copy(nodes = prec.nodes.map { n -> decryptWorkflowNodeSecrets(n, password) })
            else -> prec
        }

    private fun decryptPipelineJsonSecrets(
        pipelineJson: String,
        password: CharSequence,
    ): String {
        if (pipelineJson.isBlank()) return pipelineJson
        return try {
            val graph = AppJson.decodeFromString<WorkflowGraph>(pipelineJson)
            val updatedNodes = graph.nodes.map { n -> decryptWorkflowNodeSecrets(n, password) }
            AppJson.encodeToString(graph.copy(nodes = updatedNodes))
        } catch (_: Exception) {
            decryptSensitiveJsonKeys(pipelineJson, password)
        }
    }

    private fun workflowHasEncryptedSecrets(wf: Workflow): Boolean =
        wf.nodes.any { nodeHasEncryptedSecrets(it) }

    private fun nodeHasEncryptedSecrets(node: WorkflowNode): Boolean =
        node.configJson.contains(PasswordBasedEncryption.PREFIX) ||
            node.preconditions.any { prec ->
                when (prec) {
                    is Precondition.HealthCheck -> prec.headers.contains(PasswordBasedEncryption.PREFIX)
                    is Precondition.ChainCheck -> prec.nodes.any { nodeHasEncryptedSecrets(it) }
                    else -> false
                }
            }
}
