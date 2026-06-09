package co.loop.speaker

import android.content.Context
import android.media.AudioAttributes
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
    // Cues fired before the async TTS engine finishes initialising (the boot
    // "Speaker mode"/"Pairing" land ~0.5s before ready) are queued here and flushed
    // on init, so the speaker is never silent at boot.
    private val pending = ArrayDeque<String>()

    fun init() {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                // Route cues as ALARM audio: it plays even when the screen is off / the
                // device is dozing, and mixes OVER the incoming A2DP-sink stream instead
                // of being suppressed by it. STREAM_MUSIC cues were being silently dropped
                // at connect/disconnect (route in flux) and while idle (doze).
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ready = true
                Log.i("LoopSpk", "tts ready, volPct=$volPct")
                while (pending.isNotEmpty()) speakNow(pending.removeFirst())
            } else {
                Log.e("LoopSpk", "tts init failed status=$status")
            }
        }
    }

    fun say(text: String) {
        if (!ready) {
            pending.addLast(text)
            Log.i("LoopSpk", "tts not ready, queued: $text")
            return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volPct / 100f)
        }
        // The speaker amp suspends when idle; the first word of a cue lands on a cold amp
        // and gets clipped to nothing (only cues right after music — amp warm — were
        // audible). Open the output with ~1.5s of silence first so the amp fully powers
        // up before we speak (700ms wasn't enough — the cue still clipped). For pairing
        // cues the bounded AudioKeepAlive (LoopService) already keeps the amp warm; this
        // primer covers the one-off cases (boot "Speaker mode", battery, mode toggle).
        // Both QUEUE_ADD so order is preserved.
        tts?.playSilentUtterance(1500, TextToSpeech.QUEUE_ADD, "loop_warm")
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
