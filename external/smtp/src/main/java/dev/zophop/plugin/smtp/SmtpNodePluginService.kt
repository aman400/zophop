package dev.zophop.plugin.smtp

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodePluginManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

class SmtpNodePluginService : Service() {
    private val binder =
        object : INodePluginService.Stub() {
            override fun getPluginApiVersion(): Int = NodePluginManifest.CURRENT_API_VERSION

            override fun getManifests(): List<NodePluginManifest> = SmtpPluginModule.getManifests()

            override fun executeNode(
                nodeId: String,
                configJson: String,
                inputContextJson: String,
            ): String =
                runBlocking(Dispatchers.IO) {
                    if (!isTrustedCaller()) {
                        return@runBlocking failNodeExecution("Unauthorized caller")
                    }
                    try {
                        val node =
                            WorkflowNode(
                                id = nodeId,
                                name = "SMTP Email Action",
                                type = SmtpPluginModule.TYPE_ID_SMTP_ACTION,
                                configJson = configJson,
                            )
                        val inputMap = mutableMapOf<String, Any>()
                        val inputObj = parseJsonObjectSafe(inputContextJson)
                        for ((k, v) in inputObj) {
                            inputMap[k] = v.optString()
                        }
                        val context = ExecutionContext(inputMap)
                        val executor = SmtpActionExecutor()
                        val result = executor.execute(node, context, this@SmtpNodePluginService)
                        when (result) {
                            is NodeExecutionResult.Success ->
                                buildJsonObject {
                                    put("isSuccess", true)
                                    put("outputPort", result.outputPort)
                                    put("logMessage", result.logMessage)
                                }.toString()
                            is NodeExecutionResult.Failed ->
                                buildJsonObject {
                                    put("isSuccess", false)
                                    put("errorReason", result.reason)
                                }.toString()
                            is NodeExecutionResult.Ignored ->
                                buildJsonObject {
                                    put("isSuccess", false)
                                    put("isIgnored", true)
                                    put("errorReason", result.reason)
                                }.toString()
                        }
                    } catch (e: SerializationException) {
                        failNodeExecution(e.message ?: "JSON parsing failed")
                    } catch (e: SmtpException) {
                        failNodeExecution(e.message ?: "SMTP execution failed")
                    } catch (e: IOException) {
                        failNodeExecution(e.message ?: "Network execution failed")
                    } catch (e: IllegalArgumentException) {
                        failNodeExecution(e.message ?: "Invalid argument")
                    } catch (e: IllegalStateException) {
                        failNodeExecution(e.message ?: "Illegal state")
                    }
                }
        }

    private fun isTrustedCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val myUid = Process.myUid()
        if (callingUid == myUid) return true
        return packageManager.checkSignatures(myUid, callingUid) == PackageManager.SIGNATURE_MATCH
    }

    private fun failNodeExecution(reason: String): String =
        buildJsonObject {
            put("isSuccess", false)
            put("errorReason", reason)
        }.toString()

    override fun onBind(intent: Intent?): IBinder = binder
}
