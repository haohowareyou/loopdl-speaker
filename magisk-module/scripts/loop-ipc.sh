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

  # power-hold shutdown from the native daemon (dumb mode). Chime first so the user
  # — who has no screen — knows it heard them, then power off. The cue volume and the
  # shutdown are both run from this (su) context where svc/am succeed.
  if [ -f "$LOOP_DIR/req_shutdown" ]; then
    rm -f "$LOOP_DIR/req_shutdown"
    loop_log "ipc: button poweroff"
    loop_app say "Powering off"
    sleep 3
    loop_fix_bcp; /system/bin/svc power shutdown
  fi

  # mode toggle from the native daemon (Power+Vol-Down). The daemon can't run the
  # svc/settings calls in loop-mode from its own exec context, so it drops req_toggle
  # here and we run it in this (u:r:magisk:s0) context where those calls succeed.
  if [ -f "$LOOP_DIR/req_toggle" ]; then
    rm -f "$LOOP_DIR/req_toggle"
    if [ "$state" = dumb ]; then
      loop_log "ipc: toggle -> full"
      sh "$LOOP_DIR/scripts/loop-mode" full
    else
      loop_log "ipc: toggle -> dumb"
      sh "$LOOP_DIR/scripts/loop-mode" dumb
    fi
  fi

  # Mobile-data toggle from the full-mode QS tile (DataTile). Runs in both modes; svc needs
  # the full BOOTCLASSPATH + explicit /system/bin/svc (BusyBox standalone shadows it).
  if [ -f "$APPF/req_data_on" ]; then
    rm -f "$APPF/req_data_on"; loop_log "ipc: data on"; loop_fix_bcp; /system/bin/svc data enable
  fi
  if [ -f "$APPF/req_data_off" ]; then
    rm -f "$APPF/req_data_off"; loop_log "ipc: data off"; loop_fix_bcp; /system/bin/svc data disable
  fi

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
      loop_fix_bcp; /system/bin/svc power shutdown
    fi
  fi

  sleep 1
done
