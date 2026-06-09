package co.loop.speaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that owns all speaker-mode feature controllers.
 * Started on BOOT_COMPLETED (via BootReceiver) and on every CMD broadcast (via CmdReceiver).
 *
 * dispatch() is the single entry point for all commands. Commands:
 *   mode_dumb      — entered speaker mode (boot path: reconnect/pair + idle-sleep on)
 *   mode_full      — entered full mode (idle-sleep off)
 *   pair_open      — open PAIR_RETRIGGER-second discoverable window
 *   play_pause     — AVRCP toggle play/pause
 *   next           — AVRCP next track
 *   prev           — AVRCP previous track
 *   battery        — announce battery %
 *   say <text>     — speak arbitrary text via TTS
 *   ping           — no-op health check (just logs)
 */
class LoopService : Service() {
    companion object {
        const val TAG = "LoopSpk"
        private const val NOTIF_CH = "loop"
        private const val NOTIF_ID = 1
    }

    private lateinit var avrcp: Avrcp
    private lateinit var pairing: Pairing
    private lateinit var cues: Cues
    private lateinit var reconnect: Reconnect
    private lateinit var idleSleep: IdleSleep
    private lateinit var volume: Volume
    private var ready = false
    private var dumbMode = false

    // A2DP connect/disconnect events poke idle timer and trigger cues
    private val a2dpReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.i(TAG, "A2DP connected")
                    idleSleep.poke()
                    pairing.close()          // stop advertising; keep auto-accept armed
                    cues.say("Connected")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "A2DP disconnected")
                    idleSleep.poke()
                    // Speaker behaviour: become discoverable again whenever nothing is
                    // connected, so the next phone can find and silently re-pair.
                    if (dumbMode) pairing.open(Config.PAIR_INITIAL)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service create")
        try {
            startForeground(NOTIF_ID, buildNotif())
        } catch (e: Exception) {
            // A connectedDevice foreground service requires a *granted* runtime BT
            // permission. On a fresh install the module's service.sh grants them, but
            // BootReceiver may start us first. Bail cleanly instead of crash-looping;
            // service.sh grants the perms and re-triggers us.
            Log.w(TAG, "startForeground failed (BT perms not granted yet?): ${e.message}")
            stopSelf()
            return
        }

        Config.load()

        avrcp    = Avrcp(this)
        pairing  = Pairing(this)
        cues     = Cues(this, Config.CUE_VOLUME_PCT)
        reconnect = Reconnect(this)
        idleSleep = IdleSleep(this, Config.IDLE_SLEEP_MIN, Config.IDLE_OFF_MIN)
        volume   = Volume(this, avrcp)

        avrcp.init()
        cues.init()

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(a2dpReceiver, filter, RECEIVER_EXPORTED)
        ready = true
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (!ready) return START_NOT_STICKY
        i?.getStringExtra("cmd")?.let { cmd ->
            dispatch(cmd, i.getStringExtra("arg"))
        }
        return START_STICKY
    }

    fun dispatch(cmd: String, arg: String?) {
        Log.i(TAG, "cmd=$cmd arg=$arg")
        when (cmd) {
            "ping"       -> { /* health check — already logged above */ }

            "mode_dumb"  -> {
                dumbMode = true
                cues.say("Speaker mode")
                idleSleep.start()
                // Always silently auto-accept while we're a speaker. Then try to
                // reconnect the last phone; if that fails, advertise so a new phone
                // can find us (auto-accept makes the pairing itself codeless).
                pairing.enableAutoAccept()
                reconnect.tryLast {
                    cues.say("Pairing")
                    pairing.open(Config.PAIR_INITIAL)
                }
            }

            "mode_full"  -> {
                dumbMode = false
                cues.say("Full mode")
                idleSleep.stop()
                pairing.disableAutoAccept()
            }

            "pair_open"  -> {
                cues.say("Pairing")
                pairing.enableAutoAccept()
                pairing.open(Config.PAIR_RETRIGGER)
            }

            "play_pause" -> avrcp.playPause()
            "next"       -> avrcp.send(0x4B)
            "prev"       -> avrcp.send(0x4C)

            "battery"    -> cues.battery()
            "say"        -> arg?.let { cues.say(it) }

            else         -> Log.i(TAG, "unknown cmd=$cmd")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ready) {
            try { unregisterReceiver(a2dpReceiver) } catch (_: Exception) {}
            pairing.close()
            idleSleep.stop()
            cues.shutdown()
        }
        Log.i(TAG, "service destroy")
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun buildNotif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CH, "Loop Speaker", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, NOTIF_CH)
            .setContentTitle("Loop Speaker")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .build()
    }
}
