package com.jarvis.assistant.assist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jarvis.assistant.R
import com.jarvis.assistant.platform.Intents
import com.jarvis.assistant.ui.AssistantActivity

/**
 * Quick Settings tile: the same compact chat as the assistant gesture, for phones or users where the gesture is not JARVIS's. On a locked phone with a secure
 * lock, Android itself asks for the PIN before running the tile, and the compact chat then hands over to the lock-screen assistant if the phone is still locked.
 */
class JarvisTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(R.string.assist_tile_label); updateTile() }
    }

    override fun onClick() {
        val intent = Intent().setClassName(packageName, Intents.ASSISTANT_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AssistantActivity.EXTRA_LISTEN, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
