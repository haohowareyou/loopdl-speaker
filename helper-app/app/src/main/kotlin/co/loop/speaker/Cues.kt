package co.loop.speaker

import android.content.Context
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * TTS cue announcements at CUE_VOLUME_PCT of full volume.
 * Uses Android's built-in TextToSpeech engine (no network required).
 */
class Cues(val ctx: Context, val volPct: Int) {
    private var tts: TextToSpeech? = null
    private var ready = false

    fun init() {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ready = true
                Log.i("LoopSpk", "tts ready, volPct=$volPct")
            } else {
                Log.e("LoopSpk", "tts init failed status=$status")
            }
        }
    }

    fun say(text: String) {
        if (!ready) { Log.i("LoopSpk", "tts not ready, skipping: $text"); return }
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volPct / 100f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "loop")
        Log.i("LoopSpk", "tts: $text @ $volPct%")
    }

    fun battery() {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        say("Battery $pct percent")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
