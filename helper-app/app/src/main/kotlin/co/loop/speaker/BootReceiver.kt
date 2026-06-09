package co.loop.speaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts LoopService on BOOT_COMPLETED and triggers mode_dumb to kick off
 * the reconnect/pairing path and idle-sleep timer.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            val s = Intent(c, LoopService::class.java).putExtra("cmd", "mode_dumb")
            c.startForegroundService(s)
        }
    }
}
