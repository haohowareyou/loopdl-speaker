#!/system/bin/sh
# loopkeyd.sh — respawn supervisor for the native button daemon.
# Only runs the grabbing daemon while state==dumb; in full mode the binary
# is signalled (pkill -TERM) by loop-full.sh and the loop here idles until
# the state flips back to dumb (then it relaunches within ~2s).
. /data/adb/loop-speaker-mode/scripts/lib.sh

BIN=/data/adb/modules/loop-speaker-mode/system/bin/loopkeyd

while true; do
  if [ "$(cat "$STATE" 2>/dev/null)" = dumb ]; then
    "$BIN" || loop_log "loopkeyd exited $?"
  fi
  sleep 2
done
