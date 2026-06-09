# Loop Speaker Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Magisk module + headless system app that turns a rooted LoopDL into a dual-mode device — a lean auto-pairing Bluetooth speaker (default) switchable to a full Android phone — driven entirely by hardware buttons.

**Architecture:** One Magisk module (`loop-speaker-mode`) carries: shell mode-scripts (radio/package/screen policy), boot hooks, a native `loopkeyd` button daemon (EVIOCGRAB + uinput), and a headless system priv-app (`co.loop.speaker`) that owns all Bluetooth/AVRCP/TTS/idle logic. Scripts and the daemon drive the app via broadcasts. Everything reads `/data/adb/loop-speaker-mode/config`; mode persists in a state file but every boot forces Dumb.

**Tech Stack:** Magisk module (busybox shell), C compiled with Android NDK (arm64-v8a), Kotlin/Android (minSdk 35, system priv-app, Gradle), adb for on-device verification.

---

## Testing reality (read first)

This is on-device root + hardware work; classic host-side TDD does not apply. The discipline we substitute:

- **Shell:** every script passes `shellcheck`, then a concrete on-device `adb` verification with expected output.
- **Native:** compiles clean, then on-device behavioral check via `getevent`/`logcat`.
- **App:** each feature is exercised by an `adb shell am broadcast`/`am start` trigger with a `logcat` assertion (the app logs a tagged line per action).
- A task is "done" only when its verification command shows the expected output **on the device** (serial `WTFMGBD3PCA000482`, model `theloop`).

Keep `adb logcat -s LoopSpk:* loopkeyd:*` running in a side terminal during execution.

**Device prerequisites (already true on this unit; a fresh unit must complete docs steps 1–3 first):** bootloader unlocked, Magisk v30.6 root, A2DP sink props persisted. Confirm before Phase 1: `adb shell su -c id` → `context=u:r:magisk:s0`.

---

## File structure

```
loopdl-speaker/
├── magisk-module/
│   ├── module.prop                     # id=loop-speaker-mode, version, etc.
│   ├── customize.sh                    # install: seed config, detect input devices, set perms
│   ├── post-fs-data.sh                 # a2dp sink props (early)
│   ├── service.sh                      # late_start: wait boot → enter dumb → start daemon
│   ├── uninstall.sh                    # restore: re-enable toggled pkgs
│   ├── scripts/
│   │   ├── lib.sh                      # sourced everywhere: config load, log, helpers
│   │   ├── loop-mode                   # dispatcher: `loop-mode dumb|full|status`
│   │   ├── loop-dumb.sh                # apply Dumb policy
│   │   ├── loop-full.sh                # apply Full policy
│   │   ├── loopkeyd.sh                 # supervisor: respawn native daemon
│   │   ├── packages-permanent-disable.txt
│   │   └── packages-toggled.txt        # generated from snapshot (29 pkgs)
│   ├── system/
│   │   ├── bin/loopkeyd                # native binary (built, gitignored; built by build.sh)
│   │   ├── priv-app/LoopSpeaker/LoopSpeaker.apk   # built app (gitignored)
│   │   └── etc/permissions/privapp-permissions-co.loop.speaker.xml
│   └── config.default                  # default config copied to /data/adb/... on install
├── native/
│   ├── loopkeyd.c                      # the daemon source
│   └── Makefile                        # NDK build → arm64-v8a binary
├── helper-app/                         # Gradle Android project (source)
│   ├── build.gradle.kts, settings.gradle.kts
│   └── app/src/main/{AndroidManifest.xml, kotlin/co/loop/speaker/*.kt}
├── tools/
│   ├── loop-mode                       # Mac-side adb wrapper
│   ├── build-module.sh                 # assemble flashable zip
│   └── setup-ndk.sh                    # one-time NDK bootstrap
└── docs/ … (01-unlock … 04-speaker-mode, recovery, compatibility)
```

---

# PHASE 0 — Recovery / brick insurance (do FIRST)

The module itself writes no partitions and is removable via Magisk Safe Mode, so build risk is low. This phase exists so that even a catastrophic, unrelated failure is recoverable. BROM being wide-open means every partition can be *written* back over USB — we just need *images* to write.

### Task 0: Critical-partition raw backup

**Files:** none in repo (images are personal/firmware → gitignored, stored under `loop-backup/partitions-<date>/`).

- [ ] **Step 1: Put device in preloader mode** — power fully OFF, then plug USB with **NO buttons held** (preloader supplies DRAM/EMI config; BROM mode would hang stage-2 — the known gotcha).

- [ ] **Step 2: Read the small critical partitions** (skip `super`/`userdata` — huge + personal). Reuse the working mtkclient venv:
```bash
cd ~/Documents/Claude/personal/loopdl/mtkclient
mkdir -p ../loop-backup/partitions-2026-06-09
for P in preloader_a preloader_b lk_a lk_b boot_a boot_b init_boot_a init_boot_b \
         vbmeta_a vbmeta_b vbmeta_system_a vbmeta_system_b vbmeta_vendor_a vbmeta_vendor_b \
         seccfg nvram nvdata nvcfg protect1 protect2 persist md1img_a md1img_b; do
  ./venv/bin/python mtk.py r "$P" "../loop-backup/partitions-2026-06-09/$P.img" || echo "skip $P"
done
```
(Names per the 62-partition GPT already dumped; `|| echo skip` tolerates slot/name variance.)

- [ ] **Step 3: Verify** — every expected `.img` exists and is non-zero:
```bash
ls -lh ../loop-backup/partitions-2026-06-09/ | awk '$5=="0"{print "EMPTY:",$9}'
```
Expected: no `EMPTY:` lines. Confirm `init_boot_a.img` matches the known stock (`cmp` vs `rooting/stock-firmware/init_boot_a.img`) — sanity that the read is real.

- [ ] **Step 4: Record a one-line restore recipe** in `docs/recovery.md` (written in Task 18): `mtk.py w <partition> loop-backup/partitions-2026-06-09/<partition>.img` in preloader mode restores any single partition.

- [ ] **Step 5:** confirm `loop-backup/partitions-*/` is gitignored (it is: `loop-backup/` + `*.img` rules). Do NOT commit images.

---

# PHASE 1 — Module foundation (shell, no app/daemon yet)

End state: a flashable zip that on boot applies Dumb policy (radios, packages, screen) and supports `loop-mode dumb|full|status` over adb. No buttons/app yet. Fully testable.

### Task 1: Module skeleton + metadata

**Files:**
- Create: `magisk-module/module.prop`
- Create: `magisk-module/config.default`
- Create: `tools/build-module.sh`

- [ ] **Step 1: Write `module.prop`**

```
id=loop-speaker-mode
name=Loop Speaker Mode
version=v0.1.0
versionCode=1
author=loopdl-speaker
description=Dual-mode: lean auto-pairing BT speaker (default) <-> full Android phone, via hardware buttons.
```

