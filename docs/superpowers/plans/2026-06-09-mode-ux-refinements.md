# Loop Speaker Mode — UX Refinements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the dumb/full mode UX so it stops "looking like a phone": reliable button gestures, a real launcher, a fully-off screen in dumb mode, and a clean way back from full mode.

**Architecture:** The native `loopkeyd` daemon already grabs the physical buttons in dumb mode only. We rework its gesture state machine (delayed-volume decoding + new Power+Vol-Down mode gesture), fix the full-mode race so the daemon truly releases the keys, add a Quick-Settings tile (app) that signals the always-running root supervisor to return to dumb, power the screen off on dumb entry, and install Lawnchair as the full-mode launcher.

**Tech Stack:** C (arm64, NDK), Kotlin (Android priv-app, AGP 8.7.3/Kotlin 2.0.21/JDK17), POSIX sh (Magisk module), adb.

**Key facts (verified on-device 2026-06-09):**
- Device topology: `mtk-pmic-keys`=event0 (VOL_UP 115 + POWER 116), `mtk-kpd`=event1 (VOL_DOWN 114). VOL_UP and POWER share one chip; VOL_DOWN is on the other.
- PMIC hardware force-reboot fires on a long **Power+Vol-Up** hold (~8–10s) and is **below the OS** — uninterceptable. The new mode gesture deliberately uses **Power+Vol-Down** to avoid it.
- Daemon runs (and grabs) **only while `state==dumb`**, supervised by `loopkeyd.sh` (a root `while` loop started by `service.sh`, alive in BOTH modes).
- App (`co.loop.speaker`, uid u0a1xx) is a priv-app with **no root/su**. It cannot write to `/data/adb` (700 root) or run `pm`/`svc`. It CAN write to its own `filesDir` (`/data/data/co.loop.speaker/files/`), which Magisk root can read.
- Runtime config lives at `/data/adb/loop-speaker-mode/config` and is **NOT overwritten on reinstall** (`customize.sh`: `[ -f config ] || cp`). Changing `config.default` alone does NOT change a device that already has a config — the live config must be updated explicitly (Task 7).
- Every boot is forced to dumb by `service.sh:16` (`loop-mode dumb`), regardless of saved state. Safety net confirmed.

**Gesture scheme being implemented:**
| Gesture | Action | Active in |
|---|---|---|
| Vol +/- (single) | volume (delayed ~300ms decode) | dumb |
| Vol + double / Vol - double | next / prev | dumb |
| Power tap (<600ms) | play/pause | dumb |
| Power long (no vol) | release grab → firmware power menu | dumb |
| **Both volumes ≥1.5s** | pairing window | dumb |
| **Power + Vol-Down ≥5s** | mode toggle (→ full) | dumb |
| **QS tile "Speaker Mode"** | mode toggle (→ dumb) | full |

---

## File Structure

- `magisk-module/scripts/loop-full.sh` — **fix ordering** so daemon stops before supervisor can relaunch it.
- `magisk-module/scripts/loopkeyd.sh` — supervisor also polls the app's `req_dumb` trigger file while in full mode.
- `magisk-module/scripts/loop-dumb.sh` — power the panel off on dumb entry.
- `magisk-module/config.default` — new gesture thresholds.
- `native/loopkeyd.c` — delayed-volume decode + Power+Vol-Down mode gesture (replaces 3-button combo).
- `helper-app/app/src/main/kotlin/co/loop/speaker/SpeakerTile.kt` — **new** Quick-Settings TileService.
- `helper-app/app/src/main/AndroidManifest.xml` — register the tile service.
- Host/device only (not committed): Lawnchair APK install + set-default-home (Task 6).

Each task is independently testable on-device via adb.

---

## Task 1: Fix full-mode daemon release race (F2)

**Files:**
- Modify: `magisk-module/scripts/loop-full.sh`

**Problem:** `loop-full.sh` runs `pkill -TERM loopkeyd` (line 8) but writes `state=full` LAST (line 20), after the slow 29-app re-enable loop. For those seconds `state` is still `dumb`, so the supervisor relaunches the daemon, which re-grabs the buttons. Result: phone buttons stay hijacked in full mode.

**Fix:** Write `state=full` FIRST, then kill the daemon. The supervisor's next 2s tick sees `full` and does not relaunch.

- [ ] **Step 1: Reorder loop-full.sh**

