package co.loop.speaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives `am broadcast -a co.loop.speaker.CMD --es cmd <cmd> [--es arg <arg>]`
 * and forwards it to LoopService as a startForegroundService call.
 */
class CmdReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = Intent(c, LoopService::class.java)
            .putExtra("cmd", i.getStringExtra("cmd"))
            .putExtra("arg", i.getStringExtra("arg"))
        c.startForegroundService(s)
    }
}