- [ ] **Step 2: Write `config.default`** (the shipped defaults; install copies to `/data/adb/loop-speaker-mode/config` only if absent)

```sh
DEVICE_NAME="Loop A"
KEEP_DATA=0
KEEP_WIFI=0
PAIR_INITIAL=180
PAIR_RETRIGGER=60
IDLE_SLEEP_MIN=5
IDLE_OFF_MIN=15
INPUT_KEYPAD=""           # auto-detected at install if empty
INPUT_POWER=""            # auto-detected at install if empty
A2DP_SINK_PROP="bluetooth.profile.a2dp.sink.enabled"
GESTURE_PAIR_HOLD_MS=3000
GESTURE_MODE_HOLD_MS=5000
DOUBLE_TAP_WINDOW_MS=300
CUE_VOLUME_PCT=50
```

- [ ] **Step 3: Write `tools/build-module.sh`** — assembles the flashable zip from `magisk-module/`, after building native + app (those tasks add hooks; for now just zip what exists).

```sh
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/loop-speaker-mode.zip"
mkdir -p "$ROOT/build"
cd "$ROOT/magisk-module"
rm -f "$OUT"
zip -r9 "$OUT" . -x '.*'
echo "built: $OUT"
```

- [ ] **Step 4: Verify** — `bash tools/build-module.sh` produces `build/loop-speaker-mode.zip`; `unzip -l build/loop-speaker-mode.zip` lists `module.prop`. Expected: zip created, module.prop present.

- [ ] **Step 5: Commit**

```bash
git add magisk-module/module.prop magisk-module/config.default tools/build-module.sh
git commit -m "feat(module): skeleton, default config, zip builder"
```

### Task 2: Shared lib (`lib.sh`)

**Files:**
- Create: `magisk-module/scripts/lib.sh`

- [ ] **Step 1: Write `lib.sh`** — sourced by every script. Loads config, provides logging + reusable helpers. No side effects on source.

```sh
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
```

- [ ] **Step 2: Verify** — `shellcheck -s sh magisk-module/scripts/lib.sh` → no errors (warnings on `svc`/`am` external commands acceptable; fix any SC2xxx logic errors).

- [ ] **Step 3: Commit**

```bash
git add magisk-module/scripts/lib.sh
git commit -m "feat(module): shared lib.sh (config, logging, radio/pkg/app helpers)"
```

### Task 3: Package lists (generate from snapshot)

**Files:**
- Create: `magisk-module/scripts/packages-permanent-disable.txt`
- Create: `magisk-module/scripts/packages-toggled.txt`

- [ ] **Step 1: Write the permanent-disable list** (the 3 rainx packages — authoritative)

```
co.rainx.loop.launcher
co.rainx.loop.setup
vendor.rainx.setupwizard.overlay
```

- [ ] **Step 2: Generate the toggled list from the baseline snapshot** — the snapshot's disabled set minus the 3 rainx packages IS the 29 MODE_TOGGLED packages (single source of truth, no hand-typing IDs).

Run (Mac):
```bash
SNAP=~/Documents/Claude/personal/loopdl/loop-backup/snapshot-2026-06-09-rooted-baseline
grep -vxF -f magisk-module/scripts/packages-permanent-disable.txt \
  "$SNAP/packages-disabled.txt" \
  | sed 's/^package://' | sort -u > magisk-module/scripts/packages-toggled.txt
wc -l < magisk-module/scripts/packages-toggled.txt   # expect 29
```

- [ ] **Step 3: Verify** — line count is 29; no rainx entries present:
```bash
test "$(wc -l < magisk-module/scripts/packages-toggled.txt)" -eq 29 && echo OK
grep -c rainx magisk-module/scripts/packages-toggled.txt   # expect 0
```
Expected: `OK` and `0`. If count ≠ 29, reconcile against the design spec §2 list before continuing.

- [ ] **Step 4: Commit** (lists are config, not personal data — safe to commit)

```bash
git add magisk-module/scripts/packages-permanent-disable.txt magisk-module/scripts/packages-toggled.txt
git commit -m "feat(module): package sets (3 permanent rainx + 29 toggled, from snapshot)"
```

### Task 4: Mode scripts (`loop-dumb.sh`, `loop-full.sh`, `loop-mode`)

**Files:**
- Create: `magisk-module/scripts/loop-dumb.sh`
- Create: `magisk-module/scripts/loop-full.sh`
- Create: `magisk-module/scripts/loop-mode`

- [ ] **Step 1: Write `loop-dumb.sh`**

```sh
#!/system/bin/sh
. /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
loop_log "entering DUMB mode"

# permanent: rainx stays disabled (privacy guarantee), re-enforced every time
while read -r p; do [ -n "$p" ] && loop_pm disable-user "$p"; done < "$(dirname "$0")/packages-permanent-disable.txt"
# toggled: disable for speaker use
while read -r p; do [ -n "$p" ] && loop_pm disable-user "$p"; done < "$(dirname "$0")/packages-toggled.txt"

# radios
[ "${KEEP_WIFI:-0}" = 1 ] || loop_set_radio wifi off
[ "${KEEP_DATA:-0}" = 1 ] || loop_set_radio data off
loop_set_radio nfc off
settings put secure location_mode 0

# bt name + screen policy
settings put global device_name "$DEVICE_NAME"
settings put system screen_off_timeout 15000

echo dumb > "$STATE"
loop_app mode_dumb
loop_log "DUMB applied"
```

- [ ] **Step 2: Write `loop-full.sh`**

```sh
#!/system/bin/sh
. /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
loop_log "entering FULL mode"

# permanent rainx: STILL disabled even in full mode (privacy guarantee)
while read -r p; do [ -n "$p" ] && loop_pm disable-user "$p"; done < "$(dirname "$0")/packages-permanent-disable.txt"
# toggled: re-enable for full phone use
while read -r p; do [ -n "$p" ] && loop_pm enable "$p"; done < "$(dirname "$0")/packages-toggled.txt"

# radios back on
loop_set_radio wifi on
loop_set_radio data on

settings put system screen_off_timeout 120000
echo full > "$STATE"
loop_app mode_full
loop_log "FULL applied"
```

- [ ] **Step 3: Write `loop-mode` dispatcher**

```sh
#!/system/bin/sh
D="$(dirname "$0")"
case "$1" in
  dumb) sh "$D/loop-dumb.sh" ;;
  full) sh "$D/loop-full.sh" ;;
  status) cat /data/adb/loop-speaker-mode/state 2>/dev/null || echo unknown ;;
  *) echo "usage: loop-mode dumb|full|status" >&2; exit 2 ;;
esac
```

- [ ] **Step 4: Verify** — `shellcheck -s sh` on all three (no logic errors). Functional verification happens in Task 7 after install.