Replace the top of `magisk-module/scripts/loop-full.sh` (lines 4–8) so the state flip precedes the kill:

```sh
loop_log "entering FULL mode"

# Flip state to full BEFORE killing the daemon. The supervisor (loopkeyd.sh) only
# (re)launches the grabbing daemon while state==dumb; if we killed first and wrote
# state later (after the slow package re-enable loop), the supervisor would relaunch
# it in the gap and the buttons would stay grabbed in full mode (F2).
echo full > "$STATE"

# release the button grab so phone buttons are 100% native in full mode.
# The daemon ungrabs all devices on SIGTERM (main() cleanup), restoring factory keys.
pkill -TERM loopkeyd 2>/dev/null
```

Then delete the now-duplicate `echo full > "$STATE"` near the end (old line 20).

- [ ] **Step 2: Deploy + verify on-device**

(Assumes the module is already installed; this script lives at `/data/adb/loop-speaker-mode/scripts/loop-full.sh`, refreshed by `customize.sh` on reinstall — for a quick iteration push it directly.)

```bash
ROOT=/Users/haohowareyou/Documents/Claude/personal/loopdl/loopdl-speaker
adb push "$ROOT/magisk-module/scripts/loop-full.sh" /data/local/tmp/loop-full.sh
adb shell 'su -c "cp /data/local/tmp/loop-full.sh /data/adb/loop-speaker-mode/scripts/loop-full.sh && chmod 755 /data/adb/loop-speaker-mode/scripts/loop-full.sh"'
# from dumb, toggle to full, then check the daemon is gone:
adb shell 'su -c "sh /data/adb/loop-speaker-mode/scripts/loop-mode full"'; sleep 7
adb shell 'su -c "pgrep -l loopkeyd || echo NO-DAEMON-GOOD"'
```

Expected: `NO-DAEMON-GOOD` (daemon not running in full mode). Then physically confirm volume/power keys behave like a normal phone.

- [ ] **Step 3: Commit**

```bash
git add magisk-module/scripts/loop-full.sh
git commit -m "fix(full): flip state before killing daemon so it doesn't relaunch+regrab"
```

---

## Task 2: Daemon gesture rework — delayed volume + Power+Vol-Down mode

**Files:**
- Modify: `native/loopkeyd.c` (main event loop ~lines 387–520; header comment ~22–30)

**Why:** Instant volume reinjection makes the both-volume pairing combo unreliable (presses score as taps → "next/prev"), and the 3-button mode combo collides with the hardware reboot. We switch volume to a short delayed decode (so "both volumes" is reliably distinguished from taps) and replace the mode gesture with **Power+Vol-Down ≥5s**.

**Design (single decision timer `t_vol`, reusing `double_tap_ms` as the window):**
- Volume PRESS, other volume already down → **combo**: cancel pending volume, arm `t_pair`.
- Volume PRESS, same code already pending → **double tap** → emit next/prev now.
- Volume PRESS, otherwise → set pending, arm `t_vol`; do **not** reinject yet.
- `t_vol` expiry → single tap → reinject the pending volume key now.
- Power held with Vol-Down down → arm `t_mode` (5s) → emit `mode_toggle`.
- Power long-press passthrough only when **no** volume key is down.
- Power release: `play_pause` only if it was a short tap (released before `t_pwr` fired) and not consumed/passthrough.

- [ ] **Step 1: Update the header gesture comment**

In `native/loopkeyd.c`, replace the gesture list (lines ~22–30) with:

```c
 * Gestures (thresholds read from config, defaults below):
 *   vol+/vol- single tap        -> re-inject (volume) after DOUBLE_TAP_WINDOW_MS decode delay
 *   vol+/vol- double tap        -> "next" / "prev"   (within DOUBLE_TAP_WINDOW_MS)
 *   power short press (<600ms)   -> "play_pause"
 *   power long press (no vol)    -> release grab on power fd / pass through
 *   both volumes held            -> "pair_open"   (GESTURE_PAIR_HOLD_MS)
 *   power + vol-down held        -> "mode_toggle" (GESTURE_MODE_HOLD_MS)  [-> full mode]
 *
 * Volume is decoded with a short delay (DOUBLE_TAP_WINDOW_MS): on the first press we
 * do NOT act, so that a second key (other volume = combo, or same key = double tap)
 * can be recognised first. A lone press fires the volume nudge when the window expires.
```

