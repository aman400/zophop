package dev.zophop

import android.app.Application
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.service.SmsForwarderService

class ZopHopApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Discover and register all bundled plugins dynamically via bridge
        BuiltInPluginRegistry.discoverBundledModules()

        val settingsRepo = EncryptedSettingsRepository(this)
        val settings = settingsRepo.getSettings()

        if (settings.isEnabled) {
            SmsForwarderService.startService(this)
        }
    }
}
