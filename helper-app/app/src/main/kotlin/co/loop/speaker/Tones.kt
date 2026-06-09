package co.loop.speaker

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny earcon synthesizer. The speaker has no screen, so a handful of short musical tones
 * ARE the entire feedback channel — they replace the old spoken cues ("Pairing"/"Connected"
 * /"Disconnected"/...). The vocabulary is deliberately small and consonant so it's learnable
 * and never harsh:
 *
 *   wake()         single warm note       — a button press told you "I'm on"
 *   pairing()      slow rising two-note    — discoverable, waiting for a phone
 *   connected()    rising major triad      — a phone linked (resolved / "good")
 *   disconnected() falling two-note         — the phone dropped
 *   speaker()/full() low two-note pair      — mode switch
 *
 * Each note is a sine fundamental plus a quiet octave, shaped by a short raised-cosine attack
 * and an exponential decay, so it reads as a mellow bell/marimba pip rather than a flat beep.
 *
 * Played as USAGE_ALARM so it passes DND's alarms-only filter and mixes OVER the A2DP-sink
 * stream / through doze — the same routing the old TTS cues relied on. [gain] caps how loud
 * the synthesized tone itself can be, independent of the system volume slider; it's the one
 * knob to make the whole set gentler or stronger.
 *
 * Calls are serialized on a single worker thread, so back-to-back earcons (disconnected then
 * pairing) play in order instead of muddily overlapping. A ~400ms amp pre-roll is inserted
 * only when it's been a few seconds since the last tone (cold amp after idle); warm, rapid
 * presses stay snappy.
 */
class Tones(private val warmAmp: () -> Unit) {
    private val sr = 44100
    // Master loudness of the rendered PCM, i.e. peak amplitude as a fraction of full scale —
    // effectively "what % of full volume" every earcon plays at (the volume-step tick is the
    // only cue that instead scales with the media bar). ~0.15 = a soft, unobtrusive level:
    // present but never a blast. This is THE knob to make the whole set louder/quieter.
    private val gain = 0.15

    // Frequencies (Hz). Major-pentatonic so any combination stays consonant. Kept in the
    // warm 330–660 mid: low enough to read as a mellow "chime" rather than a sharp pip, but
    // not so low the phone's tiny speaker (weak below ~330Hz) can't reproduce it.
    private object F {
        const val E4 = 329.63; const val G4 = 392.0; const val A4 = 440.0; const val C5 = 523.25
        const val D5 = 587.33; const val E5 = 659.25
    }

    // (frequency, note length ms, trailing silence ms)
    private data class Note(val f: Double, val ms: Int, val gap: Int = 40)

    private val WAKE         = listOf(Note(F.G4, 210, 0))
    private val PAIRING      = listOf(Note(F.A4, 240, 90), Note(F.D5, 320, 0))
    private val CONNECTED    = listOf(Note(F.G4, 150, 30), Note(F.C5, 150, 30), Note(F.E5, 280, 0))
    private val DISCONNECTED = listOf(Note(F.D5, 200, 55), Note(F.G4, 320, 0))
    private val SPEAKER      = listOf(Note(F.C5, 160, 45), Note(F.G4, 260, 0))
    private val FULL         = listOf(Note(F.G4, 160, 45), Note(F.C5, 260, 0))
    // Two equal pips = "you hit the limit" (volume rail). Flat/repeated, so it never reads
    // as one of the rising/falling musical state cues.
    private val EDGE         = listOf(Note(F.A4, 90, 55), Note(F.A4, 90, 0))
    // Soft descending 3-note = "winding down" before the idle auto-off fires.
    private val IDLE_WARN    = listOf(Note(F.C5, 170, 50), Note(F.A4, 170, 50), Note(F.G4, 300, 0))
    // Descending double-chirp = low-battery alert (a repeated drop reads as "attention",
    // distinct from the single smooth idle/disconnect descents).
    private val LOW_BATT     = listOf(
        Note(F.E5, 120, 30), Note(F.C5, 210, 120),
        Note(F.E5, 120, 30), Note(F.C5, 240, 0))
    // Same chirp, a third time = more insistent at the 10% threshold.
    private val LOW_BATT_URGENT = listOf(
        Note(F.E5, 120, 30), Note(F.C5, 210, 110),
        Note(F.E5, 120, 30), Note(F.C5, 210, 110),
        Note(F.E5, 120, 30), Note(F.C5, 260, 0))
    // Slow final descent into the low register = "shutting down".
    private val POWER_OFF    = listOf(Note(F.C5, 200, 60), Note(F.G4, 220, 60), Note(F.E4, 420, 0))

    fun wake()            = enqueue(WAKE)
    fun pairing()         = enqueue(PAIRING)
    fun connected()       = enqueue(CONNECTED)
    fun disconnected()    = enqueue(DISCONNECTED)
    fun speaker()         = enqueue(SPEAKER)
    fun full()            = enqueue(FULL)
    fun edge()            = enqueue(EDGE)
    fun idleWarn()        = enqueue(IDLE_WARN)
    fun lowBattery()      = enqueue(LOW_BATT)
    fun lowBatteryUrgent()= enqueue(LOW_BATT_URGENT)
    fun powerOff()        = enqueue(POWER_OFF)

    private val queue = LinkedBlockingQueue<List<Note>>()
    @Volatile private var started = false
    @Volatile private var lastEnd = 0L

    private fun enqueue(seq: List<Note>) {
        warmAmp()          // start the amp warming now; the pre-roll below covers cold starts
        ensureWorker()
        queue.offer(seq)
    }

    @Synchronized
    private fun ensureWorker() {
        if (started) return
        started = true
        Thread {
            while (true) {
                val seq = try { queue.take() } catch (_: InterruptedException) { continue }
                try {
                    if (SystemClock.uptimeMillis() - lastEnd > 3000) Thread.sleep(400)
                    playBlocking(seq)
                } catch (e: Exception) {
                    Log.e("LoopSpk", "tone", e)
                } finally {
                    lastEnd = SystemClock.uptimeMillis()
                }
            }
        }.apply { isDaemon = true; name = "loop-tones" }.start()
    }

    private fun playBlocking(seq: List<Note>) {
        val pcm = render(seq)
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            pcm.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(pcm.size * 1000L / sr + 120)   // let it drain before release
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }

    private fun render(seq: List<Note>): ShortArray {
        var total = 0
        for (n in seq) total += (n.ms + n.gap) * sr / 1000
        val out = ShortArray(total)
        var idx = 0
        for (n in seq) {
            val len = n.ms * sr / 1000
            val atk = (0.018 * sr).toInt()                 // 18ms raised-cosine attack (soft onset)
            for (i in 0 until len) {
                val t = i.toDouble() / sr
                val env = (if (i < atk) 0.5 * (1 - cos(PI * i / atk)) else 1.0) *
                          exp(-2.4 * i / len)              // gentle bell-like decay (rings a touch)
                // Mostly fundamental with only a faint octave for body — keeps it warm, not
                // bright/sharp (the louder octave was what gave the old tone its edge).
                val s = sin(2 * PI * n.f * t) + 0.10 * sin(2 * PI * 2 * n.f * t)
                out[idx++] = (s / 1.10 * env * gain * Short.MAX_VALUE).toInt().toShort()
            }
            idx += n.gap * sr / 1000                        // trailing silence (already zero)
        }
        return out
    }
}
