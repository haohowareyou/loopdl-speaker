package io.github.haohowareyou.loopdl

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Bounded amp keep-alive.
 *
 * The speaker amp suspends a second or two after audio stops; a TTS cue that lands on a
 * cold amp gets clipped to silence. We can't keep the amp warm 24/7 -- a continuously
 * active audio stream blocks deep doze and drains the battery. So this is only run for
 * the brief windows where a cue is likely on a cold amp: while a pairing window is open
 * (the disconnect/"Pairing"/"Connected" cues fire then). It auto-stops the instant a
 * phone connects (amp is warm from music) or the window closes.
 *
 * Implementation: an AudioTrack streaming PCM zeros (true silence) tagged USAGE_ALARM so
 * it shares the exact output route the cues use, holding the amp awake without any
 * audible sound.
 */
class AudioKeepAlive {
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        try {
            val rate = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            t.play()
            val silence = ShortArray(minBuf / 2) // zeros = silence
            thread = Thread {
                while (running) {
                    try {
                        if (t.write(silence, 0, silence.size) < 0) break
                    } catch (_: Exception) { break }
                }
            }.apply { isDaemon = true; start() }
            Log.i("LoopSpk", "keepalive ON")
        } catch (e: Exception) {
            Log.e("LoopSpk", "keepalive start", e)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { thread?.join(200) } catch (_: Exception) {}
        thread = null
        try { track?.pause(); track?.flush(); track?.stop(); track?.release() } catch (_: Exception) {}
        track = null
        Log.i("LoopSpk", "keepalive OFF")
    }
}
