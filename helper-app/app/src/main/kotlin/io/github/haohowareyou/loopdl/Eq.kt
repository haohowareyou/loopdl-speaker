package io.github.haohowareyou.loopdl

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Global output EQ for the speaker. Attaches an Equalizer + BassBoost to the output-mix
 * session (session 0) so it shapes ALL playback, including the A2DP-sink music stream the
 * Bluetooth service renders on its own (our app never sees that audio to EQ it directly).
 *
 * Design:
 *  - The AOSP 5-band grid (60/230/910/3600/14000 Hz, +/-15 dB) is NOT guaranteed -- an OEM can
 *    ship a different effect bundle -- so bands/centers/range are queried at runtime and the
 *    preset's per-bucket gains are mapped onto whatever bands this device exposes, clamped to
 *    its real dB range.
 *  - "More bass, not too much" on a small driver: perceived low end comes mostly from BassBoost
 *    (a psychoacoustic harmonic enhancer that fakes fundamentals the tiny driver can't physically
 *    move) plus a modest low-shelf lift, NOT from cranking the sub band, which only eats amp
 *    headroom and distorts. Boosts stay inside a safe ~+3 dB budget.
 *  - Session-0 global effects can be bypassed by direct/offload output paths on some ROMs. If so,
 *    creation still succeeds but the music won't change audibly -- the fallback is HAL-level
 *    tuning (mixer_paths.xml). Band info + enable state are logged so pass/fail is visible in
 *    logcat (`adb logcat -s LoopSpk`). EQ never crashes the service.
 *
 * Live tuning (device on adb, so we can dial this driver in by ear then bake a new preset):
 *   am broadcast -a io.github.haohowareyou.loopdl.CMD --es cmd eq      --es arg bass
 *   am broadcast -a io.github.haohowareyou.loopdl.CMD --es cmd eq_band --es arg 0:300   (band 0 = +3 dB)
 */
class Eq {
    companion object { private const val TAG = "LoopSpk" }

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var current = ""

    /** Per-frequency-bucket gain in dB, plus BassBoost strength (0-1000). */
    private data class Preset(
        val sub: Float, val warmth: Float, val mud: Float, val presence: Float, val air: Float,
        val bassStrength: Int,
    )

    private val presets = mapOf(
        "flat"  to Preset(0f, 0f, 0f, 0f, 0f, 0),
        // Default: gently bassy with a touch of clarity -- "more bass but not too much".
        "warm"  to Preset(+2f, +2f, -1.5f, +1f, +1f, 350),
        // Bass-forward, still inside the safe budget.
        "bass"  to Preset(+3f, +3f, -2f, +1f, +1.5f, 550),
        // Vocal/podcast clarity: pull the low end, push presence + air.
        "vocal" to Preset(-1f, -1.5f, -1f, +3f, +2f, 120),
    )

    fun init(preset: String) {
        try {
            // priority 10 (above a normal app's 0) so our global effect wins on the output mix.
            eq = Equalizer(10, 0).also { it.setEnabled(true) }
            bass = try { BassBoost(10, 0).also { it.setEnabled(true) } } catch (e: Exception) {
                Log.w(TAG, "eq: BassBoost unavailable: ${e.message}"); null
            }
            val e = eq!!
            val n = e.numberOfBands.toInt()
            val range = e.bandLevelRange   // [min, max] millibels
            Log.i(TAG, "eq: $n bands, range ${range[0]}..${range[1]} mB, centers=" +
                (0 until n).joinToString { "${e.getCenterFreq(it.toShort()) / 1000}Hz" })
            apply(if (presets.containsKey(preset)) preset else "warm")
        } catch (e: Exception) {
            // No global-effect support (or blocked): leave the audio flat, HAL route is the
            // fallback. Never crash the service over EQ.
            Log.w(TAG, "eq: init failed, audio left flat: ${e.message}")
            release()
        }
    }

    /** Switch preset live via `cmd eq <name>`. No-op if EQ never initialised. */
    fun apply(name: String) {
        val e = eq ?: return
        val p = presets[name] ?: run { Log.w(TAG, "eq: unknown preset '$name'"); return }
        try {
            val range = e.bandLevelRange
            for (b in 0 until e.numberOfBands.toInt()) {
                val hz = e.getCenterFreq(b.toShort()) / 1000              // milliHz -> Hz
                val mb = (gainForFreq(p, hz) * 100).toInt()
                    .coerceIn(range[0].toInt(), range[1].toInt())
                e.setBandLevel(b.toShort(), mb.toShort())
            }
            bass?.setStrength(p.bassStrength.toShort())
            current = name
            Log.i(TAG, "eq: applied '$name' (bass=${p.bassStrength})")
        } catch (ex: Exception) {
            Log.e(TAG, "eq: apply '$name'", ex)
        }
    }

    /** Raw single-band override for on-device auditioning: arg "index:millibels", e.g. "0:300". */
    fun setBand(spec: String) {
        val e = eq ?: return
        try {
            val (idx, mb) = spec.split(":").let { it[0].trim().toInt() to it[1].trim().toInt() }
            val range = e.bandLevelRange
            e.setBandLevel(idx.toShort(), mb.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            Log.i(TAG, "eq: band $idx -> $mb mB")
        } catch (ex: Exception) {
            Log.e(TAG, "eq: setBand '$spec'", ex)
        }
    }

    /** Map a real band's center frequency to the preset's bucket gain. */
    private fun gainForFreq(p: Preset, hz: Int): Float = when {
        hz < 120  -> p.sub
        hz < 500  -> p.warmth
        hz < 1800 -> p.mud
        hz < 6000 -> p.presence
        else      -> p.air
    }

    fun release() {
        try { eq?.release() } catch (_: Exception) {}
        try { bass?.release() } catch (_: Exception) {}
        eq = null; bass = null
    }
}
