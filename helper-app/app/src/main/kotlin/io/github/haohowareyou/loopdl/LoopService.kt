package io.github.haohowareyou.loopdl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground service that owns all speaker-mode feature controllers.
 * Started on BOOT_COMPLETED (via BootReceiver) and on every CMD broadcast (via CmdReceiver).
 *
 * dispatch() is the single entry point for all commands. Commands:
 *   mode_dumb      - entered speaker mode (boot path: reconnect/pair + idle-sleep on)
 *   mode_full      - entered full mode (idle-sleep off)
 *   pair_open      - open PAIR_RETRIGGER-second discoverable window
 *   play_pause     - AVRCP toggle play/pause
 *   next           - AVRCP next track
 *   prev           - AVRCP previous track
 *   battery        - announce battery %
 *   say <text>     - speak arbitrary text via TTS
 *   eq <preset>    - switch output EQ preset (flat|warm|bass|vocal); no arg = compiled default
 *   eq_band <i:mB> - raw single-band override (millibels) for on-device tuning
 *   ping           - no-op health check (just logs)
 */
class LoopService : Service() {
    companion object {
        const val TAG = "LoopSpk"
        private const val NOTIF_CH = "loop"
        private const val NOTIF_ID = 1
        // Hidden A2DP-Sink profile connection-state-changed broadcast (authoritative).
        private const val ACTION_SINK_STATE =
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    }

    private lateinit var avrcp: Avrcp
    private lateinit var pairing: Pairing
    private lateinit var cues: Cues
    private lateinit var tones: Tones
    private lateinit var reconnect: Reconnect
    private lateinit var idleSleep: IdleSleep
    private lateinit var volume: Volume
    private lateinit var tick: Tick
    private lateinit var battery: BatteryWatch
    private lateinit var panGuard: PanGuard
    private lateinit var eq: Eq
    private val keepAlive = AudioKeepAlive()
    private var ready = false
    private var dumbMode = false
    private val handler = Handler(Looper.getMainLooper())
    // Set briefly when WE deliberately drop a phone (manual handoff): the resulting
    // disconnect must NOT announce "Disconnected"/re-open -- pair_open already did.
    private var suppressDisconnect = false
    // Address of the phone we last announced "Connected" for. Gates double-announce
    // (flapping / re-fire for the same link) and is cleared only on a true disconnect.
    private var announcedAddr: String? = null
    // A2DP-Sink profile proxy, used to read the REAL per-device connection state
    // (STATE_CONNECTED) rather than the raw ACL link, which comes up mid-pairing and
    // made us shout "Connected" ~0.6s before the phone had actually paired.
    private var sinkProxy: BluetoothProfile? = null

