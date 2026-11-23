package dev.zophop.domain.engine.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
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

            val inputContextJson = JSONObject(context.getAll()).toString()

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
                val json = JSONObject(resultJson)
                val isSuccess = json.optBoolean("isSuccess", false)
                val isIgnored = json.optBoolean("isIgnored", false)
                val outputPort = json.optString("outputPort", "main")
                val logMessage = json.optString("logMessage", "Plugin '${pluginInfo.manifest.displayName}' executed")
                val errorReason = if (json.has("errorReason")) json.getString("errorReason") else "Plugin execution failed"

                populateOutputDataMap(json.optJSONObject("outputDataMap"), context)

                when {
                    isIgnored -> NodeExecutionResult.Ignored(errorReason)
                    isSuccess -> NodeExecutionResult.Success(outputPort = outputPort, logMessage = logMessage)
                    else -> NodeExecutionResult.Failed(errorReason)
                }
            } catch (e: JSONException) {
                NodeExecutionResult.Failed(
                    "Failed to parse response from plugin '${pluginInfo.manifest.displayName}': ${e.message}",
                )
            }
        }

    private suspend fun injectCredentialsIfPresent(configJson: String, context: ExecutionContext) {
        val config = try {
            JSONObject(configJson)
        } catch (_: JSONException) {
            JSONObject()
        }
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

    private fun populateOutputDataMap(outputData: JSONObject?, context: ExecutionContext) {
        if (outputData == null) return
        val keys = outputData.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val value = outputData.get(k)
            if (value != JSONObject.NULL) {
                context.set(k, value)
            }
        }
    }

    private suspend fun executeViaAidlService(
        ctx: Context,
        intent: Intent,
        nodeId: String,
        configJson: String,
        inputContextJson: String,
    ): String =
        suspendCancellableCoroutine { continuation ->
            var isBound = false
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        service: IBinder?,
                    ) {
                        try {
                            val binder = INodePluginService.Stub.asInterface(service)
                            val response = binder.executeNode(nodeId, configJson, inputContextJson) ?: ""
                            if (continuation.isActive) continuation.resume(response)
                        } catch (_: RemoteException) {
                            if (continuation.isActive) continuation.resume("")
                        } catch (_: RuntimeException) {
                            if (continuation.isActive) continuation.resume("")
                        } finally {
                            try {
                                ctx.unbindService(this)
                                isBound = false
                            } catch (_: IllegalArgumentException) {
                            }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (continuation.isActive) continuation.resume("")
                    }
                }

            continuation.invokeOnCancellation {
                if (isBound) {
                    try {
                        ctx.unbindService(connection)
                        isBound = false
                    } catch (_: IllegalArgumentException) {
                    }
                }
            }

            isBound =
                try {
                    ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (_: SecurityException) {
                    false
                }

            if (!isBound && continuation.isActive) {
                continuation.resume("")
            }
        }
}