- [ ] **Step 2: Replace the gesture state declarations**

Replace lines ~389–393 (the `int vup_down...` block) with:

```c
    int vup_down = 0, vdn_down = 0, pwr_down = 0;
    int vol_pending = 0;        /* a volume key code awaiting decode (0 = none) */
    int pwr_passthrough = 0;    /* grab released on power fd for power menu */
    int pwr_consumed = 0;       /* power was part of mode combo -> no play_pause on release */
    int pwr_long = 0;           /* power held past short threshold -> not a tap */
```

- [ ] **Step 3: Replace the `t_dbl` timer handler**

Replace the `TAG_T_DBL` block (lines ~405–412) with the volume-decode expiry:

```c
            if (tag == TAG_T_DBL) {
                drain_timer(t_dbl);
                /* decode window closed with no second key: a lone volume tap.
                 * Re-inject the nudge now (delayed-volume). */
                if (vol_pending) {
                    if (!dry_run && ufd >= 0) uinput_tap(ufd, vol_pending);
                    else logln("dry: reinject %s",
                               vol_pending == KEY_VOLUMEUP ? "VOLUP" : "VOLDOWN");
                    vol_pending = 0;
                }
                continue;
            }
```

- [ ] **Step 4: Replace the `t_mode` handler (Power+Vol-Down)**

Replace the `TAG_T_MODE` block (lines ~425–432) with:

```c
            if (tag == TAG_T_MODE) {
                drain_timer(t_mode);
                /* mode = POWER + VOL-DOWN held for mode_hold_ms (NOT vol-up: that
                 * combo is the PMIC hardware reboot we can't intercept). */
                if (pwr_down && vdn_down && !vup_down) {
                    emit("mode_toggle");
                    pwr_consumed = 1;  /* don't fire play_pause when power is released */
                }
                continue;
            }
```

- [ ] **Step 5: Replace the `t_pwr` handler (passthrough guard)**

Replace the `TAG_T_PWR` block (lines ~433–447) with:

```c
            if (tag == TAG_T_PWR) {
                drain_timer(t_pwr);
                pwr_long = 1;  /* power is now a long press, not a tap */
                /* power long-press with NO volume down -> hand the power menu to
                 * firmware by releasing the grab on the power fd. If any volume is
                 * down we're forming the mode combo; do nothing. */
                if (pwr_down && !vup_down && !vdn_down) {
                    logln("power long-press: passthrough");
                    if (!dry_run && !pwr_passthrough) {
                        ioctl(devs[power_idx], EVIOCGRAB, 0);
                        pwr_passthrough = 1;
                    }
                }
                continue;
            }
```

- [ ] **Step 6: Replace the volume key handling**

Replace the volume branch (lines ~460–495, the `if (ie.code == KEY_VOLUMEUP || ie.code == KEY_VOLUMEDOWN) { ... }` block) with:

```c
                if (ie.code == KEY_VOLUMEUP || ie.code == KEY_VOLUMEDOWN) {
                    int is_up = (ie.code == KEY_VOLUMEUP);
                    if (pressed) {
                        int other_down = is_up ? vdn_down : vup_down;
                        if (is_up) vup_down = 1; else vdn_down = 1;

                        if (other_down) {
                            /* both volumes down -> pair/mode combo candidate.
                             * Cancel any pending single-volume decode. */
                            vol_pending = 0;
                            timer_disarm(t_dbl);
                            timer_arm(t_pair, pair_hold_ms);
                            if (pwr_down) timer_arm(t_mode, mode_hold_ms);
                        } else if (vol_pending == ie.code) {
                            /* second tap of the same key within the window -> next/prev */
                            emit(is_up ? "next" : "prev");
                            vol_pending = 0;
                            timer_disarm(t_dbl);
                        } else {
                            /* first press: defer. Decode on t_dbl expiry or a 2nd key. */
                            vol_pending = ie.code;
                            timer_arm(t_dbl, double_tap_ms);
                        }
                    } else { /* released */
                        if (is_up) vup_down = 0; else vdn_down = 0;
                        if (!(vup_down && vdn_down)) {
                            timer_disarm(t_pair);
                        }
                        if (!(pwr_down && vdn_down)) {
                            timer_disarm(t_mode);
                        }
                    }
                } else if (ie.code == KEY_POWER) {
                    if (pressed) {
                        pwr_down = 1;
                        pwr_long = 0;
                        timer_arm(t_pwr, power_short_ms);
                        if (vdn_down && !vup_down) timer_arm(t_mode, mode_hold_ms);
                    } else { /* released */
                        pwr_down = 0;
                        timer_disarm(t_mode);
                        if (pwr_passthrough) {
                            if (!dry_run) ioctl(devs[power_idx], EVIOCGRAB, 1);
                            pwr_passthrough = 0;
                            logln("power released: re-grabbed");
                        } else if (pwr_consumed) {
                            timer_disarm(t_pwr);
                            pwr_consumed = 0;
                        } else if (!pwr_long) {
                            timer_disarm(t_pwr);
                            emit("play_pause");
                        } else {
                            timer_disarm(t_pwr);
                        }
                    }
                }
```

