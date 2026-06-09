#!/system/bin/sh
. /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
loop_log "entering FULL mode"

# permanent rainx: STILL disabled even in full mode (privacy guarantee)
while read -r p; do [ -n "$p" ] && loop_pm disable-user "$p"; done < "$(dirname "$0")/packages-permanent-disable.txt"
# toggled: re-enable for full phone use
while read -r p; do [ -n "$p" ] && loop_pm enable "$p"; done < "$(dirname "$0")/packages-toggled.txt"

# radios back on
loop_set_radio wifi on
loop_set_radio data on

settings put system screen_off_timeout 120000
echo full > "$STATE"
loop_app mode_full
loop_log "FULL applied"
