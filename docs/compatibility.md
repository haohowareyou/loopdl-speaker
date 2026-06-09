# Compatibility

## Validated hardware

This project was developed and validated on an early unit of the **rainx LoopDL**:

- SoC: MediaTek MT6877V (Dimensity 900)
- Android: 15
- A/B partition layout with GKI (`init_boot` ramdisk, not `boot`)
- Bluetooth device name during development: "Loop A" (project config default)

## BROM authentication — check yours before trusting the unlock

The unlock in [docs/01-unlock.md](01-unlock.md) relies on the MediaTek BROM being
**fully unauthenticated** (SLA=False, SBC=False, DAA=False). This was confirmed on
the validated unit but is believed to be **specific to early firmware builds**.
Later firmware or different production batches may have authentication enabled, in
which case the mtkclient unlock path does not work.

**You must verify your own unit before attempting the destructive unlock steps.**

Run this (non-destructive — read-only GPT check):

```bash
cd ~/path/to/mtkclient
./venv/bin/python mtk.py printgpt
# Device: power OFF, plug USB — NO buttons held (PRELOADER mode)
```

Look for these lines in the output:

```
SLA: False
SBC: False
```

If either shows `True`, your BROM has authentication enabled. The unlock procedure
documented here will not succeed on your unit. Do not proceed with `da seccfg unlock`.

## Normal-bootloader unlock

On some LoopDL units and firmware versions, `fastboot oem unlock` (normal Android OEM
unlock) may work. The validated unit does not support it — the toggle is absent in
Developer Options and the command returns "unknown command" — but earlier firmware
reportedly allowed it. Try `fastboot oem unlock` first; if it returns an error, the
BROM path is the only option.

## Speaker module compatibility

The `loop-speaker-mode` Magisk module is generic Android (shell scripts + a priv-app
+ a native daemon). It should work on any rooted Android 13+ device with:

- Root via Magisk
- A2DP sink supportable via `resetprop` (standard on most MediaTek AOSP builds)
- Three hardware buttons accessible as `/dev/input/eventN` with standard Linux key codes

The module's `customize.sh` auto-detects the input device names from `/sys/class/input/`
at install time. If auto-detection fails, set `INPUT_KEYPAD` and `INPUT_POWER`
manually in `/data/adb/loop-speaker-mode/config`.

## Input event codes & device topology

The button daemon (`loopkeyd`) uses these Linux input event codes:

| Button | Event code |
|--------|-----------|
| Vol+ | `KEY_VOLUMEUP` = 115 |
| Vol− | `KEY_VOLUMEDOWN` = 114 |
| Power | `KEY_POWER` = 116 |

**Confirmed device topology on the validated unit** (`getevent -lp`) — note the volume
keys are **split across two devices**, which the daemon handles by grabbing both and
dispatching by keycode:

| `/dev/input/` | Name | Physical keys it carries |
|---|---|---|
| `event0` | `mtk-pmic-keys` | **VOLUME_UP + POWER** |
| `event1` | `mtk-kpd` | **VOLUME_DOWN** |
| `event2` | `mtk-tpd` | touchscreen (`BTN_TOUCH`) — **never grabbed** |
| `event3` | `mt6877-mt6359 Headset Jack` | headset remote — **never grabbed** |

So `INPUT_KEYPAD=mtk-kpd` and `INPUT_POWER=mtk-pmic-keys`, but the daemon treats keys by
code from the *union* of both devices (Vol+ actually arrives on the power device). The
daemon explicitly refuses to grab the touchscreen or headset jack.

Verify your own unit:
```bash
adb shell su -c 'getevent -lp'
# Find which event device(s) carry KEY_VOLUMEUP/DOWN/POWER and their names;
# set INPUT_KEYPAD / INPUT_POWER in config to match (the daemon auto-detects by name + capability).
```

## A2DP sink prop

The prop name `bluetooth.profile.a2dp.sink.enabled` (configured via `A2DP_SINK_PROP`
in `/data/adb/loop-speaker-mode/config`) is standard on recent AOSP and MediaTek
builds. If your device uses a different prop name, override the config key.

## Security & safety notes