(The old separate `KEY_POWER` branch at ~496–519 is fully replaced by the `else if (ie.code == KEY_POWER)` above — make sure there is no leftover duplicate power branch.)

- [ ] **Step 7: Compile (host, NDK) and verify it builds clean**

```bash
cd /Users/haohowareyou/Documents/Claude/personal/loopdl/loopdl-speaker/native
bash ../tools/setup-ndk.sh 2>/dev/null || true
make
```

Expected: arm64 ELF `loopkeyd` produced, `-Wall -Wextra` clean (no warnings).

- [ ] **Step 8: Dry-run the logic on-device (single-key gestures only)**

Deploy the new binary and run `--dry-run` to confirm taps/double-taps decode (combos need physical multi-key and are tested in Task 7):

```bash
ROOT=/Users/haohowareyou/Documents/Claude/personal/loopdl/loopdl-speaker
adb push "$ROOT/native/loopkeyd" /data/local/tmp/loopkeyd
adb shell 'su -c "chmod 755 /data/local/tmp/loopkeyd"'
# stop the grabbing daemon first so dry-run can read events:
adb shell 'su -c "echo full > /data/adb/loop-speaker-mode/state; pkill -TERM loopkeyd"'; sleep 2
adb shell 'su -c "/data/local/tmp/loopkeyd --dry-run"' &
# now physically: tap vol-up (expect "dry: reinject VOLUP" ~300ms later),
# double-tap vol-up (expect "ACTION next"), tap power (expect "ACTION play_pause").
```

Expected log lines: lone tap → `dry: reinject VOLUP/VOLDOWN`; double tap → `ACTION next`/`prev`; power tap → `ACTION play_pause`. Ctrl-C, then `adb shell 'su -c "echo dumb > /data/adb/loop-speaker-mode/state"'` to restore.

- [ ] **Step 9: Commit**

```bash
git add native/loopkeyd.c
git commit -m "feat(daemon): delayed-volume decode + Power+Vol-Down mode gesture"
```

---

## Task 3: Update gesture thresholds in config.default

**Files:**
- Modify: `magisk-module/config.default`

- [ ] **Step 1: Edit thresholds**

In `magisk-module/config.default` change:

```
GESTURE_PAIR_HOLD_MS=1500
GESTURE_MODE_HOLD_MS=5000
DOUBLE_TAP_WINDOW_MS=300
```

(`PAIR` drops 3000→1500; `MODE` stays 5000; `DOUBLE_TAP_WINDOW_MS` now doubles as the volume decode delay — keep 300.)

- [ ] **Step 2: Commit**

```bash
git add magisk-module/config.default
git commit -m "config: pair hold 1.5s; document double-tap window as volume decode delay"
```

(The live on-device config is updated in Task 7 — `config.default` does not overwrite an existing config.)

---

## Task 4: Power the screen off on dumb entry

**Files:**
- Modify: `magisk-module/scripts/loop-dumb.sh`

**Why:** Dumb mode should be dark (screen unused → saves power). The helper app's `IdleSleep` tries `input keyevent 223` from the app uid, which lacks permission; doing it from the root script is reliable. The daemon grabs power, so the panel won't wake on a power tap.

- [ ] **Step 1: Add screen-off on entry**

In `magisk-module/scripts/loop-dumb.sh`, replace the screen-policy line (currently `settings put system screen_off_timeout 15000`, line ~22) with:

```sh
# Dumb mode is screen-dark: power the panel off now (root context; the app's own
# IdleSleep can't because it runs unprivileged). Keep a short timeout as a backstop
# in case something briefly wakes it (charger insert, etc.). The daemon grabs the
# power key, so a power tap maps to play/pause and does NOT wake the panel.
settings put system screen_off_timeout 10000
input keyevent 223   # KEYCODE_SLEEP
```

