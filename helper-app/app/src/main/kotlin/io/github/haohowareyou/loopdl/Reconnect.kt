package io.github.haohowareyou.loopdl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

/**
 * On boot/dumb-mode entry, picks the most-recently-bonded device and attempts to
 * connect via A2DP Sink (profile id=11, hidden BluetoothA2dpSink).
 *
 * bondTimestamp: BluetoothDevice has a hidden getBondTimestamp() method returning the
 *   epoch ms when the device was last bonded.  Falls back to 0 on any error so
 *   maxByOrNull still works (just picks any device).
 *
 * sinkProxy: obtained via BluetoothAdapter.getProfileProxy with profile id 11.
 *   Stored in a class-level var so connect() can be called once the proxy callback fires.
 */
class Reconnect(val ctx: Context) {
    private val A2DP_SINK = 11
    private var sinkProxyRef: BluetoothProfile? = null

    fun tryLast(onFail: () -> Unit) {
        val a = BluetoothAdapter.getDefaultAdapter()
        val bonded = a?.bondedDevices
        if (bonded.isNullOrEmpty()) {
            Log.i("LoopSpk", "no bonded device")
            onFail()
            return
        }
        val d = bonded.maxByOrNull { bondTimestamp(it) }!!
        Log.i("LoopSpk", "reconnect candidate: ...${d.address.takeLast(5)} ts=${bondTimestamp(d)}")

        a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                sinkProxyRef = proxy
                try {
                    proxy.javaClass
                        .getMethod("connect", BluetoothDevice::class.java)
                        .invoke(proxy, d)
                    Log.i("LoopSpk", "reconnect -> ...${d.address.takeLast(5)}")
                } catch (e: Exception) {
                    Log.e("LoopSpk", "reconnect connect", e)
                    onFail()
                }
            }
            override fun onServiceDisconnected(p: Int) {
                sinkProxyRef = null
            }
        }, A2DP_SINK)
    }

    private fun bondTimestamp(d: BluetoothDevice): Long {
        return try {
            BluetoothDevice::class.java
                .getMethod("getBondTimestamp")
                .invoke(d) as? Long ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
