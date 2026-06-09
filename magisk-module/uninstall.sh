#!/system/bin/sh
LOOP_DIR=/data/adb/loop-speaker-mode
while read -r p; do [ -n "$p" ] && pm enable --user 0 "$p" >/dev/null 2>&1; done \
  < "$LOOP_DIR/scripts/packages-toggled.txt"
rm -rf "$LOOP_DIR"
