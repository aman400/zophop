package dev.zophop

import android.app.Application
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.plugin.call.CallPluginModule
import dev.zophop.plugin.gotify.GotifyPluginModule
import dev.zophop.plugin.gps.GpsPluginModule
import dev.zophop.plugin.notification.NotificationPluginModule
import dev.zophop.plugin.ntfy.NtfyPluginModule
import dev.zophop.plugin.sms.SmsPluginModule
import dev.zophop.service.SmsForwarderService

class ZopHopApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Register bundled plugins into engine registry at app startup
        BuiltInPluginRegistry.registerModule(SmsPluginModule)
        BuiltInPluginRegistry.registerModule(NotificationPluginModule)
        BuiltInPluginRegistry.registerModule(NtfyPluginModule)
        BuiltInPluginRegistry.registerModule(GotifyPluginModule)
        BuiltInPluginRegistry.registerModule(CallPluginModule)
        BuiltInPluginRegistry.registerModule(GpsPluginModule)

        val settingsRepo = EncryptedSettingsRepository(this)
        val settings = settingsRepo.getSettings()

        if (settings.isEnabled) {
            SmsForwarderService.startService(this)
        }
    }
}
