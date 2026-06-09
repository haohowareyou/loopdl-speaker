package co.loop.speaker

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Two-stage auto-sleep:
 *   1. After IDLE_SLEEP_MIN minutes of inactivity (no music, no A2DP connection) →
 *      blank the panel (KEYCODE_SLEEP).
 *   2. After IDLE_OFF_MIN minutes → power off.
 *
 * The app is unprivileged: `input keyevent` / `svc power shutdown` fail from the app
 * uid (no permission, no su). Instead we drop a trigger file in filesDir; the root
 * IPC poller (loop-ipc.sh) executes the actual keyevent/shutdown as root.
 *
 * Activity sources that call poke():
 *   - A2DP connect/disconnect broadcasts (LoopService)
 *   - Daemon button broadcasts
 *
 * Never fires while AudioManager.isMusicActive() is true.
 */
class IdleSleep(
    val ctx: Context,
    val sleepMin: Int,
    val offMin: Int,
    val onPreOff: () -> Unit = {},
) {
    private val h = Handler(Looper.getMainLooper())
    private var last = SystemClock.elapsedRealtime()
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var running = false
    private var sleeping = false
    private var preOffWarned = false

    fun poke() {
        last = SystemClock.elapsedRealtime()
        sleeping = false
        preOffWarned = false
    }

    /** Drop a trigger file in filesDir for the root IPC poller (loop-ipc.sh) to execute. */
    private fun signalRoot(name: String) {
        try {
            File(ctx.filesDir, name).writeText("1")
        } catch (e: Exception) {
            Log.e("LoopSpk", "signalRoot $name failed", e)
        }
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
                        signalRoot("req_poweroff")
                    }
                    idleMin >= offMin - 1 && !preOffWarned -> {
                        // ~1 min before auto-off: warn so a button tap (which poke()s) can
                        // keep the device alive instead of it powering off silently.
                        Log.i("LoopSpk", "idle->preoff-warn idleMin=$idleMin")
                        onPreOff()
                        preOffWarned = true
                    }
                    idleMin >= sleepMin -> {
                        if (!sleeping) {
                            Log.i("LoopSpk", "idle->sleep idleMin=$idleMin")
                            signalRoot("req_sleep")
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
