#!/system/bin/sh
LOOP_DIR=/data/adb/loop-speaker-mode
CONFIG="$LOOP_DIR/config"
STATE="$LOOP_DIR/state"
LOG="$LOOP_DIR/loop.log"

loop_log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; log -t LoopSpk "$*"; }

loop_load_config() {
  # Config is sourced as shell for personal-device convenience; it is intentionally
  # not a security boundary (device is already rooted, single-user).
  [ -f "$CONFIG" ] && . "$CONFIG"
  [ -f "$CONFIG" ] && chmod 0600 "$CONFIG"
}

# fire a cue/command into the helper app
loop_app() { am broadcast -a io.github.haohowareyou.loopdl.CMD --es cmd "$1" ${2:+--es arg "$2"} \
  -n io.github.haohowareyou.loopdl/.CmdReceiver >/dev/null 2>&1; }

# /system/bin/svc (and settings/cmd) are `app_process ... Svc`, which needs the FULL
# (post-APEX) BOOTCLASSPATH. Early-spawned Magisk boot-script processes (service.sh and
# its children: the daemon, the IPC poller) inherit a TRUNCATED boot classpath missing
# framework-wifi.jar/-connectivity.jar/etc, so a raw `svc wifi`/`svc power` silently
# fails. Pull the complete env from system_server. Idempotent (cached via LOOP_BCP_FIXED).
# Must be called before any svc invocation in these contexts.
loop_fix_bcp() {
  [ -n "$LOOP_BCP_FIXED" ] && return
  _ss=$(pgrep system_server 2>/dev/null | head -1)
  if [ -n "$_ss" ]; then
    _bcp=$(tr '\0' '\n' < "/proc/$_ss/environ" 2>/dev/null | grep '^BOOTCLASSPATH=' | cut -d= -f2-)
    [ -n "$_bcp" ] && export BOOTCLASSPATH="$_bcp"
  fi
  LOOP_BCP_FIXED=1
}

loop_set_radio() { # loop_set_radio wifi on|off ; loop_set_radio data on|off
  # Bare `svc` in BusyBox *standalone* mode resolves to BusyBox (a no-op banner) instead
  # of Android's tool, so call the absolute /system/bin/svc; and fix the boot classpath
  # (see loop_fix_bcp) so that app_process Svc can actually load.
  loop_fix_bcp
  case "$1:$2" in
    wifi:off) /system/bin/svc wifi disable ;;  wifi:on) /system/bin/svc wifi enable ;;
    data:off) /system/bin/svc data disable ;;  data:on) /system/bin/svc data enable ;;
    nfc:off)  /system/bin/svc nfc disable 2>/dev/null ;;
  esac
}

# $1=disable-user|enable. </dev/null is REQUIRED: callers run this inside
# `while read p; do loop_pm ... done < file` loops, and pm (via `cmd package`)
# reads stdin; without it pm swallows the rest of the file and the loop dies
# after the first package (silently masked in dumb mode since pkgs start disabled).
loop_pm() { pm "$1" --user 0 "$2" >/dev/null 2>&1 </dev/null; }