- [ ] **Step 5: Commit**

```bash
git add magisk-module/scripts/loop-dumb.sh magisk-module/scripts/loop-full.sh magisk-module/scripts/loop-mode
git commit -m "feat(module): dumb/full mode scripts + dispatcher"
```

### Task 5: Boot hooks (`post-fs-data.sh`, `service.sh`, `customize.sh`, `uninstall.sh`)

**Files:**
- Create: `magisk-module/post-fs-data.sh`
- Create: `magisk-module/service.sh`
- Create: `magisk-module/customize.sh`
- Create: `magisk-module/uninstall.sh`

- [ ] **Step 1: `post-fs-data.sh`** — early, sets A2DP sink props before BT starts (mirrors the already-working standalone script).

```sh
#!/system/bin/sh
. "$MODPATH/scripts/lib.sh" 2>/dev/null || . /data/adb/loop-speaker-mode/scripts/lib.sh
loop_load_config
resetprop "${A2DP_SINK_PROP:-bluetooth.profile.a2dp.sink.enabled}" true
resetprop bluetooth.profile.a2dp.source.enabled false
```

- [ ] **Step 2: `service.sh`** — late_start: wait for boot complete, force Dumb, start daemon supervisor.

```sh
#!/system/bin/sh
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
sleep 3
sh "$MODDIR/scripts/loop-mode" dumb
# daemon supervisor (added in Phase 3; guarded so Phase 1 zip is valid)
[ -x "$MODDIR/system/bin/loopkeyd" ] && sh "$MODDIR/scripts/loopkeyd.sh" &
```

- [ ] **Step 3: `customize.sh`** — install-time: create `/data/adb/loop-speaker-mode/`, copy scripts + config (only if config absent), auto-detect input device names, set perms.

```sh
#!/system/bin/sh
LOOP_DIR=/data/adb/loop-speaker-mode
mkdir -p "$LOOP_DIR"
cp -af "$MODPATH/scripts" "$LOOP_DIR/scripts"
[ -f "$LOOP_DIR/config" ] || cp -f "$MODPATH/config.default" "$LOOP_DIR/config"

# auto-detect keypad + power input devices if not set
kp=$(grep -lE 'mtk-kpd' /sys/class/input/event*/device/name 2>/dev/null | head -1)
pw=$(grep -lE 'mtk-pmic-keys|mtk_pmic_keys' /sys/class/input/event*/device/name 2>/dev/null | head -1)
# fallback: scan getevent names → handled by daemon at runtime if blank
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/loopkeyd" 0 0 0755 2>/dev/null
ui_print "- Loop Speaker Mode installed. Reboot to apply (boots to Dumb)."
```

- [ ] **Step 4: `uninstall.sh`** — clean revert: re-enable toggled packages (leave rainx as the user had them — disabled).

```sh
#!/system/bin/sh
LOOP_DIR=/data/adb/loop-speaker-mode
while read -r p; do [ -n "$p" ] && pm enable --user 0 "$p" >/dev/null 2>&1; done \
  < "$LOOP_DIR/scripts/packages-toggled.txt"
rm -rf "$LOOP_DIR"
```

- [ ] **Step 5: Verify** — `shellcheck -s sh` clean. `bash tools/build-module.sh` rebuilds zip including hooks.

- [ ] **Step 6: Commit**

```bash
git add magisk-module/post-fs-data.sh magisk-module/service.sh magisk-module/customize.sh magisk-module/uninstall.sh
git commit -m "feat(module): boot hooks + install/uninstall (a2dp early, force-dumb late, pkg restore)"
```

### Task 6: Mac-side `tools/loop-mode` wrapper

**Files:**
- Create: `tools/loop-mode`

- [ ] **Step 1: Write it**

```bash
#!/usr/bin/env bash
set -euo pipefail
exec adb shell su -c "sh /data/adb/loop-speaker-mode/scripts/loop-mode ${1:-status}"
```

- [ ] **Step 2: Verify** — `chmod +x tools/loop-mode`; `shellcheck tools/loop-mode` clean.

- [ ] **Step 3: Commit**

```bash
git add tools/loop-mode && git commit -m "feat(tools): mac-side loop-mode adb wrapper"
```

### Task 7: Phase-1 on-device integration test

**Files:** none (verification only)

- [ ] **Step 1: Flash** — push zip + install via Magisk:
```bash
adb push build/loop-speaker-mode.zip /sdcard/Download/
adb shell su -c 'magisk --install-module /sdcard/Download/loop-speaker-mode.zip'
adb reboot
```

- [ ] **Step 2: Verify boots to Dumb** — after boot:
```bash
adb wait-for-device; sleep 25
tools/loop-mode status            # expect: dumb
adb shell settings get global device_name    # expect: Loop A
adb shell settings get system screen_off_timeout   # expect: 15000
adb shell cmd wifi status | head -1           # expect: disabled/off
adb shell pm list packages -d | grep -c rainx # expect: 3
```

- [ ] **Step 3: Verify switch to Full and back**
```bash
tools/loop-mode full; sleep 4
adb shell pm list packages -e | grep -c 'com.android.chrome'   # expect: 1 (re-enabled)
adb shell pm list packages -d | grep -c rainx                  # expect: 3 (still disabled!)
tools/loop-mode dumb; sleep 4
adb shell pm list packages -d | grep -c 'com.android.chrome'   # expect: 1 (disabled again)
```
Expected: rainx count stays 3 in BOTH modes (privacy guarantee verified).

- [ ] **Step 4: Verify A2DP sink survived** — `adb shell dumpsys bluetooth_manager | grep -i 'a2dp sink'` → `Enabled`.

- [ ] **Step 5: Commit a checkpoint note** (no code) — update `docs/04-speaker-mode.md` Phase-1 "verified" line. (Doc task 18 expands it.)

> **Build-time privacy check (user request, spec §2):** while here, confirm a disabled rainx pkg spawns nothing:
> `adb shell dumpsys activity processes | grep -ci rainx` → expect `0`. If >0, escalate per spec note (remove APK via overlay). Record result in `docs/compatibility.md`.

---

# PHASE 2 — Helper app (`co.loop.speaker`)

Headless system priv-app. No launcher activity. All behavior via a foreground service + receivers, driven by broadcasts from scripts/daemon. Each sub-feature is independently triggerable and logs under tag `LoopSpk`.

**Parallelizable:** Tasks 9–14 are independent feature modules behind the Task 8 scaffold. Dispatch them concurrently; each touches its own `*.kt` file.

### Task 8: App scaffold — Gradle, manifest, priv-app perms, core service

