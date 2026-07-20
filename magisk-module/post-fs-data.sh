#!/system/bin/sh
. "$MODPATH/scripts/lib.sh" 2>/dev/null || . /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
# -n (write directly to the property area, no property_service / SELinux context check):
# the A2DP *sink* prop is not defined in every ROM's property_contexts (the MT6877 LoopDL
# only ships the *source* prop). Plain resetprop cannot CREATE an undefined prop there
# (no context -> denied) so the sink silently stays unset and the stack never brings the
# sink profile up. -n creates it; for already-defined props the difference is moot. These
# profile props are read once at BT stack init, so skipping change-triggers is fine.
resetprop -n "${A2DP_SINK_PROP:-bluetooth.profile.a2dp.sink.enabled}" true
resetprop -n bluetooth.profile.a2dp.source.enabled false
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
# durable override is a property read once at BT stack init.
[ -n "$DEVICE_NAME" ] && resetprop persist.bluetooth.name "$DEVICE_NAME"
# NOTE: bluetooth.device.default_name (the adapter name) is NOT set here -- at post-fs-data a
# shell resetprop can't materialise that bluetooth_config_prop. system.prop materialises it and
# service.sh overrides the value per-unit from DEVICE_NAME (after system.prop, before the BT bounce).
