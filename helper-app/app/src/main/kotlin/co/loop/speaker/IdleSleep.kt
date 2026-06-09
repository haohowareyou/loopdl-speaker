package co.loop.speaker

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Two-stage auto-sleep:
 *   1. After IDLE_SLEEP_MIN minutes of inactivity (no music, no A2DP connection) →
 *      suspend via `input keyevent 223` (KEYCODE_SLEEP).
 *   2. After IDLE_OFF_MIN minutes → power off via `svc power shutdown`.
 *
 * Activity sources that call poke():
 *   - A2DP connect/disconnect broadcasts (LoopService)
 *   - Daemon button broadcasts
 *
 * Never fires while AudioManager.isMusicActive() is true.
 */
class IdleSleep(val ctx: Context, val sleepMin: Int, val offMin: Int) {
    private val h = Handler(Looper.getMainLooper())
    private var last = SystemClock.elapsedRealtime()
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var running = false
    private var sleeping = false

    fun poke() {
        last = SystemClock.elapsedRealtime()
        sleeping = false
    }

    fun start() {
        if (running) return
        running = true
        poke()
        Log.i("LoopSpk", "idle-sleep started sleep=${sleepMin}m off=${offMin}m")
        h.post(ticker)
    }

    fun stop() {
        running = false
        h.removeCallbacks(ticker)
        Log.i("LoopSpk", "idle-sleep stopped")
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val idleMin = (SystemClock.elapsedRealtime() - last) / 60_000
            if (am.isMusicActive) {
                // Music is playing — reset idle clock and stay awake
                poke()
            } else {
                when {
                    idleMin >= offMin -> {
                        Log.i("LoopSpk", "idle->poweroff idleMin=$idleMin")
                        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "svc power shutdown")) }
                        catch (e: Exception) { Log.e("LoopSpk", "poweroff", e) }
                    }
                    idleMin >= sleepMin -> {
                        if (!sleeping) {
                            Log.i("LoopSpk", "idle->sleep idleMin=$idleMin")
                            try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "223")) } // KEYCODE_SLEEP
                            catch (e: Exception) { Log.e("LoopSpk", "sleep", e) }
                            sleeping = true
                        }
                    }
                    else -> Log.i("LoopSpk", "idle check: idleMin=$idleMin (sleep@${sleepMin} off@${offMin})")
                }
            }
            h.postDelayed(this, 30_000)
        }
    }
}