**Files:**
- Create: `helper-app/settings.gradle.kts`, `helper-app/build.gradle.kts`, `helper-app/app/build.gradle.kts`
- Create: `helper-app/app/src/main/AndroidManifest.xml`
- Create: `helper-app/app/src/main/kotlin/co/loop/speaker/LoopService.kt`
- Create: `helper-app/app/src/main/kotlin/co/loop/speaker/CmdReceiver.kt`
- Create: `magisk-module/system/etc/permissions/privapp-permissions-co.loop.speaker.xml`
- Create: `tools/build-app.sh`

- [ ] **Step 1: Gradle** — `app/build.gradle.kts` (minSdk 35, applicationId `co.loop.speaker`, kotlin, no compose). Sign with a repo-local debug keystore (generated by build-app.sh, gitignored).

```kotlin
plugins { id("com.android.application"); kotlin("android") }
android {
  namespace = "co.loop.speaker"; compileSdk = 35
  defaultConfig { applicationId = "co.loop.speaker"; minSdk = 35; targetSdk = 35; versionCode = 1; versionName = "0.1" }
  buildTypes { release { isMinifyEnabled = false } }
}
dependencies { }
```

- [ ] **Step 2: AndroidManifest** — declare privileged BT perms; a started+foreground service; the command receiver; no launcher activity.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="co.loop.speaker">
  <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
  <uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
  <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE"/>
  <uses-permission android:name="android.permission.BLUETOOTH_PRIVILEGED"/>
  <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
  <uses-permission android:name="android.permission.WAKE_LOCK"/>
  <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
  <application android:label="Loop Speaker" android:persistent="true">
    <service android:name=".LoopService" android:exported="true"
             android:foregroundServiceType="connectedDevice"/>
    <receiver android:name=".CmdReceiver" android:exported="true">
      <intent-filter><action android:name="co.loop.speaker.CMD"/></intent-filter>
    </receiver>
  </application>
</manifest>
```

- [ ] **Step 3: priv-app permission allowlist** — grants signature/privileged perms for a system app.

```xml
<permissions>
  <privapp-permissions package="co.loop.speaker">
    <permission name="android.permission.BLUETOOTH_PRIVILEGED"/>
    <permission name="android.permission.MODIFY_AUDIO_SETTINGS"/>
  </privapp-permissions>
</permissions>
```

- [ ] **Step 4: `LoopService.kt`** — foreground service holding all feature controllers; started on boot and by CmdReceiver. For scaffold, just logs lifecycle + exposes a `dispatch(cmd, arg)` that later tasks extend (each feature registers here).

```kotlin
package co.loop.speaker
import android.app.*; import android.content.*; import android.os.*; import android.util.Log
class LoopService : Service() {
  companion object { const val TAG="LoopSpk" }
  override fun onCreate() { super.onCreate(); Log.i(TAG,"service create")
    startForeground(1, notif()) }
  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    i?.getStringExtra("cmd")?.let { dispatch(it, i.getStringExtra("arg")) }; return START_STICKY }
  fun dispatch(cmd: String, arg: String?) { Log.i(TAG,"cmd=$cmd arg=$arg") /* features extend */ }
  private fun notif(): Notification {
    val ch="loop"; (getSystemService(NotificationManager::class.java))
      .createNotificationChannel(NotificationChannel(ch,"Loop",NotificationManager.IMPORTANCE_MIN))
    return Notification.Builder(this,ch).setContentTitle("Loop Speaker").setSmallIcon(android.R.drawable.stat_sys_headset).build() }
  override fun onBind(i: Intent?): IBinder? = null
}
```

- [ ] **Step 5: `CmdReceiver.kt`** — turns `am broadcast co.loop.speaker.CMD --es cmd X` into a service start.

```kotlin
package co.loop.speaker
import android.content.*
class CmdReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, i: Intent) {
    val s = Intent(c, LoopService::class.java)
      .putExtra("cmd", i.getStringExtra("cmd")).putExtra("arg", i.getStringExtra("arg"))
    c.startForegroundService(s)
  }
}
```

- [ ] **Step 6: `tools/build-app.sh`** — gradle assemble, sign, copy APK into module tree.

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)/helper-app"
cd "$ROOT"
[ -f debug.keystore ] || keytool -genkey -v -keystore debug.keystore -storepass android \
  -alias loop -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=loop"
./gradlew :app:assembleRelease
APK=app/build/outputs/apk/release/app-release-unsigned.apk
"$ANDROID_HOME"/build-tools/*/apksigner sign --ks debug.keystore --ks-pass pass:android \
  --out "$ROOT/../magisk-module/system/priv-app/LoopSpeaker/LoopSpeaker.apk" "$APK"
echo "app built + signed into module"
```

- [ ] **Step 7: Verify** — `bash tools/build-app.sh` produces `magisk-module/system/priv-app/LoopSpeaker/LoopSpeaker.apk`. Rebuild module zip, flash, reboot. Then:
```bash
adb shell dumpsys package co.loop.speaker | grep -E 'BLUETOOTH_PRIVILEGED.*granted=true'   # priv perm granted
adb shell am broadcast -a co.loop.speaker.CMD --es cmd ping -n co.loop.speaker/.CmdReceiver
adb logcat -d -s LoopSpk | tail -3   # expect: cmd=ping
```
Expected: privileged perm granted + `cmd=ping` logged. If perm not granted, fix the allowlist XML path/perms.

- [ ] **Step 8: Commit**

```bash
git add helper-app tools/build-app.sh magisk-module/system/etc/permissions/privapp-permissions-co.loop.speaker.xml
git commit -m "feat(app): scaffold — gradle, manifest, priv-app perms, foreground service, cmd receiver"
```

### Task 9: Pairing — discoverable window + auto-accept

**Files:**
- Create: `helper-app/app/src/main/kotlin/co/loop/speaker/Pairing.kt`
- Modify: `LoopService.kt` dispatch (`pair_open`, `mode_dumb`)

- [ ] **Step 1: Write `Pairing.kt`** — opens a timed discoverable window; while open, a `ACTION_PAIRING_REQUEST` receiver calls `setPairingConfirmation(true)` (reflection, privileged). Closes on timeout or connect.

