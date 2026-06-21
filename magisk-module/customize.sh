#!/system/bin/sh
LOOP_DIR=/data/adb/loop-speaker-mode
mkdir -p "$LOOP_DIR"
# Clean-sync the runtime scripts. `cp -af src dest` when dest already exists copies
# src *into* dest (creating dest/scripts/…) instead of overwriting, so on reinstall the
# runtime scripts (which loop-*.sh source lib.sh from by absolute path) would otherwise
# go stale. Remove first, then copy.
rm -rf "$LOOP_DIR/scripts"
cp -af "$MODPATH/scripts" "$LOOP_DIR/scripts"
[ -f "$LOOP_DIR/config" ] || cp -f "$MODPATH/config.default" "$LOOP_DIR/config"

# auto-detect keypad + power input devices if not set
kp=$(grep -lE 'mtk-kpd' /sys/class/input/event*/device/name 2>/dev/null | head -1)
pw=$(grep -lE 'mtk-pmic-keys|mtk_pmic_keys' /sys/class/input/event*/device/name 2>/dev/null | head -1)
# persist detected paths into the installed config (only when detection found something)
[ -n "$kp" ] && sed -i "s|INPUT_KEYPAD=\"\"|INPUT_KEYPAD=\"$kp\"|" "$LOOP_DIR/config"
[ -n "$pw" ] && sed -i "s|INPUT_POWER=\"\"|INPUT_POWER=\"$pw\"|" "$LOOP_DIR/config"
# fallback: scan getevent names -> handled by daemon at runtime if blank
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/loopkeyd" 0 0 0755 2>/dev/null
ui_print "- Loop Speaker Mode installed. Reboot to apply (boots to Dumb)."
