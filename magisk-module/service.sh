#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/scripts/lib.sh"
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 3

# Boot straight into the speaker, not the phone. The moment the framework is up, blank
# the panel and kill Wi-Fi so the device never lingers as a full phone — auto-joining a
# known hotspot, lighting the launcher — during this late_start window. The heavier
# dumb-mode apply and the BT role bounce below then happen behind an already-dark screen.
input keyevent 223          # KEYCODE_SLEEP
loop_set_radio wifi off

# Grant the runtime BT permissions the helper app needs. A connectedDevice foreground
# service (LoopService) requires a *granted* runtime BT permission; priv-app status does
# NOT auto-grant dangerous perms and the app is headless (no UI to prompt). Grants persist
# across reboots, so this is effectively a one-time first-boot step (idempotent thereafter).
i=0
until pm path io.github.haohowareyou.loopdl >/dev/null 2>&1 || [ "$i" -ge 30 ]; do sleep 1; i=$((i+1)); done
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE; do
  pm grant io.github.haohowareyou.loopdl "android.permission.$p" 2>/dev/null
done

# Force AVRCP Controller (CT) role for the sink speaker. The bluetooth.profile.* props
# are re-asserted by the BT stack at its OWN init stage (after post-fs-data), so setting
# them early doesn't stick — they must be set here (late_start) and the stack bounced.
# CT lets the speaker SEND play/pause/next/prev to the phone and enables absolute-volume
# (one synced slider); the ROM-default Target role breaks both.
#
# Stop the helper app BEFORE the bounce so the disconnect/reconnect churn produces NO
# spoken cues (it owns no a2dpReceiver while dead). loop-mode dumb then starts it fresh
# AFTER the stack is settled, so it binds the now-available AvrcpControllerService and
# announces exactly one clean "Connected" instead of the old pair-twice chatter.
am force-stop io.github.haohowareyou.loopdl
resetprop bluetooth.profile.avrcp.controller.enabled true
resetprop bluetooth.profile.avrcp.target.enabled false
resetprop persist.bluetooth.disableabsvol false
if [ "$(getprop bluetooth.profile.avrcp.controller.enabled)" = true ]; then
  svc bluetooth disable; sleep 2
  am force-stop com.android.bluetooth; sleep 1
  svc bluetooth enable; sleep 3
fi

# The enable above sometimes lands the adapter in BLE_ON (BLE stack up, CLASSIC BT off):
# enabled:false, ScanMode SCAN_MODE_NONE. In that half-state A2DP and discoverability are
# dead — setScanMode returns ERROR_BLUETOOTH_NOT_ENABLED and the speaker announces "Pairing"
# but is never findable. Force it to full classic ON, re-issuing enable until the state
# settles (or we give up after ~24s and let it be).
settings put global bluetooth_on 1
i=0
while [ "$i" -lt 12 ]; do
  st=$(dumpsys bluetooth_manager 2>/dev/null | grep -m1 -E '^  state:' | awk '{print $2}')
  [ "$st" = "ON" ] && break
  svc bluetooth enable
  sleep 2; i=$((i+1))
done
loop_log "bt classic state=$st (enable tries=$i)"

sh "$MODDIR/scripts/loop-mode" dumb
# daemon supervisor (added in Phase 3; guarded so Phase 1 zip is valid)
[ -x "$MODDIR/system/bin/loopkeyd" ] && sh "$MODDIR/scripts/loopkeyd.sh" &
# root-side IPC poller: executes app requests (QS tile -> dumb, idle sleep/poweroff)
# that the unprivileged helper app drops as trigger files. Runs in both modes.
sh "$MODDIR/scripts/loop-ipc.sh" &
