#!/system/bin/sh
. "$MODPATH/scripts/lib.sh" 2>/dev/null || . /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
resetprop "${A2DP_SINK_PROP:-bluetooth.profile.a2dp.sink.enabled}" true
resetprop bluetooth.profile.a2dp.source.enabled false
# AVRCP role: a sink speaker is the Controller (CT) -- it SENDS play/pause/next/prev to
# the phone, and must NOT run the Target (TG) role, which is the phone's role. A sink
# stuck on TG cannot send transport commands AND fails absolute-volume negotiation
# (phone + speaker each apply their own gain = two independent sliders). Switching to
# CT with absvol enabled gives transport control + one synced volume slider.
resetprop bluetooth.profile.avrcp.controller.enabled true
resetprop bluetooth.profile.avrcp.target.enabled false
resetprop persist.bluetooth.disableabsvol false
# Advertised Bluetooth name. The stack derives the default from ro.product.model
# ("theloop") and re-applies it over secure/bluetooth_name on every enable, so the only
# durable override is this property (read once at BT stack init).
[ -n "$DEVICE_NAME" ] && resetprop persist.bluetooth.name "$DEVICE_NAME"