- [ ] **Step 2: Deploy + verify**

```bash
ROOT=/Users/haohowareyou/Documents/Claude/personal/loopdl/loopdl-speaker
adb push "$ROOT/magisk-module/scripts/loop-dumb.sh" /data/local/tmp/loop-dumb.sh
adb shell 'su -c "cp /data/local/tmp/loop-dumb.sh /data/adb/loop-speaker-mode/scripts/loop-dumb.sh && chmod 755 /data/adb/loop-speaker-mode/scripts/loop-dumb.sh"'
adb shell 'su -c "sh /data/adb/loop-speaker-mode/scripts/loop-mode dumb"'; sleep 3
adb shell 'su -c "dumpsys power | grep -i mWakefulness="'
```

Expected: `mWakefulness=Asleep`. Physically confirm the screen is off and a power tap does not wake it.

- [ ] **Step 3: Commit**

```bash
git add magisk-module/scripts/loop-dumb.sh
git commit -m "feat(dumb): power the panel off on entry (screen-dark speaker)"
```

---

## Task 5: Quick-Settings tile "Speaker Mode" (full → dumb)

**Files:**
- Create: `helper-app/app/src/main/kotlin/co/loop/speaker/SpeakerTile.kt`
- Modify: `helper-app/app/src/main/AndroidManifest.xml`
- Modify: `magisk-module/scripts/loopkeyd.sh`

**Mechanism:** The app cannot run root commands. The tile writes a trigger file in the app's own `filesDir`; the always-running root supervisor (`loopkeyd.sh`) polls for it while in full mode and runs `loop-mode dumb`. The trigger path is `/data/data/co.loop.speaker/files/req_dumb`.

- [ ] **Step 1: Create the TileService**

Create `helper-app/app/src/main/kotlin/co/loop/speaker/SpeakerTile.kt`:

```kotlin
package co.loop.speaker

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import java.io.File

/**
 * "Speaker Mode" Quick-Settings tile — the full→dumb return path.
 *
 * The app is unprivileged and cannot run pm/svc/loop-mode. Instead it drops a trigger
 * file in its own filesDir; the root supervisor (loopkeyd.sh) polls for it while in
 * full mode and runs `loop-mode dumb`, then deletes it. filesDir is app-private but
 * Magisk root can read it.
 */
class SpeakerTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Speaker Mode"
            updateTile()
        }
    }

    override fun onClick() {
        try {
            File(filesDir, "req_dumb").writeText("1")
            Log.i("LoopSpk", "tile: requested dumb mode")
        } catch (e: Exception) {
            Log.e("LoopSpk", "tile write failed", e)
        }
        qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
    }
}
```

- [ ] **Step 2: Register the tile in the manifest**

In `helper-app/app/src/main/AndroidManifest.xml`, add inside `<application>` (after the `BootReceiver`):

```xml
    <service android:name=".SpeakerTile"
             android:exported="true"
             android:icon="@android:drawable/stat_sys_headset"
             android:label="Speaker Mode"
             android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
      <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE"/>
      </intent-filter>
    </service>
```

(Uses the framework drawable `@android:drawable/stat_sys_headset`, so no app `res/` is needed.)

- [ ] **Step 3: Make the supervisor poll the trigger in full mode**

Replace the loop body in `magisk-module/scripts/loopkeyd.sh` (lines ~13–18) with:

```sh
REQ=/data/data/co.loop.speaker/files/req_dumb

while true; do
  if [ "$(cat "$STATE" 2>/dev/null)" = dumb ]; then
    "$BIN" >"$DLOG" 2>&1 || loop_log "loopkeyd exited $?"
  else
    # full mode: the grabbing daemon is stopped. Watch for the QS tile's request
    # to return to dumb (the app can't run loop-mode itself; we do it as root).
    if [ -f "$REQ" ]; then
      rm -f "$REQ"
      loop_log "tile requested dumb"
      sh "$LOOP_DIR/scripts/loop-mode" dumb
    fi
  fi
  sleep 2
done
```

- [ ] **Step 4: Verify the root→app-file read path works (deploy supervisor + app)**

After building/installing the app (Task 7 covers the full build; for this check, install the new APK and push the supervisor):