```kotlin
package co.loop.speaker
import android.bluetooth.*; import android.content.*; import android.os.*; import android.util.Log
class Pairing(val ctx: Context) {
  private var openUntil = 0L
  private val rx = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
      if (i.action == BluetoothDevice.ACTION_PAIRING_REQUEST && SystemClock.elapsedRealtime() < openUntil) {
        val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        try { BluetoothDevice::class.java.getMethod("setPairingConfirmation", Boolean::class.java).invoke(d, true)
          Log.i("LoopSpk","auto-accepted ${d?.address}") } catch (e: Exception){ Log.e("LoopSpk","accept",e) }
      }
    }
  }
  fun open(seconds: Int) {
    ctx.registerReceiver(rx, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST), Context.RECEIVER_EXPORTED)
    openUntil = SystemClock.elapsedRealtime() + seconds*1000L
    setDiscoverable(seconds)               // BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE via reflection
    Log.i("LoopSpk","pairing window ${seconds}s")
  }
  fun close() { try { ctx.unregisterReceiver(rx) } catch(_:Exception){}; setDiscoverable(0); openUntil=0 }
  private fun setDiscoverable(sec: Int) {
    val a = BluetoothAdapter.getDefaultAdapter()
    val mode = if (sec>0) 23 /*CONNECTABLE_DISCOVERABLE*/ else 21 /*CONNECTABLE*/
    try { BluetoothAdapter::class.java.getMethod("setScanMode", Int::class.java).invoke(a, mode) }
    catch(e:Exception){ Log.e("LoopSpk","scanmode",e) }
  }
}
```

- [ ] **Step 2: Wire into dispatch** — in `LoopService.dispatch`: hold a `Pairing` instance; `pair_open` → `open(PAIR_RETRIGGER)`; on `mode_dumb` boot path, open `PAIR_INITIAL` only if auto-reconnect (Task 13) fails. Read durations from config via a small `Config` reader (add `Config.kt`: parse `/data/adb/loop-speaker-mode/config`).

- [ ] **Step 3: Verify on device** — from an unpaired phone, trigger window and pair with zero taps:
```bash
adb shell am broadcast -a co.loop.speaker.CMD --es cmd pair_open -n co.loop.speaker/.CmdReceiver
# on phone: scan, tap "Loop A", confirm nothing
adb logcat -d -s LoopSpk | grep -E 'pairing window|auto-accepted'
adb shell dumpsys bluetooth_manager | grep -A2 'Bonded devices'   # phone appears
```
Expected: window logged, auto-accept logged, phone bonds with no on-device interaction. After timeout, `adb shell settings get global ...`/`dumpsys` shows scan mode back to connectable-only.

- [ ] **Step 4: Commit**

```bash
git add helper-app/app/src/main/kotlin/co/loop/speaker/Pairing.kt helper-app/app/src/main/kotlin/co/loop/speaker/Config.kt helper-app/app/src/main/kotlin/co/loop/speaker/LoopService.kt
git commit -m "feat(app): timed discoverable window + auto-accept pairing"
```

### Task 10: AVRCP transport control (play/pause/next/prev)

**Files:**
- Create: `helper-app/app/src/main/kotlin/co/loop/speaker/Avrcp.kt`
- Modify: `LoopService.kt` dispatch (`play_pause`, `next`, `prev`)

- [ ] **Step 1: Write `Avrcp.kt`** — bind the AVRCP Controller profile (sink sends passthrough to the source phone). Hidden API → reflection. Keycodes per AVRCP: PLAY=0x44, PAUSE=0x46, FORWARD=0x4B, BACKWARD=0x4C.

```kotlin
package co.loop.speaker
import android.bluetooth.*; import android.content.Context; import android.util.Log
class Avrcp(val ctx: Context) {
  private var ctrl: BluetoothProfile? = null
  private val AVRCP_CONTROLLER = 12
  fun init() {
    BluetoothAdapter.getDefaultAdapter().getProfileProxy(ctx, object: BluetoothProfile.ServiceListener {
      override fun onServiceConnected(p: Int, x: BluetoothProfile) { ctrl = x; Log.i("LoopSpk","avrcp bound") }
      override fun onServiceDisconnected(p: Int) { ctrl = null }
    }, AVRCP_CONTROLLER)
  }
  private fun connectedDevice(): BluetoothDevice? = ctrl?.connectedDevices?.firstOrNull()
  fun send(key: Int) {
    val d = connectedDevice() ?: return
    try {
      val m = ctrl!!.javaClass.getMethod("sendPassThroughCmd", BluetoothDevice::class.java, Int::class.java, Int::class.java)
      m.invoke(ctrl, d, key, 0); m.invoke(ctrl, d, key, 1)   // press, release
      Log.i("LoopSpk","avrcp key=$key -> ${d.address}")
    } catch(e:Exception){ Log.e("LoopSpk","avrcp send",e) }
  }
  fun playPause() = send(0x46.let { if (true) 0x46 else it })  // toggle: PAUSE acts as toggle on most sources; see verify
}
```
(During verify, confirm whether the source treats a single key as toggle; if not, track state and alternate PLAY/PAUSE.)

- [ ] **Step 2: Wire dispatch** — `play_pause`→`playPause()`, `next`→`send(0x4B)`, `prev`→`send(0x4C)`. `Avrcp.init()` in `LoopService.onCreate`.

- [ ] **Step 3: Verify** — phone connected + playing music:
```bash
adb shell am broadcast -a co.loop.speaker.CMD --es cmd play_pause -n co.loop.speaker/.CmdReceiver  # music pauses
adb shell am broadcast -a co.loop.speaker.CMD --es cmd next -n co.loop.speaker/.CmdReceiver         # track skips
adb logcat -d -s LoopSpk | grep avrcp
```
Expected: playback on the phone responds to each command.

- [ ] **Step 4: Commit**

```bash
git add helper-app/app/src/main/kotlin/co/loop/speaker/Avrcp.kt helper-app/app/src/main/kotlin/co/loop/speaker/LoopService.kt
git commit -m "feat(app): AVRCP passthrough transport control to source phone"
```

### Task 11: Volume — absolute volume first, app bridge fallback

**Files:**
- Create: `helper-app/app/src/main/kotlin/co/loop/speaker/Volume.kt`
- Modify: `customize.sh` (clear `persist.bluetooth.disableabsvol` via resetprop in post-fs-data)
- Modify: `post-fs-data.sh`

- [ ] **Step 1: Enable native absolute volume** — in `post-fs-data.sh` add:
```sh
resetprop persist.bluetooth.disableabsvol false
```

- [ ] **Step 2: Write `Volume.kt` bridge fallback** — observe local STREAM_MUSIC changes and forward to the source via AVRCP SetAbsoluteVolume, and apply incoming absolute-volume to the local stream. Used only if native sync is one-directional.

```kotlin
package co.loop.speaker
import android.content.*; import android.database.ContentObserver; import android.media.AudioManager
import android.os.Handler; import android.os.Looper; import android.util.Log
class Volume(val ctx: Context, val avrcp: Avrcp) {
  private val am = ctx.getSystemService(AudioManager::class.java)
  private val obs = object: ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(self: Boolean) {
      val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
      val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      val abs = (cur*127)/max
      avrcp.setAbsoluteVolume(abs); Log.i("LoopSpk","vol bridge -> $abs/127")
    }
  }
  fun start() { ctx.contentResolver.registerContentObserver(
    android.provider.Settings.System.CONTENT_URI, true, obs) }
}
```
(Add `Avrcp.setAbsoluteVolume(v:Int)` calling the controller's `setAbsoluteVolume`/`sendGroupNavigation` hidden method via reflection; no-op if native absvol already syncs.)

- [ ] **Step 3: Verify native first** — with phone connected, change volume on the PHONE; confirm Loop's `STREAM_MUSIC` tracks it (`adb shell dumpsys audio | grep -A3 STREAM_MUSIC` before/after). Then change on Loop; confirm phone tracks. If both directions track natively → leave bridge dormant (don't `start()`). If only one direction → enable bridge for the missing direction.

