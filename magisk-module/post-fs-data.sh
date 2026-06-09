#!/system/bin/sh
. "$MODPATH/scripts/lib.sh" 2>/dev/null || . /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
resetprop "${A2DP_SINK_PROP:-bluetooth.profile.a2dp.sink.enabled}" true
resetprop bluetooth.profile.a2dp.source.enabled false