```bash
ROOT=/Users/haohowareyou/Documents/Claude/personal/loopdl/loopdl-speaker
adb push "$ROOT/magisk-module/scripts/loopkeyd.sh" /data/local/tmp/loopkeyd.sh
adb shell 'su -c "cp /data/local/tmp/loopkeyd.sh /data/adb/loop-speaker-mode/scripts/loopkeyd.sh && chmod 755 /data/adb/loop-speaker-mode/scripts/loopkeyd.sh"'
# simulate the tile by creating the trigger as the app, then confirm root can see it:
adb shell 'su -c "run-as co.loop.speaker sh -c \"echo 1 > files/req_dumb\" 2>/dev/null || touch /data/data/co.loop.speaker/files/req_dumb"'
adb shell 'su -c "cat /data/data/co.loop.speaker/files/req_dumb && echo ROOT-CAN-READ"'
```

Expected: `ROOT-CAN-READ`. If SELinux denies root the read (check `dmesg | grep avc`), fall back to a world-readable channel: have `customize.sh` create `/data/adb/loop-speaker-mode/ipc` mode 0777 and have the tile target `/sdcard/Android/data/co.loop.speaker/files/req_dumb` instead (app-writable, root-readable). Re-verify before proceeding.

- [ ] **Step 5: End-to-end tile test**

In full mode, pull down Quick Settings → add the "Speaker Mode" tile (edit/pencil) → tap it. Within ~2s the device should switch to dumb (screen off, apps re-disabled).

```bash
adb shell 'su -c "cat /data/adb/loop-speaker-mode/state"'   # expect: dumb
```

- [ ] **Step 6: Commit**

```bash
git add helper-app/app/src/main/kotlin/co/loop/speaker/SpeakerTile.kt \
        helper-app/app/src/main/AndroidManifest.xml \
        magisk-module/scripts/loopkeyd.sh
git commit -m "feat(full): QS 'Speaker Mode' tile -> root supervisor returns to dumb"
```

---

## Task 6: Install Lawnchair as the full-mode launcher

**Files:** none committed (APK is not stored in the repo; document the steps in `docs/`).

**Why:** The rainx launcher is permanently disabled (privacy); the fallback AOSP `launcher3` looks broken. Lawnchair is open-source, Pixel-like, no Google account required. The default home applies in both modes, but dumb mode keeps the screen off so it only shows in full mode.

- [ ] **Step 1: Download a release APK (host)**

```bash
# Lawnchair 14 (Android 15 compatible). Verify the latest release URL at
# https://github.com/LawnchairLauncher/lawnchair/releases ; example:
cd /tmp
curl -L -o lawnchair.apk \
  "https://github.com/LawnchairLauncher/lawnchair/releases/download/v14.0.0/Lawnchair.14.Stable.apk"
```

(If that exact asset name has changed, pick the current `*.apk` from the latest release page.)

- [ ] **Step 2: Install + set as default home**

```bash
adb install -r /tmp/lawnchair.apk
# discover the launcher's HOME component:
adb shell 'cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME' | grep -iE "lawnchair" 
# set it default (component from the line above; for Lawnchair 14 it is typically below):
adb shell 'su -c "cmd package set-home-activity app.lawnchair/app.lawnchair.LawnchairLauncher"'
adb shell 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME | grep packageName'
```

Expected: resolved HOME `packageName=app.lawnchair`. Press Home physically → Lawnchair appears.

- [ ] **Step 3: Document (committed)**

Add a short section to `docs/03-*.md` or `docs/compatibility.md` recording the Lawnchair install + `set-home-activity` command and the exact component used, so the setup is reproducible. Commit only the docs change.

```bash
git add docs/
git commit -m "docs: record Lawnchair install + set-default-home for full mode"
```

---

## Task 7: Full rebuild, repackage, deploy, and on-device verification

**Files:** none (build + flash + verify).

- [ ] **Step 1: Rebuild native + app into the module tree**

```bash
ROOT=/Users/haohowareyou/Documents/Claude/personal/loopdl/loopdl-speaker
cd "$ROOT/native" && make            # arm64 loopkeyd -> copied into module by Makefile/build
bash "$ROOT/tools/build-app.sh"      # signs APK into magisk-module/system/priv-app/LoopSpeaker/
bash "$ROOT/tools/build-module.sh"   # build/loop-speaker-mode.zip
```

