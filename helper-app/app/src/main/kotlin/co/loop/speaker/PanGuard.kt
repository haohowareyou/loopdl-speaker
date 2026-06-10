package co.loop.speaker

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Speaker mode wants ONLY A2DP audio from the phone — never its data. When a phone is bonded,
 * Android opportunistically brings up **Bluetooth PAN** (the phone acting as a network-access
 * point — "hotspot over Bluetooth"), because each device's per-profile PAN connection policy
 * defaults to UNKNOWN(-1), which the stack treats as "may auto-connect". That silently routes
 * the speaker onto the phone's data.
 *
 * We pin PAN to FORBIDDEN(0) for every bonded device (A2DP_SINK stays ALLOWED), so PAN never
 * auto-connects. The policy persists in the bond, but we re-assert on dumb entry and on each
 * connect so newly-paired phones are covered too.
 *
 * BluetoothProfile.PAN = 5; setConnectionPolicy/disconnect are hidden SystemApis on the PAN
 * proxy (BLUETOOTH_PRIVILEGED via privapp).
 */
class PanGuard(val ctx: Context) {
    private companion object {
        const val PROFILE_PAN = 5
        const val CONNECTION_POLICY_FORBIDDEN = 0
    }

    private var proxy: BluetoothProfile? = null
    private val h = Handler(Looper.getMainLooper())

    /** Bind the PAN proxy (retrying past the boot BT bounce) and forbid existing bonds once up. */
    fun start() = bind(0)

    private fun bind(attempt: Int) {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, pr: BluetoothProfile) {
                proxy = pr
                Log.i("LoopSpk", "pan proxy bound")
                // Bonds may not be loaded into the adapter cache yet right after the boot BT
                // bounce, so forbidAll() can see zero devices. Re-run a few times to catch
                // them once they appear. (Policy doesn't persist across reboot, so we always
                // re-assert at boot; onConnected() also forbids per-connect.)
                forbidAll()
                h.postDelayed({ forbidAll() }, 3000)
                h.postDelayed({ forbidAll() }, 8000)
                h.postDelayed({ forbidAll() }, 20000)
            }
            override fun onServiceDisconnected(p: Int) { proxy = null }
        }, PROFILE_PAN)
        if (attempt < 6) h.postDelayed({ if (proxy == null) bind(attempt + 1) }, 2000)
    }

    /** Forbid PAN for every bonded device — call on dumb entry. */
    fun forbidAll() {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        val pr = proxy ?: return
        val bonded = a.bondedDevices ?: emptySet<BluetoothDevice>()
        Log.i("LoopSpk", "pan forbidAll: ${bonded.size} bonded")
        for (d in bonded) forbid(pr, d)
    }

    /** Forbid PAN for one device — call when a phone connects. */
    fun forbid(d: BluetoothDevice) {
        val pr = proxy ?: return
        forbid(pr, d)
    }

    private fun forbid(pr: BluetoothProfile, d: BluetoothDevice) {
        try {
            pr.javaClass
                .getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                .invoke(pr, d, CONNECTION_POLICY_FORBIDDEN)
            // Tear down any PAN link that's already up (policy alone won't drop a live one).
            try {
                pr.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(pr, d)
            } catch (_: Exception) {}
            Log.i("LoopSpk", "pan forbid ...${d.address.takeLast(5)}")
        } catch (e: Exception) {
            Log.e("LoopSpk", "pan forbid", e)
        }
    }

    fun stop() {
        proxy?.let {
            try { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(PROFILE_PAN, it) } catch (_: Exception) {}
        }
        proxy = null
        h.removeCallbacksAndMessages(null)
    }
}
