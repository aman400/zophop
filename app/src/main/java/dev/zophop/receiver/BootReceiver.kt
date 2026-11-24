package dev.zophop.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.service.SmsForwarderService
import dev.zophop.worker.ForwardingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val settingsRepo = EncryptedSettingsRepository(context)
            val settings = settingsRepo.getSettings()

            if (settings.isEnabled) {
                SmsForwarderService.startService(context)

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val manager = ForwardingManager.getInstance(context)
                        manager.scheduleWorkManagerRetry()
                        manager.processPendingQueue()
                    } finally {
                        pendingResult?.finish()
                    }
                }
            }
        }
    }
}