Expected: clean build, `build/loop-speaker-mode.zip` produced.

- [ ] **Step 2: Install the module + update the LIVE config**

```bash
adb push "$ROOT/build/loop-speaker-mode.zip" /data/local/tmp/loop-speaker-mode.zip
adb shell 'su -c "magisk --install-module /data/local/tmp/loop-speaker-mode.zip"'
# config.default does NOT overwrite an existing live config — apply the new thresholds:
adb shell 'su -c "sed -i \"s/^GESTURE_PAIR_HOLD_MS=.*/GESTURE_PAIR_HOLD_MS=1500/\" /data/adb/loop-speaker-mode/config"'
adb shell 'su -c "grep GESTURE_ /data/adb/loop-speaker-mode/config"'
adb reboot
```

Expected after reboot: boots to dumb, screen off.

- [ ] **Step 3: adb-verifiable checks (no buttons)**

```bash
adb wait-for-device; sleep 25
adb shell 'su -c "echo state=$(cat /data/adb/loop-speaker-mode/state); dumpsys power | grep -i mWakefulness=; pgrep -l loopkeyd; head -6 /data/adb/loop-speaker-mode/loopkeyd.log"'
```

Expected: `state=dumb`, `mWakefulness=Asleep`, daemon running, log shows `pair=1500ms mode=5000ms` and both devices grabbed.

- [ ] **Step 4: Physical button verification (user-driven; watch the log live)**

Run `adb shell 'su -c "tail -f /data/adb/loop-speaker-mode/loopkeyd.log"'` and confirm each:
  - Vol+ / Vol- tap → volume changes (~300ms after release); log shows reinject.
  - Vol+ double / Vol- double → `ACTION next` / `ACTION prev`.
  - Power tap → `ACTION play_pause`.
  - Power long (no volume) → firmware power menu appears.
  - **Both volumes ≥1.5s → `ACTION pair_open`** (device discoverable; "Pairing" cue).
  - **Power + Vol-Down ≥5s → `ACTION mode_toggle`** → switches to full (apps return, screen on, Lawnchair home). Confirm NO reboot.

- [ ] **Step 5: Full→dumb via tile + boot safety**

  - In full mode, tap the "Speaker Mode" QS tile → returns to dumb within ~2s.
  - Power off and on → boots straight to dumb (screen off). Safety net confirmed.

- [ ] **Step 6: Update memory + compatibility doc**

Record the new gesture map, the QS-tile return path, and on-device verification results in `docs/compatibility.md` and the project memory file.

```bash
git add docs/
git commit -m "docs: verified gesture rework, screen-off, QS tile, Lawnchair on-device"
```

---

## Self-Review

**Spec coverage:**
- "5s each way / Power+Vol-Down" → Task 2 (dumb→full button) + Task 5 (full→dumb tile). The return is a tile, not a button, per the agreed resolution (daemon can't grab in full mode without breaking phone buttons). ✓
- "Daemon stops in full mode" → Task 1. ✓
- "New launcher" → Task 6 (Lawnchair). ✓
- "Power the screen off in dumb, not black" → Task 4 (`input keyevent 223`, no black activity). ✓
- "Keep full app-disable (D=1)" → no change to dumb/full package lists; toggle stays as-is. ✓
- Pairing reliability ("instant vs delayed") → Task 2 delayed-volume decode + Task 3 1.5s hold. ✓
- Boot safety net → verified in Task 7 Step 5. ✓

**Placeholder scan:** Lawnchair release URL/component may drift between versions — Task 6 Steps 1–2 include the discovery commands to pin the actual values rather than assuming. No other placeholders.

**Type/identifier consistency:** trigger file `req_dumb` in `filesDir` matches between `SpeakerTile.kt` (Task 5 Step 1) and `loopkeyd.sh` poll (Task 5 Step 3). Daemon flags (`vol_pending`, `pwr_long`, `pwr_consumed`, `pwr_passthrough`) are declared in Task 2 Step 2 and used consistently in Steps 3–6. `t_dbl` is reused as the volume-decode timer (no new timer fd added).

**Risk notes:**
- Task 5 depends on Magisk root reading app-private `filesDir` (SELinux) — Step 4 verifies this with a documented fallback.
- Task 2 combos can only be validated with physical buttons (sendevent can't reproduce timed multi-key) — Task 7 Step 4 is the gate.
