#!/system/bin/sh
LOOP_DIR=/data/adb/loop-speaker-mode
mkdir -p "$LOOP_DIR"
cp -af "$MODPATH/scripts" "$LOOP_DIR/scripts"
[ -f "$LOOP_DIR/config" ] || cp -f "$MODPATH/config.default" "$LOOP_DIR/config"

# auto-detect keypad + power input devices if not set
kp=$(grep -lE 'mtk-kpd' /sys/class/input/event*/device/name 2>/dev/null | head -1)
pw=$(grep -lE 'mtk-pmic-keys|mtk_pmic_keys' /sys/class/input/event*/device/name 2>/dev/null | head -1)
# fallback: scan getevent names → handled by daemon at runtime if blank
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/loopkeyd" 0 0 0755 2>/dev/null
ui_print "- Loop Speaker Mode installed. Reboot to apply (boots to Dumb)."
