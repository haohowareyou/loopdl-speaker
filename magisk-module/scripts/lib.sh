#!/system/bin/sh
LOOP_DIR=/data/adb/loop-speaker-mode
CONFIG="$LOOP_DIR/config"
STATE="$LOOP_DIR/state"
LOG="$LOOP_DIR/loop.log"

loop_log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; log -t LoopSpk "$*"; }

loop_load_config() { [ -f "$CONFIG" ] && . "$CONFIG"; }

# fire a cue/command into the helper app
loop_app() { am broadcast -a co.loop.speaker.CMD --es cmd "$1" ${2:+--es arg "$2"} \
  -n co.loop.speaker/.CmdReceiver >/dev/null 2>&1; }

loop_set_radio() { # loop_set_radio wifi on|off ; loop_set_radio data on|off
  # Two gotchas when toggling radios from Magisk boot-script context (service.sh and its
  # children: the daemon and the IPC poller):
  #  1) These run in BusyBox *standalone* mode, where a bare `svc` resolves to BusyBox
  #     (a no-op that just prints its banner) instead of Android's tool. Call the
  #     absolute /system/bin/svc to bypass the shadow.
  #  2) /system/bin/svc is `app_process ... Svc`, which needs the FULL (post-APEX)
  #     BOOTCLASSPATH. Early-spawned processes inherit a truncated boot classpath missing
  #     framework-wifi.jar/-connectivity.jar. Pull the complete one from system_server.
  if [ -z "$LOOP_BCP_FIXED" ]; then
    _ss=$(pgrep system_server 2>/dev/null | head -1)
    if [ -n "$_ss" ]; then
      _bcp=$(tr '\0' '\n' < "/proc/$_ss/environ" 2>/dev/null | grep '^BOOTCLASSPATH=' | cut -d= -f2-)
      [ -n "$_bcp" ] && export BOOTCLASSPATH="$_bcp"
    fi
    LOOP_BCP_FIXED=1
  fi
  case "$1:$2" in
    wifi:off) /system/bin/svc wifi disable ;;  wifi:on) /system/bin/svc wifi enable ;;
    data:off) /system/bin/svc data disable ;;  data:on) /system/bin/svc data enable ;;
    nfc:off)  /system/bin/svc nfc disable 2>/dev/null ;;
  esac
}

# $1=disable-user|enable. </dev/null is REQUIRED: callers run this inside
# `while read p; do loop_pm ... done < file` loops, and pm (via `cmd package`)
# reads stdin — without it pm swallows the rest of the file and the loop dies
# after the first package (silently masked in dumb mode since pkgs start disabled).
loop_pm() { pm "$1" --user 0 "$2" >/dev/null 2>&1 </dev/null; }
