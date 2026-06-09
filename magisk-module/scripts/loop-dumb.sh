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
settings put system screen_off_timeout 15000

echo dumb > "$STATE"
loop_app mode_dumb
loop_log "DUMB applied"
