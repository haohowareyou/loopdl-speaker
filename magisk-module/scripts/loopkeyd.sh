#!/system/bin/sh
# loopkeyd.sh: respawn supervisor for the native button daemon.
# Only runs the grabbing daemon while state==dumb; in full mode the binary
# is signalled (pkill -TERM) by loop-full.sh and the loop here idles until
# the state flips back to dumb (then it relaunches within ~2s).
. /data/adb/loop-speaker-mode/scripts/lib.sh

BIN=/data/adb/modules/loop-speaker-mode/system/bin/loopkeyd
# Daemon logs gesture detection to stdout; capture it so button gestures are
# debuggable (truncated on each (re)launch to stay small).
DLOG="$LOOP_DIR/loopkeyd.log"

while true; do
  if [ "$(cat "$STATE" 2>/dev/null)" = dumb ]; then
    "$BIN" >"$DLOG" 2>&1 || loop_log "loopkeyd exited $?"
  fi
  sleep 2
done