    // Two connect/disconnect signals, deduped:
    //  1) PRIMARY: the A2DP-Sink profile CONNECTION_STATE_CHANGED broadcast. Authoritative
    //     and fires only on a real profile transition (after pairing+audio is up), so it
    //     never produces the premature "Connected" the raw ACL did.
    //  2) FALLBACK: ACL_CONNECTED triggers a polled confirm against the sink profile state
    //     (in case the broadcast isn't delivered on this ROM). Both routes funnel into
    //     onConnected()/onDisconnected(), which are idempotent (announcedAddr / collapsed
    //     disconnect runnable), so firing twice is harmless.
    private val a2dpReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                ACTION_SINK_STATE -> {
                    val st = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    Log.i(TAG, "a2dp-sink state=$st")
                    if (!dumbMode) return
                    when (st) {
                        BluetoothProfile.STATE_CONNECTED    -> dev?.let { onConnected(it) }
                        BluetoothProfile.STATE_DISCONNECTED -> onDisconnected()
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.i(TAG, "ACL connected")
                    idleSleep.poke()
                    if (dumbMode && dev != null) {
                        // Forbid PAN the instant the phone links (reliable -- uses the event's
                        // device, not the flaky bondedDevices cache), so it can never bring up
                        // Bluetooth tethering. Policy doesn't persist, so this re-asserts each
                        // connect.
                        panGuard.forbid(dev)
                        confirmConnected(dev, 0)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "ACL disconnected")
                    idleSleep.poke()
                    onDisconnected()
                }
            }
        }
    }

    /** Announce "Connected" exactly once per phone. Idempotent via announcedAddr so the
     *  broadcast and the ACL-fallback can't double-announce. */
    private fun onConnected(dev: BluetoothDevice) {
        if (!dumbMode || announcedAddr == dev.address) return
        announcedAddr = dev.address
        reconnect.cancelTimeout()         // phone arrived in time; cancel the pairing fallback
        idleSleep.poke()
        handler.removeCallbacks(ampStopRunnable)
        keepAlive.stop()                  // music/route keeps the amp warm now
        pairing.close()                   // stop advertising; keep auto-accept armed
        pairing.disconnectOthers(dev)     // single source: newest phone wins
        tones.connected()
        battery.alertIfLow()              // queued after "Connected" if the pack is low
        panGuard.forbid(dev)              // never let this phone share data over BT-PAN
    }

    /** Debounced + collapsed: a brief drop+reconnect (or an auto-kicked older phone)
     *  shouldn't announce. We speak/re-open only when, after the dust settles, nothing is
     *  actually connected. removeCallbacks collapses the ACL + broadcast triggers into one
     *  so "Disconnected. Pairing." is never said twice. */
    private val disconnectRunnable = Runnable {
        if (dumbMode && !suppressDisconnect && !pairing.anyConnected()) {
            announcedAddr = null
            // Just the "dropped" earcon -- re-entering pairing right after is implied, so we
            // reopen the discoverable window silently (announce=false) rather than chaining a
            // second tone the user doesn't need.
            tones.disconnected()
            openPairing(Config.PAIR_INITIAL, announce = false)
        }
    }
    private fun onDisconnected() {
        handler.removeCallbacks(disconnectRunnable)
        handler.postDelayed(disconnectRunnable, 900)
    }

    /** ACL fallback: poll the real A2DP-Sink profile state a few times (it comes up a beat
     *  after the ACL) and announce via onConnected() once CONNECTED. Only forces an
     *  announce on the last attempt if the proxy is unavailable (-1) AND no STATE_DISCONNECTED
     *  was seen -- degraded path for a ROM that delivers neither the broadcast nor the proxy. */
    private fun confirmConnected(dev: BluetoothDevice, attempt: Int) {
        handler.postDelayed({
            if (!dumbMode || announcedAddr == dev.address) return@postDelayed
            val st = a2dpState(dev)               // 2 = STATE_CONNECTED, -1 = proxy n/a
            if (st == 2 || (st == -1 && attempt >= 4)) {
                onConnected(dev)
            } else if (st != 0 && attempt < 4) {
                confirmConnected(dev, attempt + 1)
            }
        }, if (attempt == 0) 800L else 700L)
    }

    private fun a2dpState(d: BluetoothDevice): Int = try {
        val p = sinkProxy ?: return -1
        p.javaClass.getMethod("getConnectionState", BluetoothDevice::class.java)
            .invoke(p, d) as? Int ?: -1
    } catch (e: Exception) {
        Log.e(TAG, "a2dpState", e); -1
    }

    /** Bind the A2DP-Sink proxy, retrying past the boot-time BT bounce. getProfileProxy
     *  called while the BT service is restarting silently fails to bind ("bluetooth service
     *  not start"), so we re-request every 2s until onServiceConnected lands. */
    private fun bindSinkProxy(attempt: Int = 0) {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                sinkProxy = proxy; Log.i(TAG, "sink proxy bound")
            }
            override fun onServiceDisconnected(p: Int) { sinkProxy = null }
        }, 11)
        if (attempt < 8) handler.postDelayed({
            if (sinkProxy == null) bindSinkProxy(attempt + 1)
        }, 2000)
    }

    // Single rolling amp-warm timer (keepAlive auto-stops some ms after the last reason to
    // be warm). All warm reasons funnel through warmAmpFor so they share one stop callback.
    private val ampStopRunnable = Runnable { keepAlive.stop() }

    private fun warmAmpFor(ms: Long) {
        keepAlive.start()
        handler.removeCallbacks(ampStopRunnable)
        handler.postDelayed(ampStopRunnable, ms)
    }

    /** Any button interaction keeps the amp warm for 45s so feedback tones/cues are
     *  audible -- without a 24/7 stream that would block doze and drain the battery. */
    private fun touchAmp() = warmAmpFor(45_000L)

    /** Open a discoverable pairing window with the amp kept warm for its duration, so the
     *  cues that fire during it (and the "Connected" when a phone arrives) aren't clipped
     *  by a cold amp. Keep-alive auto-stops on connect or when the window closes. */
    private fun openPairing(seconds: Int, announce: Boolean = true) {
        pairing.open(seconds, announce)
        warmAmpFor(seconds * 1000L + 500)
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
        cues     = Cues(this, Config.CUE_VOLUME_PCT)
        // Earcons replace spoken state cues; they warm the amp via the same rolling timer.
        tones    = Tones { touchAmp() }
        pairing  = Pairing(this, tones)
        reconnect = Reconnect(this)
        // onPreOff: warn ~1 min before the idle auto-off so a button tap can keep it alive.
        idleSleep = IdleSleep(this, Config.IDLE_SLEEP_MIN, Config.IDLE_OFF_MIN) { tones.idleWarn() }
        volume   = Volume(this, avrcp)
        // Volume press feedback also warms the amp so the tick (and any nearby cue) is
        // audible from a cold/standby amp -- same "is it on?" reassurance as the power chime.
        // onEdge: distinct "limit" earcon when volume tops out / bottoms out.
        tick     = Tick(this, { touchAmp() }, { tones.edge() })
        battery  = BatteryWatch(this, tones)
        panGuard = PanGuard(this)   // block BT-PAN (phone's data over Bluetooth) -- audio only
        eq       = Eq()             // global output EQ on the A2DP-sink stream

        avrcp.init()
        cues.init()
        // Shape the output: attach the EQ/BassBoost to the global mix. Fails safe (audio left
        // flat) if this ROM bypasses session-0 effects on the sink path -- see Eq.kt.
        eq.init(Config.EQ_PRESET)

        // Bind the A2DP-Sink profile proxy (id 11), retrying past the boot BT bounce, so
        // confirmConnected()/a2dpState() can read the real per-device connection state.
        bindSinkProxy()
        panGuard.start()   // bind PAN proxy + forbid PAN for existing bonds once up

        val filter = IntentFilter().apply {
            addAction(ACTION_SINK_STATE)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(a2dpReceiver, filter, "android.permission.BLUETOOTH_PRIVILEGED", null, RECEIVER_EXPORTED)
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
            "ping"       -> { /* health check -- already logged above */ }

            "mode_dumb"  -> {
                dumbMode = true
                tones.speaker()
                idleSleep.start()
                battery.start()   // proactive low-battery warnings + graceful shutdown
                panGuard.forbidAll()  // re-assert: no phone data over BT-PAN in speaker mode
                tick.start()      // audible volume feedback (screen off)
                volume.start()    // forward local volume -> phone (single synced slider)
                // Always silently auto-accept while we're a speaker. Then try to
                // reconnect the last phone; if that fails, advertise so a new phone
                // can find us (auto-accept makes the pairing itself codeless).
                pairing.enableAutoAccept()
                reconnect.tryLast {
                    openPairing(Config.PAIR_INITIAL)    // open() announces "Pairing"
                }
            }

            "mode_full"  -> {
                dumbMode = false
                tones.full()
                idleSleep.stop()
                battery.stop()
                tick.stop()
                volume.stop()
                keepAlive.stop()
                pairing.disableAutoAccept()
            }

            "pair_open"  -> {
                // Forced handoff: forget+drop whoever's connected, then open for a new
                // phone. We ALWAYS open ourselves (the drop's ACL_DISCONNECTED can't be
                // relied on -- removeBond may land after a beat, and plain disconnect
                // didn't fire it at all). Suppress that disconnect's cue so we say
                // "Pairing" once, not "Pairing"+"Disconnected".
                pairing.enableAutoAccept()
                suppressDisconnect = true
                announcedAddr = null
                handler.postDelayed({ suppressDisconnect = false }, 3000)
                pairing.forceDisconnectConnected()
                openPairing(Config.PAIR_RETRIGGER)
            }

            // Power tap: toggle play/pause AND emit the soft wake earcon so -- with no
            // screen -- a press tells you the speaker is on even when nothing's playing.
            "play_pause" -> { avrcp.playPause(); tones.wake() }
            "next"       -> { avrcp.next(); touchAmp() }
            "prev"       -> { avrcp.prev(); touchAmp() }

            "battery"    -> cues.battery()
            "say"        -> arg?.let { cues.say(it) }

            "eq"         -> eq.apply(arg ?: Config.EQ_PRESET)
            "eq_band"    -> arg?.let { eq.setBand(it) }

            else         -> Log.i(TAG, "unknown cmd=$cmd")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ready) {
            try { unregisterReceiver(a2dpReceiver) } catch (_: Exception) {}
            handler.removeCallbacks(ampStopRunnable)
            keepAlive.stop()
            sinkProxy?.let {
                try { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(11, it) } catch (_: Exception) {}
            }
            pairing.close()
            idleSleep.stop()
            battery.stop()
            panGuard.stop()
            tick.stop()
            volume.stop()
            eq.release()
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
