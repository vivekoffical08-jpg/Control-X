package com.controlx.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.controlx.MainActivity

class ControlQuickSettingsTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Control X"
            subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Open assistant" else null
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_LAUNCH_OVERLAY
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAndCollapse(launchIntent)
    }
}
