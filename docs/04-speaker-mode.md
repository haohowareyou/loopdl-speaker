# Step 4: Install the speaker module

The `loop-speaker-mode` Magisk module is the core of this project. It provides:

- Boot-time enforcement of Dumb-Speaker mode
- Hardware button gesture handling via `loopkeyd`
- The helper app (`co.loop.speaker`) for Bluetooth pairing, AVRCP transport, volume,
  TTS cues, auto-reconnect, and auto-sleep
- A single config file you can edit to tune the device

---

## Install

1. Build the flashable zip (or use a pre-built release):
   ```bash
   bash tools/build-module.sh
   # Produces: build/loop-speaker-mode.zip
   ```

2. Push to the device and flash via Magisk:
   ```bash
   adb push build/loop-speaker-mode.zip /sdcard/Download/
   ```
   In the Magisk app: **Modules → Install from storage** → pick `loop-speaker-mode.zip`.

3. Reboot. The module's `service.sh` applies Dumb mode on every boot.

After reboot the device is in **Dumb-Speaker mode** — radios off, screen dark,
Bluetooth open for pairing.

---

## Modes

| | Dumb-Speaker (default) | Full-Phone |
|---|---|---|
| Cellular data | OFF | ON |
| WiFi | OFF | ON |
| NFC / location | OFF | restored |
| Screen | powered off on entry (10 s backstop timeout) | normal |
| Bluetooth | ON — A2DP sink, auto-pair | ON — not auto-discoverable |
| 29 toggled apps | disabled | enabled |
| 3 rainx apps | **always disabled** | **always disabled** |
| Launcher (home) | n/a (screen off) | Lawnchair |
| Button daemon | active (speaker gestures) | released (normal Android) |
| Auto-sleep | 5 min → screen off, 15 min → power off | standard Android timeout |

Auto-sleep is driven by the helper app (it knows when music is playing) but executed by
root: the app is unprivileged and cannot blank/shut down the device itself, so it drops a
trigger file in its `filesDir` that the root IPC poller (`loop-ipc.sh`) acts on.

The 3 rainx packages are permanently disabled in both modes. This is intentional —
rainx code never runs on this device regardless of mode.

Switching modes takes a few seconds while the 29 packages are toggled. A TTS cue
("Speaker mode" / "Full mode") plays to bridge the gap since the screen is off.

---

## Button reference (Dumb-Speaker mode)

| Gesture | Action |
|---------|--------|
| Vol+ tap | Volume up |
| Vol− tap | Volume down |
| Vol+ double-tap | Next track (AVRCP passthrough to source phone) |
| Vol− double-tap | Previous track |
| Power tap (< 600 ms) | Play / pause (AVRCP) |
| Power hold (no volume) | Normal power menu (grab released to firmware) |
| Both volumes held 1.5 s | Open pairing window (60 s) |
| **Power + Vol− held 5 s** | Switch Dumb → Full |

**Delayed-volume decode:** a single volume press is deferred by `DOUBLE_TAP_WINDOW_MS`
(default 300 ms) before it acts, so the daemon can first tell apart a second key — the
other volume (a combo) or the same volume again (a double-tap for track skip). This adds
~300 ms of latency to a lone volume nudge but makes the both-volumes pairing combo
reliable (it no longer mis-fires as next/prev). Combos are recognised instantly.

**Why Power + Vol−, not all three:** a long **Power + Vol-Up** hold triggers the MT6877
PMIC hardware force-reboot *below the OS* — the daemon can't intercept it. The mode
gesture deliberately uses **Power + Vol-Down** to avoid that path.

In Full-Phone mode, the button daemon releases its input grabs and all buttons behave
as standard Android — so there is **no button to return to Dumb**. Use the Quick-Settings
tile instead (below).

### Returning to Dumb (Full → Dumb)

Full-Phone mode has no grabbing daemon, so the return path is a **Quick-Settings tile**:

1. Pull down Quick Settings → edit (pencil) → add the **"Speaker Mode"** tile.
2. Tap it → the device returns to Dumb within ~2 s (screen off, apps re-disabled).

Mechanism: the tile is part of the helper app, which is unprivileged. It drops a
`req_dumb` trigger file in its `filesDir`; the root IPC poller (`loop-ipc.sh`) sees it
and runs `loop-mode dumb`. A full power-off/on also always boots back to Dumb (safety net).

---

## Pairing flow

1. Boot → Dumb mode → **silent auto-reconnect** to the last paired phone.
2. If reconnect fails → pairing window opens automatically for `PAIR_INITIAL` (180 s).
   TTS says "Pairing".
3. Phone connects → TTS says "Connected", window closes immediately (no longer
   discoverable).
