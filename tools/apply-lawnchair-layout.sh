#!/usr/bin/env bash
# Apply a reproducible Lawnchair DOCK (hotseat) layout in full mode by writing the standard
# Launcher3 `favorites` table directly. Lawnchair ships with an empty home; this gives full
# mode a useful dock out of the box. Everything else stays in the app drawer (swipe up).
#
#   dock (hotseat): Firefox · Camera · Settings · Play Store
#
# NOTE: dock only, by design. Lawnchair tracks workspace-PAGE screen order in its own prefs
# store (not this table), so desktop-page rows written via raw SQL get culled by the loader on
# reload; the hotseat has no such dependency and persists. Setting a full multi-page home
# reliably needs a `.lawnchairbackup` restore (create one on a configured device, then restore
# it). Out of scope here. The dock is what's reproducible via SQL.
#
# The Loop is rooted, so we edit the launcher DB host-side (no on-device sqlite3): pull it,
# rewrite favorites with host sqlite3, push it back with the right owner + SELinux context,
# and restart the launcher. The DB is backed up first; restore with --restore.
#
# Usage:  tools/apply-lawnchair-layout.sh [apply|restore]
set -euo pipefail
command -v adb >/dev/null     || { echo "adb not on PATH"; exit 1; }
command -v sqlite3 >/dev/null || { echo "host sqlite3 required"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No ADB device."; exit 1; }

LC=/data/data/app.lawnchair
DB=$(adb shell "su -c 'ls $LC/databases/launcher_*.db 2>/dev/null | grep -vE \"wal|shm|journal\" | head -1'" | tr -d '\r')
[ -n "$DB" ] || { echo "Lawnchair launcher DB not found (is Lawnchair installed + opened once?)"; exit 1; }
TMP=$(mktemp -d)
BK="${LOOP_BACKUP:-/tmp}/lawnchair-layout-backup.db"

pull() { adb exec-out "su -c 'cat $DB'" > "$1"; }
push() {
  adb push "$1" /data/local/tmp/lc_new.db >/dev/null
  local own; own=$(adb shell "su -c 'stat -c %u:%g $DB'" | tr -d '\r')
  adb shell "su -c 'cat /data/local/tmp/lc_new.db > $DB; chown $own $DB; restorecon $DB; rm -f ${DB}-journal ${DB}-wal ${DB}-shm /data/local/tmp/lc_new.db'"
}
restart_launcher() {
  adb shell "am force-stop app.lawnchair" >/dev/null 2>&1 || true
  sleep 1
  adb shell "monkey -p app.lawnchair -c android.intent.category.HOME 1" >/dev/null 2>&1 || true
}

if [ "${1:-apply}" = "restore" ]; then
  [ -f "$BK" ] || { echo "no backup at $BK"; exit 1; }
  echo ">> restoring launcher DB from $BK"
  push "$BK"; restart_launcher; echo ">> restored."; exit 0
fi

echo ">> backing up launcher DB -> $BK"
pull "$BK"

INTENT='#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;launchFlags=0x10200000;component=%s;end'
row() { # title pkg/cls container screen cellX cellY
  printf "INSERT INTO favorites (title,intent,container,screen,cellX,cellY,spanX,spanY,itemType,appWidgetId,restored,profileId,rank) VALUES ('%s','%s',%s,%s,%s,%s,1,1,0,-1,0,0,0);\n" \
    "$1" "$(printf "$INTENT" "$2")" "$3" "$4" "$5" "$6"
}

pull "$TMP/launcher.db"
{
  echo "DELETE FROM favorites WHERE container=-101;"   # replace the dock only
  # dock (container -101, screen=cellX index)
  row "Firefox"    "org.mozilla.fennec_fdroid/.App"            -101 0 0 0
  row "Camera"     "com.mediatek.camera/.CameraLauncher"       -101 1 1 0
  row "Settings"   "com.android.settings/.Settings"            -101 2 2 0
  row "Play Store" "com.android.vending/.AssetBrowserActivity" -101 3 3 0
} > "$TMP/layout.sql"

sqlite3 "$TMP/launcher.db" < "$TMP/layout.sql"
echo ">> applying layout ($(sqlite3 "$TMP/launcher.db" 'SELECT COUNT(*) FROM favorites;') items)"
push "$TMP/launcher.db"
restart_launcher
rm -rf "$TMP"
echo ">> done. (restore with: $0 restore)"
