#!/system/bin/sh
. "$MODPATH/scripts/lib.sh" 2>/dev/null || . /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
resetprop "${A2DP_SINK_PROP:-bluetooth.profile.a2dp.sink.enabled}" true
resetprop bluetooth.profile.a2dp.source.enabled false
# Advertised Bluetooth name. The stack derives the default from ro.product.model
# ("theloop") and re-applies it over secure/bluetooth_name on every enable, so the only
# durable override is this property (read once at BT stack init).
[ -n "$DEVICE_NAME" ] && resetprop persist.bluetooth.name "$DEVICE_NAME"
