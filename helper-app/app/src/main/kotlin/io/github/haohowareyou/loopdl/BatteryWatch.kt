package io.github.haohowareyou.loopdl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Proactive battery safety for the screenless speaker. Premium speakers warn before they die
 * rather than cutting out mid-track; we do the same with earcons:
 *
 *   <= WARN1_PCT (20%)  → gentle low-battery chirp (once)
 *   <= WARN2_PCT (10%)  → more insistent chirp (once)
 *   <= OFF_PCT    (5%)  → power-off motif, then a graceful shutdown after a short grace
 *
 * Each threshold latches so it fires once per discharge; charging (or the level climbing back
 * above a threshold) re-arms it. alertIfLow() re-plays the appropriate warning on connect, so
 * you hear "this thing is nearly empty" right when you start a session.
 *
 * Level comes from the sticky ACTION_BATTERY_CHANGED broadcast (no polling). Shutdown reuses
 * the same root IPC trigger file as IdleSleep -- the unprivileged app can't power off itself.
 */
class BatteryWatch(val ctx: Context, val tones: Tones) {
    companion object {
        private const val WARN1_PCT = 20
        private const val WARN2_PCT = 10
        private const val OFF_PCT = 5
        private const val SHUTDOWN_GRACE_MS = 3500L
    }

    private val h = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastPct = 100
    private var charging = false
    private var warned1 = false
    private var warned2 = false
    private var shuttingDown = false

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update(i)
    }

    fun start() {
        if (registered) return
        // ACTION_BATTERY_CHANGED is sticky: registerReceiver returns the current battery
        // intent immediately, so we evaluate the level right away.
        val sticky = ctx.registerReceiver(rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registered = true
        sticky?.let { update(it) }
        Log.i("LoopSpk", "battery watch on (pct=$lastPct)")
    }

    fun stop() {
        if (registered) {
            try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
            registered = false
        }
    }

    /** On connect, re-surface the current low-battery state (if any) so a session starts with
     *  fair warning. Queued behind the "Connected" earcon by the Tones serializer. */
    fun alertIfLow() {
        if (charging) return
        when {
            lastPct <= WARN2_PCT -> tones.lowBatteryUrgent()
            lastPct <= WARN1_PCT -> tones.lowBattery()
        }
    }

    private fun update(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        lastPct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                   status == BatteryManager.BATTERY_STATUS_FULL

        // Re-arm latches once plugged in or recovered above a threshold (so a later drain
        // warns again). +1 hysteresis avoids re-arming/re-firing while hovering on the line.
        if (charging || lastPct > WARN1_PCT + 1) warned1 = false
        if (charging || lastPct > WARN2_PCT + 1) warned2 = false
        if (charging || lastPct > OFF_PCT + 1) shuttingDown = false
        if (charging) return

        when {
            lastPct <= OFF_PCT && !shuttingDown -> {
                shuttingDown = true
                Log.i("LoopSpk", "battery $lastPct% -> graceful shutdown")
                tones.powerOff()
                h.postDelayed({ requestPowerOff() }, SHUTDOWN_GRACE_MS)
            }
            lastPct <= WARN2_PCT && !warned2 -> {
                warned2 = true
                Log.i("LoopSpk", "battery $lastPct% -> warn(10)")
                tones.lowBatteryUrgent()
            }
            lastPct <= WARN1_PCT && !warned1 -> {
                warned1 = true
                Log.i("LoopSpk", "battery $lastPct% -> warn(20)")
                tones.lowBattery()
            }
        }
    }

    /** Drop the same root IPC trigger IdleSleep uses; loop-ipc.sh runs the actual shutdown. */
    private fun requestPowerOff() {
        try {
            File(ctx.filesDir, "req_poweroff").writeText("1")
        } catch (e: Exception) {
            Log.e("LoopSpk", "battery requestPowerOff failed", e)
        }
    }
}
