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
  case "$1:$2" in
    wifi:off) svc wifi disable ;;  wifi:on) svc wifi enable ;;
    data:off) svc data disable ;;  data:on) svc data enable ;;
    nfc:off)  svc nfc disable 2>/dev/null ;;
  esac
}

loop_pm() { pm "$1" --user 0 "$2" >/dev/null 2>&1; }  # $1=disable-user|enable
