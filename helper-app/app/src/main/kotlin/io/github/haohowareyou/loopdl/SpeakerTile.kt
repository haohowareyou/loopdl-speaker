package io.github.haohowareyou.loopdl

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * "Speaker Mode" Quick-Settings tile — the full→dumb return path.
 *
 * Tapping it opens a confirmation dialog (ConfirmSpeakerActivity) rather than
 * switching immediately, so a stray tap can't drop the phone into dumb mode. The
 * confirm step writes the req_dumb trigger file that the root IPC poller picks up.
 */
class SpeakerTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Speaker Mode"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, ConfirmSpeakerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pi)
    }
}
