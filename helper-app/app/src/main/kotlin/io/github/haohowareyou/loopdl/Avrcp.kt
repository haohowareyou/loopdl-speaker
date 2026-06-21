package io.github.haohowareyou.loopdl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

/**
 * Transport control + absolute volume for the connected AVRCP player (the phone).
 *
 * Transport (play/pause/next/prev) is sent via AudioManager.dispatchMediaKeyEvent:
 * when the loop is an AVRCP Controller (CT) with a connected source, the BT stack
 * publishes the remote player as the active MediaSession, so a dispatched media key
 * is forwarded to the phone over AVRCP. (BluetoothAvrcpController.sendPassThroughCmd
 * was removed from AOSP by Android 15 — reflection on it throws NoSuchMethod.)
 *
 * We still bind BluetoothAvrcpController (profile 12) for setAbsoluteVolume (used by
 * the optional Volume bridge) and connection-state visibility.
 */
class Avrcp(val ctx: Context) {
    private var ctrl: BluetoothProfile? = null
    private val AVRCP_CONTROLLER = 12
    private val am = ctx.getSystemService(AudioManager::class.java)

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
     * Dispatches a media key (down + up) to the active MediaSession, which the BT stack
     * routes to the connected AVRCP player (the phone). Works regardless of whether a
     * controller proxy/connected-device is visible to us — routing is handled by the
     * framework's media session service.
     */
    private fun mediaKey(code: Int) {
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            Log.i("LoopSpk", "media key=$code dispatched")
        } catch (e: Exception) {
            Log.e("LoopSpk", "media key=$code", e)
        }
    }

    fun playPause() = mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun next()      = mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun prev()      = mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

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
