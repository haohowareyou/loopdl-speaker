#!/system/bin/sh
. /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
loop_log "entering FULL mode"

# Flip state to full BEFORE killing the daemon. The supervisor (loopkeyd.sh) only
# (re)launches the grabbing daemon while state==dumb; if we killed first and wrote
# state later (after the slow package re-enable loop), the supervisor would relaunch
# it in the gap and the buttons would stay grabbed in full mode (F2).
echo full > "$STATE"

# release the button grab so phone buttons are 100% native in full mode.
# The daemon ungrabs all devices on SIGTERM (main() cleanup), restoring factory keys.
pkill -TERM loopkeyd 2>/dev/null

# permanent rainx: STILL disabled even in full mode (privacy guarantee)
while read -r p; do [ -n "$p" ] && loop_pm disable-user "$p"; done < "$(dirname "$0")/packages-permanent-disable.txt"
# toggled: re-enable for full phone use
while read -r p; do [ -n "$p" ] && loop_pm enable "$p"; done < "$(dirname "$0")/packages-toggled.txt"

# radios back on
loop_set_radio wifi on
loop_set_radio data on

settings put system screen_off_timeout 120000
settings put secure double_tap_to_wake 1   # restore normal-phone tap-to-wake
loop_app mode_full
loop_log "FULL applied"