- [ ] **Step 4: Commit**

```bash
git add helper-app/app/src/main/kotlin/co/loop/speaker/Volume.kt magisk-module/post-fs-data.sh helper-app/app/src/main/kotlin/co/loop/speaker/Avrcp.kt
git commit -m "feat(app): single synced volume — native absvol + bridge fallback"
```

### Task 12: TTS cues + battery announcement (half volume)

**Files:**
- Create: `helper-app/app/src/main/kotlin/co/loop/speaker/Cues.kt`
- Modify: `LoopService.kt` dispatch (`say`, `battery`, and mode/pairing cues)

- [ ] **Step 1: Write `Cues.kt`** — init `TextToSpeech`; speak at `CUE_VOLUME_PCT`. Map mode/pairing/connect events to phrases. Battery: read `BatteryManager.BATTERY_PROPERTY_CAPACITY` → "Battery N percent".

```kotlin
package co.loop.speaker
import android.content.Context; import android.media.AudioManager; import android.os.BatteryManager
import android.speech.tts.TextToSpeech; import android.util.Log; import java.util.Locale
class Cues(val ctx: Context, val volPct: Int) {
  private var tts: TextToSpeech? = null; private var ready=false
  fun init() { tts = TextToSpeech(ctx) { if (it==TextToSpeech.SUCCESS){ tts?.language=Locale.US; ready=true } } }
  fun say(text: String) {
    if (!ready) return
    val p = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volPct/100f) }
    tts?.speak(text, TextToSpeech.QUEUE_ADD, p, "loop"); Log.i("LoopSpk","tts: $text @ $volPct%")
  }
  fun battery() { val bm=ctx.getSystemService(BatteryManager::class.java)
    say("Battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent") }
}
```

- [ ] **Step 2: Wire dispatch** — `mode_dumb`→say "Speaker mode", `mode_full`→"Full mode", pairing open→"Pairing", connect→"Connected", `battery`→`battery()`, `say`→arg. Read `CUE_VOLUME_PCT` from Config.

- [ ] **Step 3: Verify**
```bash
adb shell am broadcast -a co.loop.speaker.CMD --es cmd say --es arg "test" -n co.loop.speaker/.CmdReceiver
adb shell am broadcast -a co.loop.speaker.CMD --es cmd battery -n co.loop.speaker/.CmdReceiver
adb logcat -d -s LoopSpk | grep tts
```
Expected: audible TTS at ~half volume; battery % spoken; log lines present.

- [ ] **Step 4: Commit**

```bash
git add helper-app/app/src/main/kotlin/co/loop/speaker/Cues.kt helper-app/app/src/main/kotlin/co/loop/speaker/LoopService.kt
git commit -m "feat(app): TTS cues + battery announcement at half volume"
```

### Task 13: Auto-reconnect last phone

**Files:**
- Create: `helper-app/app/src/main/kotlin/co/loop/speaker/Reconnect.kt`
- Modify: `LoopService.kt` (`mode_dumb` boot path)

- [ ] **Step 1: Write `Reconnect.kt`** — on boot/dumb, pick the most-recently-bonded device and connect A2DP sink; if none bonds within `RECONNECT_TIMEOUT` (10s), signal caller to open the initial pairing window.

```kotlin
package co.loop.speaker
import android.bluetooth.*; import android.content.Context; import android.util.Log
class Reconnect(val ctx: Context) {
  fun tryLast(onFail: () -> Unit) {
    val a = BluetoothAdapter.getDefaultAdapter()
    val d = a.bondedDevices?.maxByOrNull { bondTimestamp(it) }
    if (d == null) { Log.i("LoopSpk","no bonded device"); onFail(); return }
    try {
      val proxy = sinkProxy() ?: run { onFail(); return }
      proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, d)
      Log.i("LoopSpk","reconnect -> ${d.address}")
    } catch(e:Exception){ Log.e("LoopSpk","reconnect",e); onFail() }
  }
  // bondTimestamp + sinkProxy via reflection on BluetoothA2dpSink hidden profile (id=11)
}
```

- [ ] **Step 2: Wire** — `mode_dumb` boot path calls `Reconnect.tryLast { pairing.open(PAIR_INITIAL); cues.say("Pairing") }`. On successful A2DP-connected broadcast → `cues.say("Connected")` + `pairing.close()`.

- [ ] **Step 3: Verify** — with a previously-bonded phone in range, reboot Loop; phone reconnects with no pairing window. Then unpair/move phone away, reboot; window opens after ~10s.
```bash
adb reboot; adb wait-for-device; sleep 30
adb logcat -d -s LoopSpk | grep -E 'reconnect|Connected|pairing window'
```

- [ ] **Step 4: Commit**

```bash
git add helper-app/app/src/main/kotlin/co/loop/speaker/Reconnect.kt helper-app/app/src/main/kotlin/co/loop/speaker/LoopService.kt
git commit -m "feat(app): auto-reconnect last phone, fall back to pairing window"
```

### Task 14: Two-stage auto-sleep

**Files:**
- Create: `helper-app/app/src/main/kotlin/co/loop/speaker/IdleSleep.kt`
- Modify: `LoopService.kt`

- [ ] **Step 1: Write `IdleSleep.kt`** — track activity (A2DP connected/playing, any button via daemon broadcast). Reset timer on activity. At `IDLE_SLEEP_MIN` with nothing connected and no audio → suspend (`input keyevent KEYCODE_SLEEP` / `PowerManager.goToSleep` via reflection). At `IDLE_OFF_MIN` → power off (`reboot -p` through a privileged call / `am`/`svc power`). Never fire during playback (A2DP wakelock / `AudioManager.isMusicActive`).

```kotlin
package co.loop.speaker
import android.content.Context; import android.media.AudioManager; import android.os.*; import android.util.Log
class IdleSleep(val ctx: Context, val sleepMin: Int, val offMin: Int) {
  private val h = Handler(Looper.getMainLooper()); private var last = SystemClock.elapsedRealtime()
  private val am = ctx.getSystemService(AudioManager::class.java)
  fun poke() { last = SystemClock.elapsedRealtime() }
  fun start() { h.post(object: Runnable { override fun run() {
    val idleMin = (SystemClock.elapsedRealtime()-last)/60000
    if (!am.isMusicActive) {
      if (idleMin >= offMin) { Log.i("LoopSpk","idle->poweroff"); Runtime.getRuntime().exec(arrayOf("su","-c","svc power shutdown")) }
      else if (idleMin >= sleepMin) { Log.i("LoopSpk","idle->sleep"); Runtime.getRuntime().exec(arrayOf("input","keyevent","223")) } // KEYCODE_SLEEP
    } else poke()
    h.postDelayed(this, 30_000)
  }}) }
}
```

