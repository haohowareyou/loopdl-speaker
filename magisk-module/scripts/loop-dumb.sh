#!/system/bin/sh
. /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
loop_log "entering DUMB mode"

# permanent: rainx stays disabled (privacy guarantee), re-enforced every time
while read -r p; do [ -n "$p" ] && loop_pm disable-user "$p"; done < "$(dirname "$0")/packages-permanent-disable.txt"
# toggled: disable for speaker use
while read -r p; do [ -n "$p" ] && loop_pm disable-user "$p"; done < "$(dirname "$0")/packages-toggled.txt"

# radios
[ "${KEEP_WIFI:-0}" = 1 ] || loop_set_radio wifi off
[ "${KEEP_DATA:-0}" = 1 ] || loop_set_radio data off
loop_set_radio nfc off
settings put secure location_mode 0

# bt name + screen policy. The advertised Bluetooth name is secure/bluetooth_name
# (global/device_name alone does NOT rename the adapter); set both. Applied by the BT
# stack on its next init (i.e. from the next boot) — harmless to re-set every dumb entry.
settings put global device_name "$DEVICE_NAME"
settings put secure bluetooth_name "$DEVICE_NAME"

# Dumb mode is screen-dark: power the panel off now (root context; the app's own
# IdleSleep can't because it runs unprivileged). Keep a short timeout as a backstop
# in case something briefly wakes it (charger insert, etc.). The daemon grabs the
# power key, so a power tap maps to play/pause and does NOT wake the panel.
# Also clear "stay awake while plugged in" — a speaker is usually on a charger, and
# that developer setting (if on) would otherwise keep the panel lit indefinitely.
settings put global stay_on_while_plugged_in 0
# Disable double-tap-to-wake: it keeps the touch digitizer in a low-power gesture-scan
# mode while the screen is off (extra battery + accidental wakes). A dumb speaker never
# takes screen input, so let the digitizer fully sleep. Restored in full mode.
settings put secure double_tap_to_wake 0
settings put system screen_off_timeout 10000
input keyevent 223   # KEYCODE_SLEEP

echo dumb > "$STATE"
loop_app mode_dumb
loop_log "DUMB applied"
