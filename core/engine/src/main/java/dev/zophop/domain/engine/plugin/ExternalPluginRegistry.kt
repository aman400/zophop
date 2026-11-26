package dev.zophop.domain.engine.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.BadParcelableException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodePluginManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class ExternalPluginServiceInfo(
    val packageName: String,
    val serviceClassName: String,
    val manifest: NodePluginManifest,
    val apiVersion: Int,
)

open class ExternalPluginRegistry(
    private val context: Context?,
) {
    companion object {
        const val ACTION_NODE_SERVICE = "dev.zophop.plugin.ACTION_NODE_SERVICE"
        private const val DISCOVERY_TIMEOUT_MS = 5_000L
        private const val TAG = "ExternalPluginRegistry"
    }

    private val discoveredPlugins = ConcurrentHashMap<String, ExternalPluginServiceInfo>()

    open fun getDiscoveredPlugins(): List<ExternalPluginServiceInfo> = discoveredPlugins.values.toList()

    open fun getPluginByTypeId(typeId: String): ExternalPluginServiceInfo? {
        val plugin = discoveredPlugins[typeId] ?: return null
        val ctx = context ?: return plugin
        val disabledPluginsStr =
            ctx
                .getSharedPreferences("zophop_encrypted_prefs", Context.MODE_PRIVATE)
                .getString("disabled_plugin_packages", "") ?: ""
        val disabledPackages = disabledPluginsStr.split(",").map { it.trim() }.toSet()
        if (disabledPackages.contains(plugin.packageName)) return null
        return plugin
    }

    open suspend fun discoverPlugins(): List<ExternalPluginServiceInfo> =
        withContext(Dispatchers.IO) {
            val ctx = context ?: return@withContext emptyList()
            val intent = Intent(ACTION_NODE_SERVICE)
            val resolveInfos = ctx.packageManager.queryIntentServices(intent, 0)

            val disabledPluginsStr =
                ctx
                    .getSharedPreferences("zophop_encrypted_prefs", Context.MODE_PRIVATE)
                    .getString("disabled_plugin_packages", "") ?: ""
            val disabledPackages = disabledPluginsStr.split(",").map { it.trim() }.toSet()

            val newMap = ConcurrentHashMap<String, ExternalPluginServiceInfo>()
            val validServices = resolveInfos.mapNotNull { it.serviceInfo }
                .filter { it.packageName != ctx.packageName && !disabledPackages.contains(it.packageName) }

            for (serviceInfo in validServices) {
                val componentName = ComponentName(serviceInfo.packageName, serviceInfo.name)
                val serviceIntent = Intent().apply { component = componentName }
                val pluginInfoList =
                    withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                        queryPluginServiceList(ctx, serviceIntent, serviceInfo.packageName, serviceInfo.name)
                    } ?: emptyList()

                for (pluginInfo in pluginInfoList) {
                    newMap[pluginInfo.manifest.typeId] = pluginInfo
                }
            }

            discoveredPlugins.clear()
            discoveredPlugins.putAll(newMap)
            discoveredPlugins.values.toList()
        }

    private suspend fun queryPluginServiceList(
        ctx: Context,
        intent: Intent,
        packageName: String,
        className: String,
    ): List<ExternalPluginServiceInfo> =
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
                            val apiVersion = binder.pluginApiVersion
                            val manifests = binder.manifests ?: emptyList()

                            val infoList =
                                manifests.map { manifest ->
                                    ExternalPluginServiceInfo(packageName, className, manifest, apiVersion)
                                }
                            if (continuation.isActive) continuation.resume(infoList)
                        } catch (e: RemoteException) {
                            Log.w(TAG, "RemoteException querying plugin '$packageName': ${e.message}")
                            if (continuation.isActive) continuation.resume(emptyList())
                        } catch (e: SecurityException) {
                            Log.w(TAG, "SecurityException querying plugin '$packageName': ${e.message}")
                            if (continuation.isActive) continuation.resume(emptyList())
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "IllegalStateException querying plugin '$packageName': ${e.message}")
                            if (continuation.isActive) continuation.resume(emptyList())
                        } catch (e: BadParcelableException) {
                            Log.w(
                                TAG,
                                "Skipping incompatible external plugin '$packageName': BadParcelable (${e.message})",
                            )
                            if (continuation.isActive) continuation.resume(emptyList())
                        } catch (ignored: RuntimeException) {
                            Log.w(
                                TAG,
                                "Skipping incompatible external plugin '$packageName': " +
                                    "${ignored.javaClass.simpleName} (${ignored.message})",
                            )
                            if (continuation.isActive) continuation.resume(emptyList())
                        } finally {
                            try {
                                ctx.unbindService(this)
                                isBound = false
                            } catch (_: IllegalArgumentException) {
                            }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                }

            continuation.invokeOnCancellation {
                if (isBound) {
                    try {
                        ctx.unbindService(connection)
                        isBound = false
                    } catch (_: IllegalArgumentException) {
                    } catch (_: IllegalStateException) {
                    }
                }
            }

            isBound =
                try {
                    ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (_: SecurityException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                } catch (_: IllegalStateException) {
                    false
                }

            if (!isBound && continuation.isActive) {
                continuation.resume(emptyList())
            }
        }
}
