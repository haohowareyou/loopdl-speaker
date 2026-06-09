package co.loop.speaker

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

/**
 * Wraps the hidden BluetoothAvrcpController profile (id=12) via reflection.
 * Used for passthrough transport control: play/pause/next/prev.
 * Also exposes setAbsoluteVolume for the Volume bridge fallback (Task 11).
 *
 * AVRCP passthrough keycodes (AVRCP 1.3 §25.19):
 *   PLAY   = 0x44
 *   PAUSE  = 0x46  (also used as toggle — most sources treat PAUSE as play/pause toggle)
 *   FORWARD (next)  = 0x4B
 *   BACKWARD (prev) = 0x4C
 */
class Avrcp(val ctx: Context) {
    private var ctrl: BluetoothProfile? = null
    private val AVRCP_CONTROLLER = 12

    fun init() {
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(
            ctx,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, x: BluetoothProfile) {
                    ctrl = x
                    Log.i("LoopSpk", "avrcp bound profile=$p")
                }
                override fun onServiceDisconnected(p: Int) {
                    ctrl = null
                    Log.i("LoopSpk", "avrcp unbound profile=$p")
                }
            },
            AVRCP_CONTROLLER
        )
    }

    private fun connectedDevice(): BluetoothDevice? = ctrl?.connectedDevices?.firstOrNull()

    /**
     * Sends a passthrough command (press + release) to the connected source device.
     * Uses reflection on the hidden BluetoothAvrcpController.sendPassThroughCmd method.
     */
    fun send(key: Int) {
        val c = ctrl ?: run { Log.i("LoopSpk", "avrcp: no controller proxy"); return }
        val d = connectedDevice() ?: run { Log.i("LoopSpk", "avrcp: no connected device"); return }
        try {
            val m = c.javaClass.getMethod(
                "sendPassThroughCmd",
                BluetoothDevice::class.java, Int::class.java, Int::class.java
            )
            m.invoke(c, d, key, 0) // key pressed
            m.invoke(c, d, key, 1) // key released
            Log.i("LoopSpk", "avrcp key=0x${key.toString(16)} -> ${d.address}")
        } catch (e: Exception) {
            Log.e("LoopSpk", "avrcp send key=0x${key.toString(16)}", e)
        }
    }

    /** Play/pause toggle — sends PAUSE (0x46); most A2DP sources treat it as toggle. */
    fun playPause() = send(0x46)

    /**
     * Forward absolute volume (0–127) to the source device via the hidden
     * BluetoothAvrcpController.setAbsoluteVolume method.
     * No-op if ctrl is null (proxy not bound yet) or method not found on this ROM.
     */
    fun setAbsoluteVolume(v: Int) {
        val c = ctrl ?: return
        val d = connectedDevice() ?: return
        try {
            val m = c.javaClass.getMethod(
                "setAbsoluteVolume",
                BluetoothDevice::class.java, Int::class.java
            )
            m.invoke(c, d, v.coerceIn(0, 127))
            Log.i("LoopSpk", "avrcp setAbsVol=$v -> ${d.address}")
        } catch (e: NoSuchMethodException) {
            // Some ROMs expose this differently; Volume bridge is optional anyway
            Log.i("LoopSpk", "avrcp setAbsoluteVolume not found on this ROM")
        } catch (e: Exception) {
            Log.e("LoopSpk", "avrcp setAbsVol", e)
        }
    }
}
