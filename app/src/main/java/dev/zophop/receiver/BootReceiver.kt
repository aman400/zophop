package dev.zophop.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.service.SmsForwarderService
import dev.zophop.worker.ForwardingManager
import dev.zophop.worker.WorkflowQueueManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class BootReceiver : BroadcastReceiver() {
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext ?: context
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val settings = EncryptedSettingsRepository(appContext).getSettings()
                if (settings.isEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    SmsForwarderService.startService(appContext)
                }

                if (settings.isEnabled || settings.globalWorkflowsEnabled) {
                    val manager = ForwardingManager.getInstance(appContext)
                    manager.scheduleWorkManagerRetry()
                    withTimeoutOrNull(8_000.milliseconds) {
                        if (settings.isEnabled) {
                            manager.processPendingQueue()
                        }
                        if (settings.globalWorkflowsEnabled) {
                            WorkflowQueueManager.processQueuedWorkflows(appContext)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error handling BOOT_COMPLETED: ${e.message}", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
