package dev.zophop.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.zophop.data.EncryptedSettingsRepository

class WorkflowsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val repo = EncryptedSettingsRepository(applicationContext)
        val settings = repo.getSettings()
        val newState = !settings.globalWorkflowsEnabled
        repo.saveSettings(settings.copy(globalWorkflowsEnabled = newState))
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val repo = EncryptedSettingsRepository(applicationContext)
        val isEnabled = repo.getSettings().globalWorkflowsEnabled

        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "SMS Automations"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (isEnabled) "Active" else "Paused"
        }
        tile.updateTile()
    }
}
