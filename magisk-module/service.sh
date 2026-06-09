#!/system/bin/sh
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 3
sh "$MODDIR/scripts/loop-mode" dumb
# daemon supervisor (added in Phase 3; guarded so Phase 1 zip is valid)
[ -x "$MODDIR/system/bin/loopkeyd" ] && sh "$MODDIR/scripts/loopkeyd.sh" &
