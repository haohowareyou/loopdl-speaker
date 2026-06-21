package io.github.haohowareyou.loopdl

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import java.io.File

/**
 * Quick Settings tile — toggle mobile data (5G). The Loop is used as a connected speaker /
 * mobile hotspot, not a phone, so data is normally off; this is the fast "turn the internet
 * on" switch in full mode.
 *
 * The app is unprivileged and can't flip the radio itself, so it drops a trigger file that the
 * root IPC poller (loop-ipc.sh) runs `svc data enable|disable` on — the same pattern the QS
 * speaker tile and idle-sleep use. Tile state mirrors Settings.Global mobile_data.
 */
class DataTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val on = dataOn()
        trigger(if (on) "req_data_off" else "req_data_on")
        setTile(!on)   // optimistic; onStartListening re-syncs when the panel reopens
    }

    private fun dataOn() = Settings.Global.getInt(contentResolver, "mobile_data", 0) == 1

    private fun trigger(name: String) = try {
        File(filesDir, name).writeText("1")
    } catch (e: Exception) { Log.e("LoopSpk", "data trigger", e) }

    private fun refresh() = setTile(dataOn())

    private fun setTile(on: Boolean) {
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Mobile data"
            subtitle = if (on) "On" else "Off"
            updateTile()
        }
    }
}
