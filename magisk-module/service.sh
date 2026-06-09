#!/system/bin/sh
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 3

# Grant the runtime BT permissions the helper app needs. A connectedDevice foreground
# service (LoopService) requires a *granted* runtime BT permission; priv-app status does
# NOT auto-grant dangerous perms and the app is headless (no UI to prompt). Grants persist
# across reboots, so this is effectively a one-time first-boot step (idempotent thereafter).
i=0
until pm path co.loop.speaker >/dev/null 2>&1 || [ "$i" -ge 30 ]; do sleep 1; i=$((i+1)); done
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE; do
  pm grant co.loop.speaker "android.permission.$p" 2>/dev/null
done

sh "$MODDIR/scripts/loop-mode" dumb
# daemon supervisor (added in Phase 3; guarded so Phase 1 zip is valid)
[ -x "$MODDIR/system/bin/loopkeyd" ] && sh "$MODDIR/scripts/loopkeyd.sh" &
# root-side IPC poller: executes app requests (QS tile -> dumb, idle sleep/poweroff)
# that the unprivileged helper app drops as trigger files. Runs in both modes.
sh "$MODDIR/scripts/loop-ipc.sh" &
