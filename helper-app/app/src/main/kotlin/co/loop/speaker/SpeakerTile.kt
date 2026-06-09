package co.loop.speaker

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import java.io.File

/**
 * "Speaker Mode" Quick-Settings tile — the full→dumb return path.
 *
 * The app is unprivileged and cannot run pm/svc/loop-mode. Instead it drops a trigger
 * file in its own filesDir; the root IPC poller (loop-ipc.sh) polls for it and runs
 * `loop-mode dumb`, then deletes it. filesDir is app-private but Magisk root can read it.
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
        try {
            File(filesDir, "req_dumb").writeText("1")
            Log.i("LoopSpk", "tile: requested dumb mode")
        } catch (e: Exception) {
            Log.e("LoopSpk", "tile write failed", e)
        }
        qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
    }
}
