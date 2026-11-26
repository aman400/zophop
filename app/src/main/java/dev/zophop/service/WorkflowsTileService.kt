package dev.zophop.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.zophop.data.SettingsRepository
import javax.inject.Inject

@AndroidEntryPoint
class WorkflowsTileService : TileService() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val settings = settingsRepository.getSettings()
        val newState = !settings.globalWorkflowsEnabled
        settingsRepository.saveSettings(settings.copy(globalWorkflowsEnabled = newState))
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = settingsRepository.getSettings().globalWorkflowsEnabled

        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "SMS Automations"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (isEnabled) "Active" else "Paused"
        }
        tile.updateTile()
    }
}
