#!/system/bin/sh
# Live-replace loopkeyd binary + dumb/full scripts and restart the daemon.
# Run as root on-device. Expects new files staged in /data/local/tmp.
LD=/data/adb/loop-speaker-mode
BIN=/data/adb/modules/loop-speaker-mode/system/bin/loopkeyd

echo "old_pid=$(pgrep -x loopkeyd) old_size=$(stat -c %s "$BIN")"

# stop supervisor relaunch (it only respawns while state==dumb), kill daemon,
# then unlink+rewrite the binary (avoids 'Text file busy' on the mmap'd file).
echo full > "$LD/state"
kill -9 $(pgrep -x loopkeyd) 2>/dev/null
sleep 1
rm -f "$BIN"
cp /data/local/tmp/loopkeyd.new "$BIN"
chmod 0755 "$BIN"
chcon u:object_r:system_file:s0 "$BIN" 2>/dev/null

cp /data/local/tmp/loop-dumb.sh "$LD/scripts/loop-dumb.sh"
cp /data/local/tmp/loop-full.sh "$LD/scripts/loop-full.sh"
chmod 0755 "$LD/scripts/loop-dumb.sh" "$LD/scripts/loop-full.sh"

# relaunch new daemon
echo dumb > "$LD/state"
sleep 3
echo "new_pid=$(pgrep -x loopkeyd) new_size=$(stat -c %s "$BIN")"
echo "=== daemon log ==="
cat "$LD/loopkeyd.log"
