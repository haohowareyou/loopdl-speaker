package co.loop.speaker

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Bridge fallback: observes local STREAM_MUSIC changes and forwards the new level
 * to the connected source phone as an AVRCP SetAbsoluteVolume command.
 *
 * Use only if the native absolute-volume sync (persist.bluetooth.disableabsvol=false)
 * is one-directional. Call start() only for the missing direction; leave dormant if
 * both directions sync natively.
 */
class Volume(val ctx: Context, val avrcp: Avrcp) {
    private val am = ctx.getSystemService(AudioManager::class.java)

    private val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return
            val abs = (cur * 127) / max
            avrcp.setAbsoluteVolume(abs)
            Log.i("LoopSpk", "vol bridge -> $abs/127 (stream $cur/$max)")
        }
    }

    fun start() {
        ctx.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, obs
        )
        Log.i("LoopSpk", "vol bridge started")
    }

    fun stop() {
        ctx.contentResolver.unregisterContentObserver(obs)
        Log.i("LoopSpk", "vol bridge stopped")
    }
}
