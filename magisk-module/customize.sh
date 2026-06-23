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

# auto-detect keypad + power input device NAMES if not set.
# grep -l yields the matching FILE PATH; the daemon matches by device NAME (strstr),
# so read the name OUT of the matched file -- storing the path made pass-1 by-name
# detection fail and forced the capability fallback.
kpf=$(grep -lE 'mtk-kpd' /sys/class/input/event*/device/name 2>/dev/null | head -1)
pwf=$(grep -lE 'mtk-pmic-keys|mtk_pmic_keys' /sys/class/input/event*/device/name 2>/dev/null | head -1)
kp=$([ -n "$kpf" ] && cat "$kpf" 2>/dev/null)
pw=$([ -n "$pwf" ] && cat "$pwf" 2>/dev/null)
# Overwrite the whole line (not just the empty default) so a stale/wrong value
# persisted by an earlier install is corrected on reinstall.
[ -n "$kp" ] && sed -i "s|^INPUT_KEYPAD=.*|INPUT_KEYPAD=\"$kp\"|" "$LOOP_DIR/config"
[ -n "$pw" ] && sed -i "s|^INPUT_POWER=.*|INPUT_POWER=\"$pw\"|" "$LOOP_DIR/config"
# fallback: scan getevent names -> handled by daemon at runtime if blank
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/loopkeyd" 0 0 0755 2>/dev/null
ui_print "- Loop Speaker Mode installed. Reboot to apply (boots to Dumb)."
