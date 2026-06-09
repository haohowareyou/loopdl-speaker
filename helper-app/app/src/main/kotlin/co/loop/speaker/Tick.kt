package co.loop.speaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Audible volume feedback for screen-off (dumb) mode. The daemon re-injects the
 * volume key, the framework fires VOLUME_CHANGED_ACTION, and we play a short beep
 * through the speaker so each step (and each hold-to-ramp step) is confirmed.
 *
 * The beep loudness tracks the current media volume (quiet when low, louder when
 * high), bucketed to tens so a continuous ramp doesn't rebuild the ToneGenerator
 * on every single step.
 */
class Tick(val ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var registered = false
    private var tg: ToneGenerator? = null
    private var lastBucket = -1

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != "android.media.VOLUME_CHANGED_ACTION") return
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                != AudioManager.STREAM_MUSIC) return
            val v = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (v < 0 || v == prev) return
            play(v)
        }
    }

    private fun play(v: Int) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val bucket = ((v * 100 / max) / 10 * 10).coerceIn(10, 100)
        try {
            if (bucket != lastBucket || tg == null) {
                tg?.release()
                tg = ToneGenerator(AudioManager.STREAM_MUSIC, bucket)
                lastBucket = bucket
            }
            tg?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
        } catch (e: Exception) {
            Log.e("LoopSpk", "tick", e)
        }
    }

    fun start() {
        if (registered) return
        ctx.registerReceiver(
            rx, IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            Context.RECEIVER_EXPORTED
        )
        registered = true
        Log.i("LoopSpk", "tick on")
    }

    fun stop() {
        if (registered) {
            try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
            registered = false
        }
        tg?.release(); tg = null; lastBucket = -1
        Log.i("LoopSpk", "tick off")
    }
}
