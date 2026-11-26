package dev.zophop.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zophop.worker.ForwardingManager
import dev.zophop.worker.WorkflowQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ConnectivityMonitor"

@Suppress("ktlint:standard:annotation")
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val forwardingManager: ForwardingManager,
    private val workflowQueueManager: WorkflowQueueManager,
) {
    private var scope: CoroutineScope? = null
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                triggerFlush()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                triggerFlush()
            }
        }

    private fun triggerFlush() {
        scope?.launch {
            try {
                forwardingManager.processPendingQueue()
                workflowQueueManager.processQueuedWorkflows()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Error processing queue on connectivity change: ${e.message}", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception processing queue: ${e.message}", e)
            }
        }
    }

    fun register() {
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied registering network callback: ${e.message}", e)
        }
    }

    fun unregister() {
        scope?.cancel()
        scope = null
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to unregister network callback: ${e.message}", e)
        }
    }
}
