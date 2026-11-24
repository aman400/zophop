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
import dev.zophop.domain.model.WorkflowNode
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
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
                            val rawConfig =
                                if (DatabaseEncryption.isEncrypted(node.configJson)) {
                                    DatabaseEncryption.decryptNodeConfig(node.configJson)
                                } else {
                                    node.configJson
                                }
                            val config =
                                if (!password.isNullOrBlank()) {
                                    encryptSensitiveJsonKeys(rawConfig, password)
                                } else {
                                    rawConfig
                                }
                            node.copy(configJson = config)
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
                val headers =
                    if (!password.isNullOrBlank()) {
                        encryptSensitiveJsonKeys(prec.headers, password)
                    } else {
                        prec.headers
                    }
                val pipeline =
                    if (!password.isNullOrBlank()) {
                        encryptSensitiveJsonKeys(prec.pipelineJson, password)
                    } else {
                        prec.pipelineJson
                    }
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
            parseJsonArray(JSONArray(trimmed))
        } else {
            parseJsonObject(JSONObject(trimmed))
        }
    }

    private fun parseJsonObject(obj: JSONObject): ParsedBackupData {
        if (isBundleObject(obj)) {
            return parseBundle(obj)
        }
        if (isWorkflowObject(obj)) {
            val wf = parseWorkflow(obj)
            val hasEncrypted = wf.nodes.any { it.configJson.contains(PasswordBasedEncryption.PREFIX) }
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

    private fun isBundleObject(obj: JSONObject): Boolean =
        obj.optString("type") == BACKUP_TYPE ||
            (obj.has("workflows") && (obj.has("credentials") || obj.has("preconditions")))

    private fun isWorkflowObject(obj: JSONObject): Boolean =
        obj.has("nodes") || obj.has("graphJson") || (obj.has("name") && obj.has("connections"))

    private fun isCredentialObject(obj: JSONObject): Boolean =
        obj.has("type") && (obj.has("data") || obj.has("dataMap") || obj.has("dataJson"))

    private fun isPreconditionObject(obj: JSONObject): Boolean =
        obj.has("type") && (obj.has("onFailureAction") || obj.has("targetUrl") || obj.has("minBatteryPercent"))

    private fun parseBundle(obj: JSONObject): ParsedBackupData {
        try {
            val bundle = AppJson.decodeFromString<BackupBundle>(obj.toString())
            val hasEncryptedItems =
                bundle.isEncrypted ||
                    bundle.verificationToken != null ||
                    bundle.credentials.any { it.dataMap.values.any { v -> PasswordBasedEncryption.isEncryptedWithPassword(v) } } ||
                    bundle.workflows.any { it.nodes.any { n -> n.configJson.contains(PasswordBasedEncryption.PREFIX) } } ||
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
        val wfArr = obj.optJSONArray("workflows") ?: JSONArray()
        for (i in 0 until wfArr.length()) {
            workflows.add(parseWorkflow(wfArr.getJSONObject(i)))
        }

        val credentials = mutableListOf<Credential>()
        val credArr = obj.optJSONArray("credentials") ?: JSONArray()
        for (i in 0 until credArr.length()) {
            credentials.add(parseCredential(credArr.getJSONObject(i)))
        }

        val preconditions = mutableListOf<PreconditionEntity>()
        val precArr = obj.optJSONArray("preconditions") ?: JSONArray()
        for (i in 0 until precArr.length()) {
            preconditions.add(parsePreconditionEntity(precArr.getJSONObject(i)))
        }

        val hasEncryptedItems = isEncryptedFlag ||
            verificationToken != null ||
            credentials.any { it.dataMap.values.any { v -> PasswordBasedEncryption.isEncryptedWithPassword(v) } } ||
            workflows.any { it.nodes.any { n -> n.configJson.contains(PasswordBasedEncryption.PREFIX) } }

        return ParsedBackupData(
            workflows = workflows,
            credentials = credentials,
            preconditions = preconditions,
            isEncrypted = hasEncryptedItems,
            verificationToken = verificationToken,
        )
    }

    private fun parseJsonArray(arr: JSONArray): ParsedBackupData {
        val workflows = mutableListOf<Workflow>()
        val credentials = mutableListOf<Credential>()
        val preconditions = mutableListOf<PreconditionEntity>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
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
            workflows.any { it.nodes.any { n -> n.configJson.contains(PasswordBasedEncryption.PREFIX) } }

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
            val updatedNodes = wf.nodes.map { node ->
                val decryptedConfig = decryptSensitiveJsonKeys(node.configJson, password)
                node.copy(configJson = decryptedConfig)
            }
            wf.copy(nodes = updatedNodes)
        }

        // Decrypt Preconditions
        val decryptedPreconditions = parsed.preconditions.map { prec ->
            val updatedHeaders = decryptSensitiveJsonKeys(prec.headers, password)
            val updatedPipeline = decryptSensitiveJsonKeys(prec.pipelineJson, password)
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
            val newWfId = UUID.randomUUID().toString()
            val newWfName = if (wf.name in existingWfNames) "${wf.name} (Imported)" else wf.name

            val nodeIdMap = mutableMapOf<String, String>()
            val remappedNodes = wf.nodes.map { node ->
                val newNodeId = UUID.randomUUID().toString()
                nodeIdMap[node.id] = newNodeId

                var updatedConfig = node.configJson
                for ((oldCId, newCId) in credIdMap) {
                    if (updatedConfig.contains(oldCId)) {
                        updatedConfig = updatedConfig.replace(oldCId, newCId)
                    }
                }

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

    private fun parseWorkflow(obj: JSONObject): Workflow {
        val id = obj.optString("id", UUID.randomUUID().toString())
        val name = obj.optString("name", "Imported Workflow")
        val description = obj.optString("description", "")
        val isEnabled = obj.optBoolean("isEnabled", true)
        val icon = obj.optString("icon", "")
        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())

        val nodesList = mutableListOf<WorkflowNode>()
        val nodesArr = extractNodesArray(obj)
        for (i in 0 until nodesArr.length()) {
            val nObj = nodesArr.getJSONObject(i)
            nodesList.add(parseWorkflowNode(nObj))
        }

        val connectionsList = mutableListOf<WorkflowConnection>()
        val connArr = extractConnectionsArray(obj)
        for (i in 0 until connArr.length()) {
            val cObj = connArr.getJSONObject(i)
            connectionsList.add(
                WorkflowConnection(
                    fromNodeId = cObj.getString("fromNodeId"),
                    toNodeId = cObj.getString("toNodeId"),
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

    private fun extractNodesArray(obj: JSONObject): JSONArray {
        val directNodes = obj.optJSONArray("nodes")
        if (directNodes != null) return directNodes

        val graphJsonStr = obj.optString("graphJson")
        val innerNodes = if (graphJsonStr.isNotBlank()) {
            try {
                JSONObject(graphJsonStr).optJSONArray("nodes")
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return innerNodes ?: JSONArray()
    }

    private fun extractConnectionsArray(obj: JSONObject): JSONArray {
        val directConnections = obj.optJSONArray("connections")
        if (directConnections != null) return directConnections

        val graphJsonStr = obj.optString("graphJson")
        val innerConn = if (graphJsonStr.isNotBlank()) {
            try {
                JSONObject(graphJsonStr).optJSONArray("connections")
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return innerConn ?: JSONArray()
    }

    private fun parseWorkflowNode(nodeObj: JSONObject): WorkflowNode {
        val id = nodeObj.optString("id", UUID.randomUUID().toString())
        val name = nodeObj.optString("name", "Node")
        val typeStr = nodeObj.optString("type", "")
        val isEnabled = nodeObj.optBoolean("isEnabled", true)
        val rawConfig = nodeObj.optString("configJson", "{}")
        val configJson = if (DatabaseEncryption.isEncrypted(rawConfig)) {
            DatabaseEncryption.decryptNodeConfig(rawConfig)
        } else {
            rawConfig
        }

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
        val pId = pObj.optString("id", UUID.randomUUID().toString())
        val pTypeStr = pObj.optString("type", "")
        val pIcon = pObj.optString("icon", "")
        val isPEnabled = pObj.optBoolean("isEnabled", true)
        val pFailureAction = pObj.optString("onFailureAction", "FAIL_IMMEDIATELY")
        val pMaxRetries = pObj.optInt("maxRetries", 3)
        val pRetryInterval = pObj.optInt("retryIntervalSeconds", 30)
        val pRetryStrategy = pObj.optString("retryStrategy", "FIXED")
        val pQueueTimeout = pObj.optInt("queueTimeoutMinutes", 10)
        val pShowNotif = pObj.optBoolean("showNotificationOnFailure", false)

        val precType = try {
            PreconditionType.valueOf(pTypeStr.uppercase().trim())
        } catch (_: IllegalArgumentException) {
            PreconditionType.INTERNET_CHECK
        }

        return when (precType) {
            PreconditionType.INTERNET_CHECK -> Precondition.InternetCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout, showNotificationOnFailure = pShowNotif,
            )
            PreconditionType.VPN_CHECK -> Precondition.VpnCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout, showNotificationOnFailure = pShowNotif,
            )
            PreconditionType.HEALTH_CHECK -> Precondition.HealthCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, targetUrl = pObj.optString("targetUrl", ""),
                method = pObj.optString("method", "GET"), headers = pObj.optString("headers", ""),
                expectedStatus = pObj.optInt("expectedStatus", 200), onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout, showNotificationOnFailure = pShowNotif,
            )
            PreconditionType.SIM_CHECK -> Precondition.SimCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, requiredCarrier = pObj.optString("requiredCarrier", ""),
                requiredSlot = pObj.optInt("requiredSlot", 0), onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout, showNotificationOnFailure = pShowNotif,
            )
            PreconditionType.GPS_CHECK -> Precondition.GpsCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, requireGpsEnabled = pObj.optBoolean("requireGpsEnabled", true),
                onFailureAction = pFailureAction, maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval,
                retryStrategy = pRetryStrategy, queueTimeoutMinutes = pQueueTimeout, showNotificationOnFailure = pShowNotif,
            )
            PreconditionType.BATTERY_CHECK -> Precondition.BatteryCheck(
                id = pId, isEnabled = isPEnabled, icon = pIcon, minBatteryPercent = pObj.optInt("minBatteryPercent", 15),
                requireCharging = pObj.optBoolean("requireCharging", false), onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout, showNotificationOnFailure = pShowNotif,
            )
            PreconditionType.CHAINED_CHECK -> Precondition.ChainCheck(
                id = pId, name = pObj.optString("name", "Chain"), icon = pIcon, isEnabled = isPEnabled,
                nodes = emptyList(), connections = emptyList(), onFailureAction = pFailureAction,
                maxRetries = pMaxRetries, retryIntervalSeconds = pRetryInterval, retryStrategy = pRetryStrategy,
                queueTimeoutMinutes = pQueueTimeout, showNotificationOnFailure = pShowNotif,
            )
        }
    }

    private fun parseCredential(obj: JSONObject): Credential {
        val id = obj.optString("id", UUID.randomUUID().toString())
        val name = obj.optString("name", "Imported Credential")
        val type = obj.optString("type", CredentialType.BEARER_TOKEN)
        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())

        val dataMap = mutableMapOf<String, String>()
        val dataObj = obj.optJSONObject("data") ?: obj.optJSONObject("dataMap")
        if (dataObj != null) {
            val keys = dataObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                dataMap[k] = dataObj.optString(k, "")
            }
        } else if (obj.has("dataJson")) {
            try {
                val parsedDataJson = JSONObject(obj.getString("dataJson"))
                val keys = parsedDataJson.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    dataMap[k] = parsedDataJson.optString(k, "")
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

    private fun parsePreconditionEntity(obj: JSONObject): PreconditionEntity =
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
            val json = JSONObject(jsonString)
            var modified = false
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (encryptJsonKeyIfSensitive(json, key, password)) {
                    modified = true
                }
            }
            if (modified) json.toString() else jsonString
        } catch (_: Exception) {
            jsonString
        }
    }

    private fun encryptJsonKeyIfSensitive(json: JSONObject, key: String, password: String): Boolean {
        if (!DatabaseEncryption.isSensitiveKey(key)) return false
        val value = json.optString(key, "")
        if (value.isNotBlank() && !PasswordBasedEncryption.isEncryptedWithPassword(value)) {
            json.put(key, PasswordBasedEncryption.encrypt(value, password))
            return true
        }
        return false
    }

    private fun decryptSensitiveJsonKeys(jsonString: String, password: CharSequence): String {
        if (jsonString.isBlank()) return jsonString
        return try {
            val json = JSONObject(jsonString)
            var modified = false
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (PasswordBasedEncryption.isEncryptedWithPassword(value)) {
                    json.put(key, PasswordBasedEncryption.decrypt(value, password))
                    modified = true
                }
            }
            if (modified) json.toString() else jsonString
        } catch (_: Exception) {
            jsonString
        }
    }
}