4. **Both volumes held 1.5 s** at any time → reopen the window for `PAIR_RETRIGGER`
   (60 s). Pressing again resets the timer.
5. Window always self-closes on timeout — the device is never stuck discoverable.

Pairing uses Bluetooth Just-Works (no PIN) and is **zero-tap**: the helper app's
`ACTION_PAIRING_REQUEST` receiver registers at `SYSTEM_HIGH_PRIORITY`, auto-accepts via
`setPairingConfirmation(true)`, and calls `abortBroadcast()` to suppress the system
pairing dialog — so nothing needs to be tapped on the (dark) screen. It only does this
while a pairing window is open.

---

## Config reference

The config file is at `/data/adb/loop-speaker-mode/config`. It is created from
`config.default` on first install and never overwritten by module updates — your edits
persist.

Edit it as root:

```bash
adb shell su -c 'vi /data/adb/loop-speaker-mode/config'
```

| Key | Default | Description |
|-----|---------|-------------|
| `DEVICE_NAME` | `"Loop A"` | Bluetooth name advertised in Dumb mode |
| `KEEP_DATA` | `0` | Set to `1` to leave cellular data ON in Dumb mode |
| `KEEP_WIFI` | `0` | Set to `1` to leave WiFi ON in Dumb mode |
| `PAIR_INITIAL` | `180` | Pairing window at boot (seconds) |
| `PAIR_RETRIGGER` | `60` | Pairing window on button hold (seconds) |
| `IDLE_SLEEP_MIN` | `5` | Minutes of silence before auto-suspend |
| `IDLE_OFF_MIN` | `15` | Minutes of silence before power off |
| `INPUT_KEYPAD` | `"mtk-kpd"` | Input device name for volume buttons (auto-detected at install) |
| `INPUT_POWER` | `"mtk-pmic-keys"` | Input device name for power button (auto-detected at install) |
| `A2DP_SINK_PROP` | `"bluetooth.profile.a2dp.sink.enabled"` | Prop name for A2DP sink (do not change) |
| `GESTURE_PAIR_HOLD_MS` | `1500` | Hold both volumes this long to open pairing window (ms) |
| `GESTURE_MODE_HOLD_MS` | `5000` | Hold Power+Vol− this long to switch Dumb→Full (ms) |
| `DOUBLE_TAP_WINDOW_MS` | `300` | Double-tap window for track skip — also the delayed-volume decode delay (ms) |
| `CUE_VOLUME_PCT` | `50` | TTS and audio cue volume as % of max (tune to taste) |

After editing config, apply without rebooting:

```bash
adb shell su -c 'sh /data/adb/loop-speaker-mode/scripts/loop-mode dumb'
# or: loop-mode full
```

---

## Full-mode launcher (Lawnchair)

The rainx launcher is permanently disabled (privacy), and the AOSP fallback
(`com.android.launcher3`) looks broken (empty hotseat, no app feed). Full-Phone mode
uses **Lawnchair** instead — open-source, Pixel-like, no Google account required. It is
installed as a normal user app (not part of the module) and set as the default home; the
preference persists across reboots. In Dumb mode the screen stays off, so the launcher
only ever shows in Full mode.

Reproducible setup (host with adb + root):

```bash
# Lawnchair 15 beta (Android 15 target). Latest asset at:
#   https://github.com/LawnchairLauncher/lawnchair/releases
adb install -r Lawnchair.15.0.0.Beta.3.0.apk
adb shell su -c 'cmd package set-home-activity app.lawnchair/app.lawnchair.LawnchairLauncher'

# verify:
adb shell 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME' | grep packageName
# expected: packageName=app.lawnchair
```

The APK is **not** committed to the repo. On first Home press in Full mode, Lawnchair
runs a one-time setup wizard.

---

## Check current mode

```bash
adb shell su -c 'cat /data/adb/loop-speaker-mode/state'
# → dumb  or  full
```

---

## Remove / disable the module

The module writes **no partitions**. It is fully removable at any time.

**Option 1 — Magisk Safe Mode** (no adb needed): hold **Vol-Down** while the boot
animation is running. Magisk loads without any modules active; the device boots to
unmodified Android. On next normal reboot the module is active again (this only
bypasses, not removes).

**Option 2 — Disable permanently** via Magisk app: Modules → loop-speaker-mode →
toggle off → reboot.

**Option 3 — Remove from adb**:
```bash
adb shell su -c 'magisk --remove-modules'
# or remove just this module via the Magisk app
```

On removal, `uninstall.sh` re-enables all 29 toggled packages (the 3 rainx packages
are left as-is — they were disabled before the module was installed).