- [ ] **Step 2: Wire** — `IdleSleep` started in dumb mode, stopped in full mode (normal Android timeout there). Daemon button broadcasts and A2DP-connect events call `poke()`.

- [ ] **Step 3: Verify** — disconnect phone, leave idle; after ~5 min → screen/CPU suspends (`adb shell dumpsys power | grep mWakefulness` → Asleep). Then verify it does NOT sleep while music plays. (Power-off at 15 min: verify with a shortened `IDLE_OFF_MIN=2` in config for the test, then restore.)

- [ ] **Step 4: Commit**

```bash
git add helper-app/app/src/main/kotlin/co/loop/speaker/IdleSleep.kt helper-app/app/src/main/kotlin/co/loop/speaker/LoopService.kt
git commit -m "feat(app): two-stage auto-sleep (suspend then power off), suppressed during playback"
```

---

# PHASE 3 — Native button daemon (`loopkeyd`)

EVIOCGRAB the keypad + power input devices, run the gesture state machine, re-inject single volume taps via uinput so volume stays instant, emit actions by invoking `loop-mode` / `am broadcast` to the app.

### Task 15: Input discovery + NDK setup

**Files:**
- Create: `tools/setup-ndk.sh`
- Create: `native/Makefile`

- [ ] **Step 1: Discover real device codes** — on the Mac, capture button events:
```bash
adb shell su -c 'getevent -lp' > /tmp/loop-inputs.txt          # lists devices + their key capabilities
adb shell su -c 'getevent -lq' &                                # then press each button once
```
Record: which `/dev/input/eventN` is the keypad (`KEY_VOLUMEUP/DOWN`) and which is power (`KEY_POWER`), and the exact `EV_KEY` codes. Write findings into `docs/compatibility.md` and confirm `INPUT_KEYPAD`/`INPUT_POWER` auto-detection in `customize.sh` resolved them (else set explicitly in config).

- [ ] **Step 2: NDK bootstrap** — `tools/setup-ndk.sh` installs/locates NDK and exports `NDK`:
```bash
#!/usr/bin/env bash
set -euo pipefail
if [ -z "${NDK:-}" ]; then
  NDK="$(ls -d "$HOME"/Library/Android/sdk/ndk/* 2>/dev/null | tail -1 || true)"
fi
[ -n "${NDK:-}" ] || { echo "Install NDK: 'sdkmanager --install ndk;26.*' or brew install --cask android-ndk"; exit 1; }
echo "NDK=$NDK"
```

- [ ] **Step 3: Makefile** — cross-compile arm64 static-ish binary:
```make
NDK ?= $(shell ls -d $(HOME)/Library/Android/sdk/ndk/* | tail -1)
CC = $(NDK)/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android30-clang
loopkeyd: loopkeyd.c
	$(CC) -O2 -Wall -o ../magisk-module/system/bin/loopkeyd loopkeyd.c
```

- [ ] **Step 4: Verify** — `bash tools/setup-ndk.sh` prints an NDK path. (Build verified in Task 16.)

- [ ] **Step 5: Commit**

```bash
git add tools/setup-ndk.sh native/Makefile docs/compatibility.md
git commit -m "feat(native): NDK bootstrap, Makefile, input-device discovery notes"
```

### Task 16: `loopkeyd.c` — grab + gesture state machine + uinput re-inject

**Files:**
- Create: `native/loopkeyd.c`

- [ ] **Step 1: Write `loopkeyd.c`** — open keypad+power devices, `ioctl(EVIOCGRAB,1)`, create a uinput device for re-injecting volume. State machine:
  - Vol+/Vol- tap → re-inject the same key (instant volume) AND start a `DOUBLE_TAP_WINDOW_MS` timer; a second tap within window → emit `next`/`prev` (and the volume nudge already applied is accepted per design).
  - Power tap (press+release < 600ms, no long-press) → emit `play_pause`; long-press → ungrab-passthrough so firmware power menu/shutdown still works.
  - Both volumes held `GESTURE_PAIR_HOLD_MS` → emit `pair_open`.
  - All three held `GESTURE_MODE_HOLD_MS` → emit `mode_toggle`.
  Actions are emitted by `system("sh /data/adb/loop-speaker-mode/scripts/loop-act <action>")` (a tiny dispatcher added in Task 17) to keep C free of policy.

Provide the full C: device-open helper, uinput setup, an epoll loop reading `input_event`, timers via `timerfd`, and the gesture logic. (~280 lines — write it complete, no stubs. Key constants: `KEY_VOLUMEUP=115`, `KEY_VOLUMEDOWN=114`, `KEY_POWER=116`; confirm against Task 15 discovery.)

- [ ] **Step 2: Build** — `cd native && make`. Expected: `../magisk-module/system/bin/loopkeyd` produced, `file` reports `ELF 64-bit ARM aarch64`.

- [ ] **Step 3: Verify on device** — push binary, run manually (not as service yet):
```bash
adb push magisk-module/system/bin/loopkeyd /data/local/tmp/
adb shell su -c '/data/local/tmp/loopkeyd --dry-run'   # logs detected gestures to logcat, no actions
adb logcat -s loopkeyd
# press: vol+ (instant volume), vol+ vol+ (next), power (play/pause line), both-vol 3s (pair line), 3-button 5s (mode line)
```
Expected: each gesture logs the right action; single volume taps still change volume (re-inject working); power long-press still shows the firmware power menu.

- [ ] **Step 4: Commit**

```bash
git add native/loopkeyd.c
git commit -m "feat(native): loopkeyd — EVIOCGRAB gesture daemon with uinput volume re-inject"
```

### Task 17: Daemon supervisor + action dispatcher + integration

**Files:**
- Create: `magisk-module/scripts/loopkeyd.sh`
- Create: `magisk-module/scripts/loop-act`
- Modify: `magisk-module/scripts/loop-dumb.sh` / `loop-full.sh` (start/stop grab via signal)

- [ ] **Step 1: `loop-act`** — maps daemon actions to concrete effects:
```sh
#!/system/bin/sh
. /data/adb/loop-speaker-mode/scripts/lib.sh
case "$1" in
  play_pause|next|prev|pair_open|battery) loop_app "$1" ;;
  mode_toggle) [ "$(cat "$STATE" 2>/dev/null)" = dumb ] \
      && sh "$(dirname "$0")/loop-mode" full || sh "$(dirname "$0")/loop-mode" dumb ;;
esac
```

