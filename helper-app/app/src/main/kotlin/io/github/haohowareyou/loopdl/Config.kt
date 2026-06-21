package io.github.haohowareyou.loopdl

import android.util.Log
import java.io.File

/**
 * Parses /data/adb/loop-speaker-mode/config (key=val lines, # comments ignored).
 * All values have safe defaults so the app runs even if the file is missing.
 */
object Config {
    private const val TAG = "LoopSpk"
    private const val PATH = "/data/adb/loop-speaker-mode/config"

    // Defaults match config.default in the magisk module. NOTE: the config file
    // lives under /data/adb (root-only, mode 700) so this unprivileged app cannot
    // read it — these compiled defaults are the effective values for the app.
    var PAIR_INITIAL: Int = 60
    var PAIR_RETRIGGER: Int = 60
    var CUE_VOLUME_PCT: Int = 30
    var IDLE_SLEEP_MIN: Int = 5
    var IDLE_OFF_MIN: Int = 15

    fun load() {
        val f = File(PATH)
        if (!f.exists()) { Log.i(TAG, "config: no file at $PATH, using defaults"); return }
        try {
            f.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                val eq = trimmed.indexOf('=')
                if (eq < 0) return@forEachLine
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                when (key) {
                    "PAIR_INITIAL"     -> PAIR_INITIAL     = value.toIntOrNull() ?: PAIR_INITIAL
                    "PAIR_RETRIGGER"   -> PAIR_RETRIGGER   = value.toIntOrNull() ?: PAIR_RETRIGGER
                    "CUE_VOLUME_PCT"   -> CUE_VOLUME_PCT   = value.toIntOrNull() ?: CUE_VOLUME_PCT
                    "IDLE_SLEEP_MIN"   -> IDLE_SLEEP_MIN   = value.toIntOrNull() ?: IDLE_SLEEP_MIN
                    "IDLE_OFF_MIN"     -> IDLE_OFF_MIN     = value.toIntOrNull() ?: IDLE_OFF_MIN
                }
            }
            Log.i(TAG, "config loaded: pair_initial=$PAIR_INITIAL pair_retrigger=$PAIR_RETRIGGER " +
                    "cue_vol=$CUE_VOLUME_PCT% idle_sleep=$IDLE_SLEEP_MIN idle_off=$IDLE_OFF_MIN")
        } catch (e: Exception) {
            Log.e(TAG, "config load error", e)
        }
    }
}
