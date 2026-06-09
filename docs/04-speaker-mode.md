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
| Screen | forced dark (15 s timeout) | normal |
| Bluetooth | ON — A2DP sink, auto-pair | ON — not auto-discoverable |
| 29 toggled apps | disabled | enabled |
| 3 rainx apps | **always disabled** | **always disabled** |
| Button daemon | active (speaker gestures) | released (normal Android) |
| Auto-sleep | 5 min → suspend, 15 min → power off | standard Android timeout |

The 3 rainx packages are permanently disabled in both modes. This is intentional —
rainx code never runs on this device regardless of mode.

Switching modes takes a few seconds while the 29 packages are toggled. A TTS cue
("Speaker mode" / "Full mode") plays to bridge the gap since the screen is off.

---

## Button reference (Dumb-Speaker mode)

| Gesture | Action |
|---------|--------|
| Vol+ tap | Volume up (instant) |
| Vol− tap | Volume down (instant) |
| Vol+ double-tap | Next track (AVRCP passthrough to source phone) |
| Vol− double-tap | Previous track |
| Power tap (< 600 ms) | Play / pause (AVRCP) |
| Power hold | Normal power off/on (passed through to firmware) |
| Both volumes held 3 s | Open pairing window (60 s) |
| All 3 buttons held 5 s | Toggle Dumb ↔ Full |

Volume is applied immediately on the first tap; the daemon opens a short window
(`DOUBLE_TAP_WINDOW_MS`, default 300 ms) to detect a second tap for track skip.
There is no latency added to normal volume changes.

In Full-Phone mode, the button daemon releases its input grabs and all buttons behave
as standard Android.

**Fallback:** If the 3-button-5s combo is intercepted by the kernel before the daemon
sees it (hardware-dependent), the mode toggle falls back to `both volumes held 5 s`
(distinct from the 3 s pairing hold). The correct hold durations for your unit are
confirmed during first boot via `getevent`.

---

## Pairing flow

1. Boot → Dumb mode → **silent auto-reconnect** to the last paired phone.
2. If reconnect fails → pairing window opens automatically for `PAIR_INITIAL` (180 s).
   TTS says "Pairing".
3. Phone connects → TTS says "Connected", window closes immediately (no longer
   discoverable).
4. **Both volumes held 3 s** at any time → reopen the window for `PAIR_RETRIGGER`
   (60 s). Pressing again resets the timer.
5. Window always self-closes on timeout — the device is never stuck discoverable.

Pairing uses Bluetooth Just-Works (no PIN). The helper app's `ACTION_PAIRING_REQUEST`
receiver auto-accepts only while a window is open.

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
| `GESTURE_PAIR_HOLD_MS` | `3000` | Hold duration to open pairing window (ms) |
| `GESTURE_MODE_HOLD_MS` | `5000` | Hold duration to toggle modes (ms) |
| `DOUBLE_TAP_WINDOW_MS` | `300` | Window to detect a double-tap for track skip (ms) |
| `CUE_VOLUME_PCT` | `50` | TTS and audio cue volume as % of max (tune to taste) |

After editing config, apply without rebooting:

```bash
adb shell su -c 'sh /data/adb/loop-speaker-mode/scripts/loop-mode dumb'
# or: loop-mode full
```

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