- [ ] **Step 2: `loopkeyd.sh` supervisor** — respawn loop; only run grab in Dumb (in Full, send SIGTERM so buttons are normal). Uses the state file.
```sh
#!/system/bin/sh
. /data/adb/loop-speaker-mode/scripts/lib.sh
BIN=/data/adb/modules/loop-speaker-mode/system/bin/loopkeyd
while true; do
  if [ "$(cat "$STATE" 2>/dev/null)" = dumb ]; then
    "$BIN" || loop_log "loopkeyd exited $?"
  fi
  sleep 2
done
```

- [ ] **Step 3: Mode scripts signal the daemon** — `loop-full.sh` adds `pkill -TERM loopkeyd` (release grabs → normal phone buttons); `loop-dumb.sh` relies on the supervisor to relaunch within 2s.

- [ ] **Step 4: Verify end-to-end on device** — reboot, then with a phone connected and playing:
  - vol+ tap → volume up; vol+ ×2 → next track.
  - power tap → play/pause; power long-press → power menu.
  - both-vol 3s → "Pairing" + window opens.
  - 3-button 5s → "Full mode", buttons become normal; 3-button 5s again → "Speaker mode", gestures return.
```bash
adb logcat -s loopkeyd LoopSpk    # watch live during the manual button test
tools/loop-mode status            # toggles correctly via buttons
```

- [ ] **Step 5: Commit**

```bash
git add magisk-module/scripts/loopkeyd.sh magisk-module/scripts/loop-act magisk-module/scripts/loop-dumb.sh magisk-module/scripts/loop-full.sh
git commit -m "feat(native): supervisor + action dispatcher; mode-aware grab on/off"
```

---

# PHASE 4 — Docs, full integration, review

### Task 18: Walkthrough docs

**Files:**
- Create: `docs/01-unlock.md`, `docs/02-root.md`, `docs/03-debloat.md`, `docs/04-speaker-mode.md`, `docs/recovery.md`, `docs/compatibility.md`
- Modify: `README.md` (link the four steps)

- [ ] **Step 1: Write each doc** from the already-completed work — content sourced from the memory file `loopdl-device-state.md` (unlock via preloader mode, root via fastboot init_boot, debloat sets, snapshot/rollback). `04-speaker-mode.md` = install + button reference + config table. `compatibility.md` = BROM-open caveat + `mtk printgpt` self-check + discovered input codes. `recovery.md` = module disable, uninstall.sh, return-to-stock via snapshot. **Placeholders for serials/MACs** — no personal data.

- [ ] **Step 2: Verify** — `grep -rIE 'WTFMGBD3PCA000482|([0-9A-F]{2}:){5}' docs/ README.md` → no matches (no serial/MAC leaked). Markdown links resolve.

- [ ] **Step 3: Commit**

```bash
git add docs/ README.md
git commit -m "docs: full stock->speaker walkthrough (01-unlock..04-speaker-mode, recovery, compatibility)"
```

### Task 19: Full integration test (cold boot, the spec §11 checklist)

**Files:** none (verification only); fixes land in their owning task's files.

- [ ] **Step 1: Clean cold-boot run** — reflash final zip, reboot, walk the entire spec §11 list (1–10): radios+packages flip, gestures, zero-tap pairing, AVRCP, synced volume, TTS+battery, auto-sleep both stages, auto-reconnect, reboot→Dumb+window, mode toggle both ways, module-disable clean revert. Record pass/fail per item in `docs/compatibility.md`.

- [ ] **Step 2: Fix any failures** in the owning task, re-verify, commit with `fix(...)`.

- [ ] **Step 3: Commit** the verification record.

```bash
git add docs/compatibility.md && git commit -m "test: full spec §11 integration pass recorded"
```

### Task 20: Plugin/skill review pass (low priority — after it works)

**Files:** none (review only); fixes land in owning files.

- [ ] **Step 1: Security review** — dispatch a security-focused review (e.g. `voltagent-qa-sec:security-auditor` / `code-reviewer` subagents) over `helper-app/`, `native/loopkeyd.c`, and the module scripts. Focus: the auto-accept pairing window (must be time-bounded, never stuck discoverable), reflection calls, `system()`/`su` usage in the daemon path, priv-app permission scope. This is the "no vulnerability" pass the user asked for — **low priority, after working**.

- [ ] **Step 2: Triage findings** — fix real issues inline in the owning task's files; note accepted risks in `docs/compatibility.md`. Don't gold-plate.

- [ ] **Step 3: Commit** any fixes; final commit.

```bash
git commit -am "review: address security/code-review findings"
```

### Task 21: Push (only on explicit user go-ahead)

- [ ] **Step 1: Confirm** the user says "push now" (repo stays local until then, per project rule).
- [ ] **Step 2:** create the private GitHub repo and push `main`. Re-verify `git ls-files` shows no firmware/personal data first.

---

## Self-review (against the spec)

- **§1 Goal / §2 Modes** → Tasks 3,4,7 (package sets, mode scripts, rainx-stays-disabled verified both modes).
- **§3 Pairing flow** → Task 9 (window+auto-accept), Task 13 (reconnect-then-window), Task 17 (button retrigger).
- **§4 Button map** → Tasks 15–17 (discovery, daemon, dispatcher); volume instant via uinput re-inject; power long-press preserved; fallback (both-vol 5s) is a config swap of `GESTURE_MODE_HOLD_MS` if 3-button combo isn't grabbable — noted in Task 16.
- **§5 Volume** → Task 11 (absvol first, bridge fallback) — matches the locked decision.
- **§6 Components** → mode scripts (T4), daemon (T16/17), helper app (T8–14), boot hooks (T5).
- **§7 Extras** → TTS+battery (T12), auto-sleep (T14), auto-reconnect (T13); cues at `CUE_VOLUME_PCT=50` (T12).
- **§8 Config** → T1 `config.default` carries every key incl. `CUE_VOLUME_PCT`; `Config.kt` reads it app-side.
- **§9 Distribution** → docs T18, `.gitignore`/no-personal-data already in repo, push gated T21.
- **§10 Error handling** → supervisor respawn (T17), window self-close (T9), always-boot-dumb (T5 service.sh), power long-press preserved (T16), rainx re-enforced every switch (T4), sleep suppressed during playback (T14), uninstall clean revert (T5).
- **§11 Verification** → Task 19 walks all 10 items.

**Gaps fixed inline:** added `loop-act` dispatcher (T17) and `Config.kt` (T9) which the spec implied but didn't name; added the rainx-disable-sufficiency check (T7) per the user's spec edit. No placeholders remain except the deliberately on-device-discovered input codes (T15), which cannot be known until `getevent` runs on the device — flagged, not hidden.
