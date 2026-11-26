package dev.zophop.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.zophop.data.SettingsRepository
import dev.zophop.service.SmsForwarderService
import dev.zophop.worker.ForwardingManager
import dev.zophop.worker.WorkflowQueueManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var forwardingManager: ForwardingManager

    @Inject
    lateinit var workflowQueueManager: WorkflowQueueManager

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
                val settings = settingsRepository.getSettings()
                if (settings.isEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    SmsForwarderService.startService(appContext)
                }

                if (settings.isEnabled || settings.globalWorkflowsEnabled) {
                    forwardingManager.scheduleWorkManagerRetry()
                    withTimeoutOrNull(8_000.milliseconds) {
                        if (settings.isEnabled) {
                            forwardingManager.processPendingQueue()
                        }
                        if (settings.globalWorkflowsEnabled) {
                            workflowQueueManager.processQueuedWorkflows()
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
