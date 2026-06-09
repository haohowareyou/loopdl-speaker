package co.loop.speaker

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Speaker-style pairing.
 *
 * Two decoupled concerns:
 *  - auto-accept: while enabled (the whole time we're in dumb mode), ANY incoming
 *    pairing request is confirmed automatically and the system dialog is aborted,
 *    so pairing a phone never shows a code or lights the screen. No timed window
 *    or gesture is required — a real BT speaker is always willing to pair.
 *  - discoverable: the adapter is only made discoverable when nothing is connected
 *    (on entering dumb and on disconnect); once a phone connects we drop back to
 *    connectable-only. The phone needs us discoverable to *find* us, but auto-accept
 *    is what makes the pairing itself silent.
 *
 * setPairingConfirmation / setScanMode / setDiscoverableTimeout are hidden APIs
 * reached by reflection (BLUETOOTH_PRIVILEGED is granted via the privapp allowlist).
 *
 * BT scan-mode constants (BluetoothAdapter, hidden):
 *   SCAN_MODE_CONNECTABLE              = 21
 *   SCAN_MODE_CONNECTABLE_DISCOVERABLE = 23
 */
class Pairing(val ctx: Context) {
    private var autoAccept = false
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothDevice.ACTION_PAIRING_REQUEST || !autoAccept) return
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            try {
                BluetoothDevice::class.java
                    .getMethod("setPairingConfirmation", Boolean::class.java)
                    .invoke(d, true)
                Log.i("LoopSpk", "auto-accepted pairing from ...${d?.address?.takeLast(5)}")
                // ACTION_PAIRING_REQUEST is an ordered broadcast; aborting it from our
                // higher-priority receiver stops the system BluetoothPairingDialog (and
                // its screen wake) from ever appearing -> truly silent pairing.
                try { abortBroadcast() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e("LoopSpk", "accept pairing", e)
            }
        }
    }

    /** Arm silent auto-accept for the whole dumb session. Idempotent. */
    fun enableAutoAccept() {
        autoAccept = true
        if (!registered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
            ctx.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            registered = true
        }
        Log.i("LoopSpk", "pairing auto-accept ON")
    }

    /** Disarm auto-accept and stop being discoverable (entering full mode). */
    fun disableAutoAccept() {
        autoAccept = false
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
            registered = false
        }
        setDiscoverable(false)
        Log.i("LoopSpk", "pairing auto-accept OFF")
    }

    /** Become discoverable for `seconds` so a phone can find us. Auto-accept must
     *  already be armed (enableAutoAccept) for the pairing itself to be silent. */
    fun open(seconds: Int) {
        if (!registered) enableAutoAccept()
        setDiscoverable(true)
        Log.i("LoopSpk", "discoverable ${seconds}s")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ setDiscoverable(false) }, seconds * 1000L)
    }

    /** A phone connected: stop advertising, but keep auto-accept armed for re-pairs. */
    fun close() {
        handler.removeCallbacksAndMessages(null)
        setDiscoverable(false)
        Log.i("LoopSpk", "discoverable off")
    }

    private fun setDiscoverable(on: Boolean) {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            if (on) {
                // 0 = no timeout on most builds; we also reassert on disconnect.
                try {
                    BluetoothAdapter::class.java
                        .getMethod("setDiscoverableTimeout", Int::class.java)
                        .invoke(a, 0)
                } catch (_: Exception) {}
            }
            BluetoothAdapter::class.java
                .getMethod("setScanMode", Int::class.java)
                .invoke(a, if (on) 23 else 21)
            Log.i("LoopSpk", "scanMode=${if (on) 23 else 21}")
        } catch (e: Exception) {
            Log.e("LoopSpk", "scanmode", e)
        }
    }
}
