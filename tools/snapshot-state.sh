#!/usr/bin/env bash
# Capture the Loop's logical software state (packages / settings / props / Magisk) into a
# timestamped snapshot dir, so any later change can be rolled back with restore-state.sh.
#
# This is the LOGICAL half of a stage snapshot (the irreplaceable partition half is a
# separate mtkclient dump; see capture-unrooted-baseline.sh / run-partition-backup.sh).
# The three stages we keep:
#   1. unrooted     - pristine factory state (capture from a NEW unrooted unit; see docs/05)
#   2. rooted       - unlocked + Magisk + A2DP-sink, before debloat (loop-backup/snapshot-*-rooted-baseline)
#   3. speaker-mode - the speaker module installed + configured
#
# Usage:  tools/snapshot-state.sh <label> [out_base]
#   label    : stage name, e.g. speaker-mode
#   out_base : where to write (default ./snapshots). Snapshot dir = <out_base>/snapshot-<date>-<label>
#
# Output dirs match the repo .gitignore (snapshots/, snapshot-*/); they contain device
# identifiers (serial in getprop, etc.) and must never be committed.
set -euo pipefail

LABEL="${1:-}"
OUT_BASE="${2:-snapshots}"
[ -n "$LABEL" ] || { echo "usage: $0 <label> [out_base]"; exit 1; }
command -v adb >/dev/null || { echo "adb not on PATH"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No ADB device. Plug in the Loop (USB debugging on)."; exit 1; }

DATE="$(date +%F)"
DIR="$OUT_BASE/snapshot-$DATE-$LABEL"
mkdir -p "$DIR"
echo ">> capturing '$LABEL' -> $DIR"

# --- packages: exact enable/disable layout (the reversible, restorable part) ---
adb shell 'pm list packages'    | sed 's/package://' | tr -d '\r' | sort > "$DIR/packages-all.txt"
adb shell 'pm list packages -e' | sed 's/package://' | tr -d '\r' | sort > "$DIR/packages-enabled.txt"
adb shell 'pm list packages -d' | sed 's/package://' | tr -d '\r' | sort > "$DIR/packages-disabled.txt"
adb shell 'pm list packages -3' | sed 's/package://' | tr -d '\r' | sort > "$DIR/packages-3rdparty.txt"

# --- settings + props (reference; not auto-restored; too device-stateful) ---
for ns in global secure system; do
  adb shell "settings list $ns" | tr -d '\r' | sort > "$DIR/settings-$ns.txt"
done
adb shell 'getprop' | tr -d '\r' > "$DIR/getprop.txt"

# --- Magisk + boot-script state ---
{
  adb shell 'su -c "magisk -V; magisk -v"' 2>/dev/null
  echo "--- modules ---";        adb shell 'su -c "ls /data/adb/modules"' 2>/dev/null
  echo "--- post-fs-data.d ---"; adb shell 'su -c "ls /data/adb/post-fs-data.d"' 2>/dev/null
  echo "--- service.d ---";      adb shell 'su -c "ls /data/adb/service.d"' 2>/dev/null
} | tr -d '\r' > "$DIR/magisk-state.txt"

# --- speaker-module + BT role proof (what makes stage 3 stage 3) ---
{
  echo "a2dp.sink   = $(adb shell 'getprop bluetooth.profile.a2dp.sink.enabled' | tr -d '\r')"
  echo "a2dp.source = $(adb shell 'getprop bluetooth.profile.a2dp.source.enabled' | tr -d '\r')"
  echo "avrcp.ct    = $(adb shell 'getprop bluetooth.profile.avrcp.controller.enabled' | tr -d '\r')"
  echo "loop-module = $(adb shell 'su -c "ls /data/adb/modules/loop-speaker-mode/module.prop 2>/dev/null && cat /data/adb/modules/loop-speaker-mode/module.prop" ' 2>/dev/null | tr -d '\r')"
} > "$DIR/speaker-state.txt"

cat > "$DIR/README.md" <<EOF
# Loop logical-state snapshot - stage: $LABEL ($DATE)

Captured by tools/snapshot-state.sh. The LOGICAL software state only - package
enable/disable layout, settings, props, Magisk/module state. The irreplaceable
per-unit partitions (identity/RF/lock) are a separate mtkclient dump.

## Restore the package layout (reversible, non-destructive)
\`\`\`bash
tools/restore-state.sh "$DIR"
\`\`\`

## Files
- packages-{all,enabled,disabled,3rdparty}.txt - exact package layout
- settings-{global,secure,system}.txt - Settings provider (reference)
- getprop.txt - full props (reference; CONTAINS DEVICE SERIAL - gitignored)
- magisk-state.txt - Magisk version + modules + boot scripts
- speaker-state.txt - A2DP/AVRCP role + loop module presence (stage-3 markers)

See docs/05-snapshots.md for the full 3-stage model.
EOF

echo ">> done. $(wc -l < "$DIR/packages-all.txt") packages, $(wc -l < "$DIR/packages-disabled.txt") disabled."
echo "   $DIR"