A security review (2026-06-09) of the app, daemon, and scripts found no critical
issues. Calibrated to the threat model (personal rooted device; nearby attacker + a
rogue sideloaded app are the only real surfaces):

- **Auto-accept pairing is safe by design** — it only accepts while a pairing window is
  open (time-bounded), the window always self-closes (timeout / connect / mode-switch /
  service destroy), and `ACTION_PAIRING_REQUEST` is a protected broadcast a normal app
  cannot spoof. The device is **not** discoverable or auto-accepting outside a window.
  The receiver registers at `SYSTEM_HIGH_PRIORITY` and calls `abortBroadcast()` to
  suppress the system pairing dialog (zero-tap) — this only fires inside an open window,
  so it does not weaken the time-bounded guarantee.
- **Control broadcasts are permission-gated** — `co.loop.speaker.CMD` is protected by a
  `signature`-level permission, so a sideloaded app cannot open a pairing window or
  toggle modes. The module's own callers run as root (uid 0, exempt) so internal
  signaling still works.
- **No command-injection surface** — the daemon only ever passes a fixed set of action
  strings to `loop-act`; no Bluetooth name or config value flows into a shell command.
- **Minimal privileges** — the priv-app holds only `BLUETOOTH_PRIVILEGED` +
  `MODIFY_AUDIO_SETTINGS`.

### ⚠️ If the buttons ever go dead — hold power 10 seconds

The daemon `EVIOCGRAB`s the input devices. If it ever *hangs* (rather than crashes —
a crash auto-releases the grab and the supervisor restarts it), normal "hold power to
power off" can stop responding because the grabbed power key isn't being processed.
**The MT6877 PMIC hardware reset — hold power ~10–15 seconds — bypasses the input layer
entirely and always works**, so you can never be locked out. If this happens, after the
reset the device boots back to Dumb mode; consider reporting it so a daemon watchdog can
be added.

---

## Verified on-device

### UX refinements — adb-verifiable checks (2026-06-09)

All of the following were verified on the validated unit after flashing the
mode-UX-refinements build:

- **Cold boot → Dumb**, daemon up with `dbl=300ms pair=1500ms mode=5000ms`, both button
  devices grabbed (`mtk-kpd` event1, `mtk-pmic-keys` event0), helper app running with
  `BLUETOOTH_CONNECT/SCAN` granted, root IPC poller (`loop-ipc.sh`) running.
- **Dumb is screen-dark even on a charger** — `loop-dumb.sh` clears
  `stay_on_while_plugged_in` and issues `KEYCODE_SLEEP`; `mWakefulness=Asleep` confirmed.
- **Full mode releases the buttons** — after `loop-mode full`, `state=full` and the
  grabbing daemon is gone and *stays* gone (the F2 relaunch race is fixed: state flips to
  full before the daemon is killed, so the supervisor never relaunches it).
- **Full → Dumb via the QS-tile IPC path** — a `req_dumb` trigger written with the app's
  real uid + `privapp_data_file` SELinux label (exactly what `SpeakerTile.onClick`
  produces) is read and consumed by the root poller, which runs `loop-mode dumb`. Proves
  Magisk root can read the app's private trigger file — **no sdcard fallback needed**.
- **Idle screen-off via root IPC** — an app-labeled `req_sleep` trigger is consumed by
  the poller and blanks the panel (`KEYCODE_SLEEP`). `req_poweroff` uses the identical
  mechanism (not exercised destructively).

### Still needs physical-button / phone validation

`sendevent` cannot reproduce timed multi-key gestures, and a QS tile tap is a UI action —
these require hands on the device:

- Vol± single tap → volume (after ~300 ms decode); Vol± double-tap → next/prev.
- Power tap → play/pause; Power long-hold (no volume) → firmware power menu.
- **Both volumes held ≥1.5 s → pairing window** (zero-tap auto-accept; "Pairing" cue).
- **Power + Vol-Down held ≥5 s → Dumb→Full** (apps return, Lawnchair home; confirm NO reboot).
- **QS "Speaker Mode" tile → Full→Dumb** within ~2 s.
- A2DP audio through the speaker, AVRCP transport control, volume sync, TTS cues @50%.
- Two-stage auto-sleep timing (5 min screen-off, 15 min power-off).
