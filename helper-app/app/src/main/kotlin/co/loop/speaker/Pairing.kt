package co.loop.speaker

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Opens a timed discoverable window and auto-accepts incoming pairing requests.
 * setPairingConfirmation is a privileged/hidden API called via reflection.
 * setScanMode is also hidden and called via reflection.
 *
 * BT_SCAN_MODE constants (BluetoothAdapter, hidden):
 *   SCAN_MODE_NONE                   = 20
 *   SCAN_MODE_CONNECTABLE            = 21
 *   SCAN_MODE_CONNECTABLE_DISCOVERABLE = 23
 */
class Pairing(val ctx: Context) {
    private var openUntil = 0L
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothDevice.ACTION_PAIRING_REQUEST &&
                SystemClock.elapsedRealtime() < openUntil
            ) {
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                try {
                    BluetoothDevice::class.java
                        .getMethod("setPairingConfirmation", Boolean::class.java)
                        .invoke(d, true)
                    Log.i("LoopSpk", "auto-accepted pairing from ...${d?.address?.takeLast(5)}")
                    // Suppress the system BluetoothPairingDialog: ACTION_PAIRING_REQUEST is an
                    // ordered broadcast, so a higher-priority receiver that aborts it stops the
                    // dialog from ever showing -> truly zero-tap pairing. (BLUETOOTH_PRIVILEGED
                    // is granted, so we run before the system handler.)
                    try { abortBroadcast() } catch (_: Exception) {}
                } catch (e: Exception) {
                    Log.e("LoopSpk", "accept pairing", e)
                }
            }
        }
    }

    fun open(seconds: Int) {
        if (!registered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                // Beat the system pairing handler so we can auto-accept + abort the
                // dialog. SYSTEM_HIGH_PRIORITY is the max a (privileged) app can request.
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
            ctx.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED)
            registered = true
        }
        openUntil = SystemClock.elapsedRealtime() + seconds * 1000L
        setDiscoverable(seconds)
        Log.i("LoopSpk", "pairing window ${seconds}s")
        // Auto-close after timeout
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ close() }, seconds * 1000L)
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}
            registered = false
        }
        setDiscoverable(0)
        openUntil = 0
        Log.i("LoopSpk", "pairing window closed")
    }

    private fun setDiscoverable(sec: Int) {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        val mode = if (sec > 0) 23 /* CONNECTABLE_DISCOVERABLE */ else 21 /* CONNECTABLE */
        try {
            BluetoothAdapter::class.java
                .getMethod("setScanMode", Int::class.java)
                .invoke(a, mode)
            Log.i("LoopSpk", "scanMode=$mode (discoverable=${sec > 0})")
        } catch (e: Exception) {
            Log.e("LoopSpk", "scanmode", e)
        }
    }
}
