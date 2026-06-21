package io.github.haohowareyou.loopdl

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * Confirmation shown when the user taps the "Speaker Mode" QS tile, so a stray tap
 * doesn't dump the phone into dumb mode. On confirm it drops the req_dumb trigger
 * file that the root IPC poller (loop-ipc.sh) picks up to run `loop-mode dumb`.
 * Launched via TileService.startActivityAndCollapse().
 */
class ConfirmSpeakerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle("Enter Speaker Mode?")
            .setMessage(
                "Turns this phone into a dumb Bluetooth speaker: screen off, apps " +
                "disabled, buttons control playback.\n\nHold Power + Volume-Down to return."
            )
            .setPositiveButton("Speaker Mode") { _, _ ->
                try {
                    File(filesDir, "req_dumb").writeText("1")
                    Log.i("LoopSpk", "tile: confirmed dumb mode")
                } catch (e: Exception) {
                    Log.e("LoopSpk", "tile write failed", e)
                }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
