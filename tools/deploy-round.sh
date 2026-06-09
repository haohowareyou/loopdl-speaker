#!/system/bin/sh
# Run as: su -M -c 'sh /data/local/tmp/deploy-round.sh'
# (su -M = global mount namespace, required to write the magic-mounted module dir)
set -u
RT=/data/adb/loop-speaker-mode
MOD=/data/adb/modules/loop-speaker-mode
TMP=/data/local/tmp

echo "== scripts -> both dirs =="
for D in "$RT/scripts" "$MOD/scripts"; do
  cp -f "$TMP/loop-dumb.sh" "$D/loop-dumb.sh"
  cp -f "$TMP/loop-full.sh" "$D/loop-full.sh"
  chmod 755 "$D/loop-dumb.sh" "$D/loop-full.sh"
  chcon --reference="$D/loop-mode" "$D/loop-dumb.sh" "$D/loop-full.sh" 2>/dev/null
  echo "  updated $D"
done

echo "== config GESTURE_PAIR_HOLD_MS -> 3000 =="
sed -i 's/^GESTURE_PAIR_HOLD_MS=.*/GESTURE_PAIR_HOLD_MS=3000     # both volumes held this long = pairing window/' "$RT/config"
sed -i 's/^GESTURE_PAIR_HOLD_MS=.*/GESTURE_PAIR_HOLD_MS=3000     # both volumes held this long = pairing window/' "$MOD/config.default"
grep -E '^GESTURE_PAIR_HOLD_MS' "$RT/config"

echo "== APK -> module priv-app =="
DST="$MOD/system/priv-app/LoopSpeaker/LoopSpeaker.apk"
cp -f "$TMP/LoopSpeaker.apk" "$DST"
chmod 644 "$DST"
chown 0:0 "$DST"
chcon u:object_r:system_file:s0 "$DST"
ls -lZ "$DST"

echo "== wipe BT bonds (clean test round) =="
svc bluetooth disable
sleep 2
am force-stop com.android.bluetooth
sleep 1
for f in /data/misc/bluedroid/bt_config.conf /data/misc/bluedroid/bt_config.conf.bak; do
  [ -f "$f" ] || continue
  awk '
    /^\[/ { if ($0 ~ /^\[([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\]/) keep=0; else keep=1 }
    { if (keep) print }
  ' "$f" > "$f.new" && cat "$f.new" > "$f" && rm -f "$f.new"
  echo "  stripped device sections from $f"
done
echo "  remaining section headers:"; grep -hE '^\[' /data/misc/bluedroid/bt_config.conf 2>/dev/null
echo "== DONE (reboot to apply APK + clean BT) =="
