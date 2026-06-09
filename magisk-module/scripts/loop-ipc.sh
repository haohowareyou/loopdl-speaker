#!/system/bin/sh
# loop-ipc.sh — root-side executor for requests the unprivileged helper app
# (co.loop.speaker) drops in its own filesDir. The app has no su and cannot run
# pm/svc/input or write /data/adb, so it signals us via trigger files; we poll and
# execute as root.
#
#   req_dumb     -> return to dumb mode  (QS "Speaker Mode" tile; only acted on in full)
#   req_sleep    -> blank the panel      (idle stage 1; KEYCODE_SLEEP; dumb only)
#   req_poweroff -> shut down            (idle stage 2; dumb only)
#
# This runs in BOTH modes, independent of the daemon supervisor (loopkeyd.sh blocks
# on the foreground daemon while in dumb, so it cannot poll there). filesDir is
# app-private but Magisk root can read/delete it.
. /data/adb/loop-speaker-mode/scripts/lib.sh

APPF=/data/data/co.loop.speaker/files

while true; do
  state=$(cat "$STATE" 2>/dev/null)

  # full -> dumb (QS tile). Only meaningful when not already dumb.
  if [ -f "$APPF/req_dumb" ]; then
    rm -f "$APPF/req_dumb"
    if [ "$state" != dumb ]; then
      loop_log "ipc: tile requested dumb"
      sh "$LOOP_DIR/scripts/loop-mode" dumb
    fi
  fi

  # idle stages only apply in dumb mode; guard so a stale trigger can't act in full.
  if [ "$state" = dumb ]; then
    if [ -f "$APPF/req_sleep" ]; then
      rm -f "$APPF/req_sleep"
      input keyevent 223   # KEYCODE_SLEEP
    fi
    if [ -f "$APPF/req_poweroff" ]; then
      rm -f "$APPF/req_poweroff"
      loop_log "ipc: idle poweroff"
      svc power shutdown
    fi
  fi

  sleep 2
done
