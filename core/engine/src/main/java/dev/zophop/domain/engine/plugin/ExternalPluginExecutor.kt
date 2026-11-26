package dev.zophop.domain.engine.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import dev.zophop.data.CredentialRepository
import dev.zophop.data.DatabaseEncryption
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optJsonObject
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.ExecutionLogFormatter
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

class ExternalPluginExecutor(
    private val pluginInfo: ExternalPluginServiceInfo,
    private val credentialRepository: CredentialRepository? = null,
) : NodeExecutor {
    companion object {
        private const val IPC_TIMEOUT_MS = 15_000L
        private const val CRED_PREFIX = "_cred_"
    }

    internal var aidlInterfaceResolver: (IBinder?) -> INodePluginService? = INodePluginService.Stub::asInterface

    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult =
        withContext(Dispatchers.IO) {
            if (appContext == null) {
                NodeExecutionResult.Failed("Context unavailable for plugin execution")
            } else {
                executeWithAppContext(node, context, appContext)
            }
        }

    private suspend fun executeWithAppContext(
        node: WorkflowNode,
        context: ExecutionContext,
        ctx: Context,
    ): NodeExecutionResult {
        val intent =
            Intent().apply {
                component = ComponentName(pluginInfo.packageName, pluginInfo.serviceClassName)
            }

        val scopedCredentials = resolveScopedCredentials(node.configJson)
        val explicitSecrets = collectExplicitSecrets(node.configJson, scopedCredentials)

        val inputContextJson =
            buildJsonObject {
                for ((k, v) in context.getAll()) {
                    if (!k.startsWith(CRED_PREFIX)) {
                        put(k, v.toString())
                    }
                }
                for ((k, v) in scopedCredentials) {
                    put(k, v)
                }
            }.toString()

        val resultJson =
            withTimeoutOrNull(IPC_TIMEOUT_MS) {
                executeViaAidlService(ctx, intent, node.id, node.configJson, inputContextJson)
            }

        return when {
            resultJson == null ->
                NodeExecutionResult.Failed(
                    "Timeout (${IPC_TIMEOUT_MS / 1000}s) waiting for plugin '${pluginInfo.manifest.displayName}'",
                )
            resultJson.isBlank() ->
                NodeExecutionResult.Failed(
                    "Failed to connect or receive response from plugin '${pluginInfo.manifest.displayName}'",
                )
            else -> parsePluginResult(resultJson, explicitSecrets, context)
        }
    }

    private fun parsePluginResult(
        resultJson: String,
        explicitSecrets: List<String>,
        context: ExecutionContext,
    ): NodeExecutionResult =
        try {
            val json = parseJsonObjectSafe(resultJson)
            val isSuccess = json.optBoolean("isSuccess", false)
            val isIgnored = json.optBoolean("isIgnored", false)
            val outputPort = json.optString("outputPort", "main")
            val rawLogMessage = json.optString("logMessage", "Plugin '${pluginInfo.manifest.displayName}' executed")
            val rawErrorReason = json.optString("errorReason", "Plugin execution failed")
            val logMessage = ExecutionLogFormatter.redactSecrets(rawLogMessage, explicitSecrets)
            val errorReason = ExecutionLogFormatter.redactSecrets(rawErrorReason, explicitSecrets)

            populateOutputDataMap(json.optJsonObject("outputDataMap"), context)

            when {
                isIgnored -> NodeExecutionResult.Ignored(errorReason)
                isSuccess -> NodeExecutionResult.Success(outputPort = outputPort, logMessage = logMessage)
                else -> NodeExecutionResult.Failed(errorReason)
            }
        } catch (e: SerializationException) {
            val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
            NodeExecutionResult.Failed(
                "Failed to parse response from plugin '${pluginInfo.manifest.displayName}': $safeMsg",
            )
        }

    private suspend fun resolveScopedCredentials(configJson: String): Map<String, String> {
        val config = parseJsonObjectSafe(configJson)
        val credentialId = config.optString("credentialId", "").trim()
        val cred =
            if (credentialId.isNotBlank() && credentialRepository != null) {
                credentialRepository.getCredentialById(credentialId)
            } else {
                null
            }
        return cred?.dataMap?.entries?.associate { (k, v) -> "$CRED_PREFIX$k" to v } ?: emptyMap()
    }

    private fun collectExplicitSecrets(
        configJson: String,
        scopedCredentials: Map<String, String>,
    ): List<String> {
        val secrets = mutableListOf<String>()
        for ((k, v) in scopedCredentials) {
            val rawKey = k.removePrefix(CRED_PREFIX)
            if (v.isNotBlank() && (DatabaseEncryption.isSensitiveKey(rawKey) || v.length >= 4)) {
                secrets.add(v)
            }
        }
        val config = parseJsonObjectSafe(configJson)
        for ((k, v) in config) {
            val content = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (DatabaseEncryption.isSensitiveKey(k) && !content.isNullOrBlank()) {
                secrets.add(content)
            }
        }
        return secrets
    }

    private fun populateOutputDataMap(outputData: JsonObject?, context: ExecutionContext) {
        if (outputData == null) return
        for ((k, value) in outputData) {
            if (!k.startsWith(CRED_PREFIX) && value !is JsonNull) {
                context.set(k, extractPrimitiveValue(value))
            }
        }
    }

    private fun extractPrimitiveValue(value: JsonElement): Any =
        when (value) {
            is JsonPrimitive ->
                if (value.isString) {
                    value.content
                } else {
                    value.booleanOrNull ?: value.intOrNull ?: value.doubleOrNull ?: value.content
                }
            else -> value.toString()
        }

    private suspend fun executeViaAidlService(
        ctx: Context,
        intent: Intent,
        nodeId: String,
        configJson: String,
        inputContextJson: String,
    ): String? =
        suspendCancellableCoroutine { continuation ->
            var isResumed = false
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (isResumed) return
                        try {
                            val aidlService = aidlInterfaceResolver(service)
                            if (aidlService == null) {
                                isResumed = true
                                ctx.unbindService(this)
                                continuation.resume(null)
                                return
                            }
                            val result = aidlService.executeNode(nodeId, configJson, inputContextJson)
                            isResumed = true
                            ctx.unbindService(this)
                            continuation.resume(result)
                        } catch (ignored: RemoteException) {
                            if (!isResumed) {
                                isResumed = true
                                try {
                                    ctx.unbindService(this)
                                } catch (_: Exception) {
                                }
                                continuation.resume(null)
                            }
                        } catch (ignored: Exception) {
                            if (!isResumed) {
                                isResumed = true
                                try {
                                    ctx.unbindService(this)
                                } catch (_: Exception) {
                                }
                                continuation.resume(null)
                            }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (!isResumed) {
                            isResumed = true
                            continuation.resume(null)
                        }
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        if (!isResumed) {
                            isResumed = true
                            continuation.resume(null)
                        }
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        if (!isResumed) {
                            isResumed = true
                            try {
                                ctx.unbindService(this)
                            } catch (_: Exception) {
                            }
                            continuation.resume(null)
                        }
                    }
                }

            val bound =
                try {
                    ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (ignored: SecurityException) {
                    false
                }

            if (!bound) {
                isResumed = true
                continuation.resume(null)
            }

            continuation.invokeOnCancellation {
                if (!isResumed) {
                    isResumed = true
                    try {
                        ctx.unbindService(connection)
                    } catch (_: Exception) {
                    }
                }
            }
        }
}
