package dev.zophop

import android.app.Application
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZopHopApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Warm bundled plugin registry off the main thread to avoid startup ANR/disk stalls.
        // Foreground service startup is handled when entering foreground (MainActivity / toggle).
        appScope.launch {
            BuiltInPluginRegistry.discoverBundledModules()
        }
    }
}
