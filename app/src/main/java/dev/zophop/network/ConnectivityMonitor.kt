package dev.zophop.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dev.zophop.worker.ForwardingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "ConnectivityMonitor"

class ConnectivityMonitor(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        scope.launch {
            try {
                ForwardingManager.getInstance(context).processPendingQueue()
                dev.zophop.worker.WorkflowQueueManager.processQueuedWorkflows(context)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Error processing queue on connectivity change: ${e.message}", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception processing queue: ${e.message}", e)
            }
        }
    }

    fun register() {
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied registering network callback: ${e.message}", e)
        }
    }

    fun unregister() {
        scope.cancel()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to unregister network callback: ${e.message}", e)
        }
    }
}
