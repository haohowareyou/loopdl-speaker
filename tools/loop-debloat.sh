#!/usr/bin/env bash
# Permanently remove the rainx/loop bloatware — REVERSIBLY.
#
# The speaker module only DISABLES the rainx packages (they never run, in either mode). This
# goes a step further and uninstalls them for the only user (user 0). That removes them
# entirely from the launcher/app list and frees their data, yet writes NO partitions: the
# system APKs stay on the read-only /system image, so `restore` brings them back with
# `pm install-existing`. Fully reversible, no risk to the firmware.
#
# Packages come from magisk-module/scripts/packages-permanent-disable.txt (the 3 rainx apps):
#   co.rainx.loop.launcher  co.rainx.loop.setup  vendor.rainx.setupwizard.overlay
#
# Usage:  tools/loop-debloat.sh {status|remove|restore}
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIST="$HERE/../magisk-module/scripts/packages-permanent-disable.txt"
CMD="${1:-status}"

command -v adb >/dev/null || { echo "adb not on PATH"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No ADB device. Plug in the Loop (USB debugging on)."; exit 1; }
[ -f "$LIST" ] || { echo "package list not found: $LIST"; exit 1; }

pkgs() { grep -vE '^\s*(#|$)' "$LIST"; }

state() {
  local p="$1"
  if adb shell "pm list packages --user 0 $p" 2>/dev/null | grep -q "package:$p"; then
    if adb shell "pm list packages -d --user 0 $p" 2>/dev/null | grep -q "package:$p"; then
      echo "installed (disabled)"
    else echo "installed (enabled)"; fi
  else echo "REMOVED (uninstalled for user 0)"; fi
}

case "$CMD" in
  status)
    for p in $(pkgs); do printf '%-40s %s\n' "$p" "$(state "$p")"; done ;;
  remove)
    echo ">> uninstalling rainx packages for user 0 (reversible)..."
    for p in $(pkgs); do
      adb shell "pm uninstall --user 0 $p" >/dev/null 2>&1 \
        && echo "   removed   $p" || echo "   skip      $p (already gone / not present)"
    done
    echo ">> done. rainx code is now uninstalled for the user; system APKs remain for restore." ;;
  restore)
    echo ">> reinstalling rainx packages from the system image..."
    for p in $(pkgs); do
      adb shell "cmd package install-existing $p" >/dev/null 2>&1 \
        && echo "   restored  $p" || echo "   skip      $p"
    done
    echo ">> done. (They are restored ENABLED; the speaker module re-disables them on next mode apply.)" ;;
  *)
    echo "usage: $0 {status|remove|restore}"; exit 1 ;;
esac
