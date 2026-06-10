#!/usr/bin/env bash
# Restore the Loop's package enable/disable layout to a snapshot captured by
# snapshot-state.sh. Package enable/disable is non-destructive and fully reversible —
# this just re-syncs which packages are on/off. Settings/props are NOT auto-applied
# (too device-stateful; the snapshot keeps them for reference only).
#
# Usage:  tools/restore-state.sh <snapshot-dir>
set -euo pipefail
DIR="${1:-}"
[ -n "$DIR" ] && [ -d "$DIR" ] || { echo "usage: $0 <snapshot-dir>"; exit 1; }
[ -f "$DIR/packages-enabled.txt" ] || { echo "no packages-enabled.txt in $DIR"; exit 1; }
command -v adb >/dev/null || { echo "adb not on PATH"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No ADB device. Plug in the Loop (USB debugging on)."; exit 1; }

echo ">> re-enabling packages enabled in snapshot..."
while read -r p; do [ -z "$p" ] && continue
  adb shell "pm enable $p" >/dev/null 2>&1 || true
done < "$DIR/packages-enabled.txt"

echo ">> re-disabling packages disabled in snapshot..."
while read -r p; do [ -z "$p" ] && continue
  adb shell "pm disable-user --user 0 $p" >/dev/null 2>&1 || true
done < "$DIR/packages-disabled.txt"

echo ">> done. current disabled set:"
adb shell 'pm list packages -d' | sed 's/package://' | tr -d '\r' | sort
