package io.github.haohowareyou.loopdl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives `am broadcast -a io.github.haohowareyou.loopdl.CMD --es cmd <cmd> [--es arg <arg>]`
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
