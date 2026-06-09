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
class Pairing(val ctx: Context, val tones: Tones) {
    companion object {
        // BluetoothProfile connection-policy constants (hidden).
        private const val CONNECTION_POLICY_FORBIDDEN = 0
        private const val CONNECTION_POLICY_ALLOWED = 100
        // How long a dropped phone stays forbidden before becoming a normal bonded
        // device again (enough for a new phone to take over the single source slot).
        private const val REALLOW_MS = 45_000L
    }

    private var autoAccept = false
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())
    // Separate handler for delayed re-ALLOW so it survives the discoverable-timeout
    // handler's removeCallbacksAndMessages(null) calls.
    private val policyHandler = Handler(Looper.getMainLooper())

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
     *  already be armed (enableAutoAccept) for the pairing itself to be silent.
     *  Plays the pairing earcon and restarts the discoverable countdown on EVERY call —
     *  re-triggering while already open re-cues and resets the timer (there's no screen,
     *  so the earcon is the only "we're open" indicator). */
    fun open(seconds: Int, announce: Boolean = true) {
        if (!registered) enableAutoAccept()
        if (announce) tones.pairing()
        setDiscoverable(true, seconds)
        Log.i("LoopSpk", "discoverable ${seconds}s")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ setDiscoverable(false) }, seconds * 1000L)
    }

    /** True if any bonded device currently has an active connection. */
    fun anyConnected(): Boolean {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return false
        return (a.bondedDevices ?: emptySet<BluetoothDevice>()).any { isConnected(it) }
    }

    /** Forced handoff: drop every connected device so a new phone can take over. */
    fun forceDisconnectConnected(): Boolean = dropExcept(null)

    /** Single-source enforcement: drop every connected device EXCEPT [keep] (the one
     *  that just connected), so the newest phone always wins. */
    fun disconnectOthers(keep: BluetoothDevice?): Boolean = dropExcept(keep?.address)

    /** Drop a phone WITHOUT un-bonding it.
     *
     *  We used to removeBond() here, which was the root of most of the pairing grief:
     *  un-bond is one-sided — the speaker forgets the phone but the PHONE keeps its bond,
     *  so (a) the user had to "Forget device" before re-pairing, and (b) the phone kept
     *  auto-reconnecting with a now-invalid key, producing the connect/disconnect FLAPPING
     *  seen in the logs (and the premature/cascading cues).
     *
     *  setConnectionPolicy(FORBIDDEN) is the right primitive: it disconnects the device
     *  AND tells the framework not to auto-reconnect it, while leaving the bond intact on
     *  both sides — so re-pairing is never required. We also call disconnect() to force
     *  the teardown immediately rather than waiting for the policy to take effect. The
     *  forbidden device is re-ALLOWED after a delay so it isn't permanently banished —
     *  long enough for a new phone to grab us, after which the old one is just a normal
     *  bonded device that won't fight for the connection.
     *
     *  setConnectionPolicy / disconnect are hidden SystemApis (BLUETOOTH_PRIVILEGED via
     *  privapp). Returns true if at least one connected device was dropped. */
    private fun dropExcept(keepAddr: String?): Boolean {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return false
        var dropped = false
        for (d in a.bondedDevices ?: emptySet<BluetoothDevice>()) {
            if (keepAddr != null && d.address == keepAddr) continue
            if (!isConnected(d)) continue
            setConnectionPolicy(d, CONNECTION_POLICY_FORBIDDEN)
            disconnect(d)
            Log.i("LoopSpk", "forbid+disconnect ...${d.address.takeLast(5)}")
            // Re-permit after the handoff settles so it isn't banned forever.
            policyHandler.postDelayed({
                setConnectionPolicy(d, CONNECTION_POLICY_ALLOWED)
                Log.i("LoopSpk", "re-allow ...${d.address.takeLast(5)}")
            }, REALLOW_MS)
            dropped = true
        }
        return dropped
    }

    private fun setConnectionPolicy(d: BluetoothDevice, policy: Int) = try {
        BluetoothDevice::class.java
            .getMethod("setConnectionPolicy", Int::class.javaPrimitiveType)
            .invoke(d, policy)
        Unit
    } catch (e: Exception) {
        Log.e("LoopSpk", "setConnectionPolicy", e)
    }

    private fun disconnect(d: BluetoothDevice) = try {
        BluetoothDevice::class.java.getMethod("disconnect").invoke(d)
        Unit
    } catch (e: Exception) {
        Log.e("LoopSpk", "disconnect", e)
    }

    private fun isConnected(d: BluetoothDevice): Boolean = try {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean ?: false
    } catch (_: Exception) { false }

    /** A phone connected: stop advertising, but keep auto-accept armed for re-pairs. */
    fun close() {
        handler.removeCallbacksAndMessages(null)
        setDiscoverable(false)
        Log.i("LoopSpk", "discoverable off")
    }

    private fun setDiscoverable(on: Boolean, seconds: Int = 0) {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        // A13+: the discoverable timeout MUST be set (>0, as a Duration) BEFORE setScanMode,
        // or setScanMode(SCAN_MODE_CONNECTABLE_DISCOVERABLE) is rejected and the adapter
        // stays SCAN_MODE_NONE — the bug where we announced "Pairing" but were never
        // actually discoverable. The old setDiscoverableTimeout(int) overload is gone on
        // A15 (throws), so we try the Duration signature first.
        if (on) setDiscoverableTimeout(a, seconds.coerceAtLeast(1))
        val mode = if (on) 23 else 21
        try {
            val rc = BluetoothAdapter::class.java
                .getMethod("setScanMode", Int::class.javaPrimitiveType)
                .invoke(a, mode)
            // a.scanMode is the public getter — log the ACTUAL resulting mode, not just
            // what we asked for, so a silent rejection is visible.
            Log.i("LoopSpk", "setScanMode($mode) rc=$rc -> scanMode=${a.scanMode}")
        } catch (e: Exception) {
            Log.e("LoopSpk", "scanmode", e)
        }
    }

    private fun setDiscoverableTimeout(a: BluetoothAdapter, seconds: Int) {
        try {
            BluetoothAdapter::class.java
                .getMethod("setDiscoverableTimeout", java.time.Duration::class.java)
                .invoke(a, java.time.Duration.ofSeconds(seconds.toLong()))
            Log.i("LoopSpk", "discoverableTimeout(Duration ${seconds}s)")
            return
        } catch (_: Exception) {}
        try {
            BluetoothAdapter::class.java
                .getMethod("setDiscoverableTimeout", Int::class.javaPrimitiveType)
                .invoke(a, seconds)
            Log.i("LoopSpk", "discoverableTimeout(int ${seconds}s)")
        } catch (e: Exception) {
            Log.e("LoopSpk", "discoverableTimeout", e)
        }
    }
}
