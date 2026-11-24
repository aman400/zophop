package dev.zophop.domain.engine.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optJsonObject
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
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
    }

    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult =
        withContext(Dispatchers.IO) {
            val ctx = appContext ?: return@withContext NodeExecutionResult.Failed("Context unavailable for plugin execution")

            val intent =
                Intent().apply {
                    component = ComponentName(pluginInfo.packageName, pluginInfo.serviceClassName)
                }

            injectCredentialsIfPresent(node.configJson, context)

            val inputContextJson =
                buildJsonObject {
                    for ((k, v) in context.getAll()) {
                        put(k, v.toString())
                    }
                }.toString()

            val resultJson =
                withTimeoutOrNull(IPC_TIMEOUT_MS) {
                    executeViaAidlService(ctx, intent, node.id, node.configJson, inputContextJson)
                }

            if (resultJson == null) {
                return@withContext NodeExecutionResult.Failed(
                    "Timeout (${IPC_TIMEOUT_MS / 1000}s) waiting for plugin '${pluginInfo.manifest.displayName}'",
                )
            }

            if (resultJson.isBlank()) {
                return@withContext NodeExecutionResult.Failed(
                    "Failed to connect or receive response from plugin '${pluginInfo.manifest.displayName}'",
                )
            }

            try {
                val json = parseJsonObjectSafe(resultJson)
                val isSuccess = json.optBoolean("isSuccess", false)
                val isIgnored = json.optBoolean("isIgnored", false)
                val outputPort = json.optString("outputPort", "main")
                val logMessage = json.optString("logMessage", "Plugin '${pluginInfo.manifest.displayName}' executed")
                val errorReason = json.optString("errorReason", "Plugin execution failed")

                populateOutputDataMap(json.optJsonObject("outputDataMap"), context)

                when {
                    isIgnored -> NodeExecutionResult.Ignored(errorReason)
                    isSuccess -> NodeExecutionResult.Success(outputPort = outputPort, logMessage = logMessage)
                    else -> NodeExecutionResult.Failed(errorReason)
                }
            } catch (e: SerializationException) {
                NodeExecutionResult.Failed(
                    "Failed to parse response from plugin '${pluginInfo.manifest.displayName}': ${e.message}",
                )
            }
        }

    private suspend fun injectCredentialsIfPresent(configJson: String, context: ExecutionContext) {
        val config = parseJsonObjectSafe(configJson)
        val credentialId = config.optString("credentialId", "").trim()
        if (credentialId.isNotBlank() && credentialRepository != null) {
            val cred = credentialRepository.getCredentialById(credentialId)
            if (cred != null) {
                for ((k, v) in cred.dataMap) {
                    context.set("_cred_$k", v)
                }
            }
        }
    }

    private fun populateOutputDataMap(outputData: JsonObject?, context: ExecutionContext) {
        if (outputData == null) return
        for ((k, value) in outputData) {
            if (value !is JsonNull) {
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
                            val aidlService = INodePluginService.Stub.asInterface(service)
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
