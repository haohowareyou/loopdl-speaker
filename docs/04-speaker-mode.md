# Step 4: Install the speaker module

The `loop-speaker-mode` Magisk module is the core of this project. It provides:

- Boot-time enforcement of Dumb-Speaker mode
- Hardware button gesture handling via `loopkeyd`
- The helper app (`io.github.haohowareyou.loopdl`) for Bluetooth pairing, AVRCP transport, volume,
  audio earcons, auto-reconnect, and auto-sleep
- A single config file you can edit to tune the device

---

## Install

1. Build the helper app first, then the flashable zip. The module bundles the app as a
   priv-app, so the app must exist before the zip is built or the module ships without it:
   ```bash
   bash tools/build-app.sh      # builds the helper APK (needs JDK 17 + Android SDK + NDK)
   bash tools/build-module.sh   # bundles it into build/loop-speaker-mode.zip
   ```
   `build-module.sh` refuses to run if the helper APK is not present yet, so you cannot
   accidentally flash a module with no app in it.

2. Push to the device and flash via Magisk:
   ```bash
   adb push build/loop-speaker-mode.zip /sdcard/Download/
   ```
   In the Magisk app: **Modules → Install from storage** → pick `loop-speaker-mode.zip`.

3. Reboot. The module's `service.sh` applies Dumb mode on every boot.

After reboot the device is in **Dumb-Speaker mode**: radios off, screen dark,
Bluetooth open for pairing.

---

## Modes

| | Dumb-Speaker (default) | Full-Phone |
|---|---|---|
| Cellular data | OFF | ON |
| WiFi | OFF | ON |
| NFC / location | OFF | restored |
| Screen | powered off on entry (10 s backstop timeout) | normal |
| Bluetooth | ON (A2DP sink, auto-pair, **BT PAN forbidden**) | ON (not auto-discoverable) |
| 29 toggled apps | disabled | enabled |
| 3 rainx apps | **always disabled** | **always disabled** |
| Launcher (home) | n/a (screen off) | Lawnchair (dock: Firefox/Camera/Settings/Play Store) |
| Button daemon | active (speaker gestures) | released (normal Android) |
| Quick Settings | n/a | Mobile Data + Hotspot tiles |
| Do-Not-Disturb | alarms-only (phone notifications silent) | lifted |
| Auto-sleep | 5 min → screen off, 15 min → power off | standard Android timeout |

Auto-sleep is driven by the helper app (it knows when music is playing) but executed by
root: the app is unprivileged and cannot blank/shut down the device itself, so it drops a
trigger file in its `filesDir` that the root IPC poller (`loop-ipc.sh`) acts on.

**Bluetooth PAN is forbidden per connected device** in Dumb mode; the speaker never
uses the phone's data over Bluetooth. Audio only.

The 3 rainx packages are permanently disabled in both modes. This is intentional:
rainx code never runs on this device regardless of mode.

Switching modes takes a few seconds while the 29 packages are toggled. A musical earcon
(low two-note pair for Dumb / high two-note pair for Full) plays to bridge the gap since
the screen is off.

---

## Button reference (Dumb-Speaker mode)

| Gesture | Action |
|---------|--------|
| Vol+ tap | Volume up (applied instantly on release) |
| Vol− tap | Volume down (instant) |
| Vol+ / Vol− hold | Ramp volume (starts after `VOL_RAMP_DELAY_MS`, steps every `VOL_RAMP_INTERVAL_MS`); a "limit" tone plays at max/min |
| Power single tap | Play / pause (AVRCP); soft "wake" earcon. Fires after `POWER_MULTITAP_MS` (see below) |
| Power double tap | Next track (AVRCP passthrough to source phone) |
| Power triple tap | Previous track (fires instantly on the 3rd tap — no extra wait) |
| **Power hold alone ~2.5 s** | **Shutdown** (power-off earcon, then powers off; no firmware power menu in Dumb mode) |
| Both volumes held 3 s | Open pairing window (60 s) |
| **Power + Vol− held 3 s** | Switch Dumb → Full |

**Power multi-tap decode:** track control lives on the power button — 1 tap = play/pause,
2 taps = next, 3 taps = previous. Because the daemon can't know another tap isn't coming, a
single tap is deferred by `POWER_MULTITAP_MS` (default 280 ms) to count the sequence, so
play/pause carries ~280 ms of latency. A triple-tap short-circuits: "previous" fires
immediately on the 3rd release. Volume taps are **not** deferred — there is no volume
double-tap gesture any more, so a lone volume tap acts instantly. The both-volumes pairing
combo and the Power+Vol− mode combo are recognised by hold timers, independent of order.

**Why Power + Vol−, not all three:** a long **Power + Vol-Up** hold triggers the MT6877
PMIC hardware force-reboot *below the OS*; the daemon can't intercept it. The mode
gesture deliberately uses **Power + Vol-Down** to avoid that path.

In Full-Phone mode, the button daemon releases its input grabs and all buttons behave
as standard Android, so there is **no button to return to Dumb**. Use the Quick-Settings
tile instead (below).

### Returning to Dumb (Full → Dumb)

Full-Phone mode has no grabbing daemon, so the return path is a **Quick-Settings tile**:

1. Pull down Quick Settings → edit (pencil) → add the **"Speaker Mode"** tile.
2. Tap it → the device returns to Dumb within ~2 s (screen off, apps re-disabled).

Mechanism: the tile is part of the helper app, which is unprivileged. It drops a
`req_dumb` trigger file in its `filesDir`; the root IPC poller (`loop-ipc.sh`) sees it
and runs `loop-mode dumb`. A full power-off/on also always boots back to Dumb (safety net).

If the **"Speaker Mode"** tile is not available to add, the helper app did not install as a
priv-app (usually the module was built without the APK; see Install step 1). Until you
rebuild and reflash, a full power-off then power-on always boots straight back to Dumb, so
you are never stuck in Full.

---

## Troubleshooting buttons

If gestures misbehave, **read the daemon log first** — it logs every key edge and every
decoded action, and almost always tells you the answer directly:

```bash
adb shell su -c 'cat /data/adb/loop-speaker-mode/loopkeyd.log'
```

The log is truncated on each (re)launch. A healthy startup looks like:

```
loopkeyd: start (dry_run=0) pmtap=280ms pair=3000ms mode=3000ms poweroff=2500ms
loopkeyd: matched "mtk-kpd" -> /dev/input/event1 ("mtk-kpd")
loopkeyd: matched "mtk-pmic-keys" -> /dev/input/event0 ("mtk-pmic-keys")
loopkeyd: grabbed 2 button device(s), power on "mtk-pmic-keys"
loopkeyd: silence -> /dev/input/event2 ("mtk-tpd")
loopkeyd: silenced 1 touchscreen device(s)
```

Then each gesture you perform logs its raw edges and decode, e.g.
`key POWER down` / `key POWER up` / `power 2-tap: next` / `ACTION next`,
or `key VOLDN down` / `vol tap VOLDN`.

### Reading the startup line

- **`matched "<name>"`** lines mean the daemon found its devices **by name** (pass 1).
  This is the correct, expected path.
- **`cap-match`** lines instead mean the by-name match found nothing and the daemon fell
  back to a **capability scan** (pass 2). That is a red flag: it means `INPUT_KEYPAD` /
  `INPUT_POWER` in the config don't match any device *name*. Check them:
  ```bash
  adb shell su -c 'grep INPUT_ /data/adb/loop-speaker-mode/config'
  ```
  They must hold device **names** (e.g. `mtk-kpd`, `mtk-pmic-keys`), **not** paths like
  `/sys/class/input/event1`. If they hold paths, an old install wrote them wrong; fix with:
  ```bash
  adb shell su -c 'sed -i "s|^INPUT_KEYPAD=.*|INPUT_KEYPAD=\"mtk-kpd\"|;s|^INPUT_POWER=.*|INPUT_POWER=\"mtk-pmic-keys\"|" /data/adb/loop-speaker-mode/config'
  ```
  (or set them empty `""` to re-trigger auto-detect on the next install).

### Confirm the hardware with a raw capture

To prove the buttons themselves emit events (vs. a daemon decode problem), watch the
kernel events directly. The daemon **grabs** the devices exclusively, so first stop it or
run it in `--dry-run` (which does not grab), then:

```bash
adb shell su -c 'getevent -lt'   # all input devices; press each button and watch
```

On this LoopDL the topology is split across **two** devices, which is *why* the daemon
keeps a global key-state and why every fd must be non-blocking:

- `mtk-pmic-keys` → **Vol-Up + Power**
- `mtk-kpd` → **Vol-Down only**
- `mtk-tpd` → touchscreen (grabbed-and-dropped so a tap-wake can't light the screen)

So **"Vol-Down dead but Vol-Up works"** is the signature of the daemon servicing only the
first device — historically caused by a blocking fd starving the epoll loop (see below).

### Dry-run mode (detect/log only, no grab, no actions)

```bash
adb shell su -c '/data/adb/modules/loop-speaker-mode/system/bin/loopkeyd --dry-run'
```

It logs gesture detection to stdout without grabbing devices or emitting actions — useful
to confirm decode logic against live presses while the normal daemon is stopped.

### Restarting the daemon after a code/config change

The supervisor (`loopkeyd.sh`) relaunches the daemon within ~2 s of it exiting, but **only
while `state == dumb`**. An interactive `su` shell runs in a confined SELinux domain and
**cannot reliably signal the daemon** (`kill` returns `Operation not permitted`) or write
the IPC trigger files. The reliable ways to pick up a new binary/config are:

- **Reboot** (always works; cold-boot returns to Dumb).
- `pkill -f loop-speaker-mode/system/bin/loopkeyd` as root — the supervisor respawns it.
  (May be denied depending on domain; reboot is the fallback.)

A config edit alone (no binary change) is applied by re-running the mode script:
`sh /data/adb/loop-speaker-mode/scripts/loop-mode dumb`.

### Known pitfalls (post-mortem)

Two coupled bugs once made every gesture except Power and Vol-Up dead. Both are fixed; the
mechanisms are recorded here because they were non-obvious:

1. **Install stored device *path* instead of *name*.** `customize.sh` used `grep -l`
   (returns the matching file path) and wrote that into `INPUT_KEYPAD`/`INPUT_POWER`, but
   the daemon matches by **name** (`strstr`). Pass-1 by-name detection therefore always
   failed and silently fell back to the capability scan. Fix: read the name *out* of the
   matched file and overwrite the whole config line on every (re)install.
2. **The fallback opened fds blocking.** The main loop drains each device with
   `while (read()==sizeof(ev))`. On a **blocking** fd that `read()` waits for the next
   event instead of returning `EAGAIN`, so the loop never returns to `epoll` — it camps on
   the first device that fired and starves the *other* button device **and every gesture
   timerfd**. Every input fd MUST be opened `O_RDONLY | O_NONBLOCK`. Pass 1 did; the pass-2
   fallback originally did not.

The lesson baked into the code: both discovery passes open fds with identical flags, and
the startup log distinguishes `matched` (by-name, good) from `cap-match` (fallback) so the
wrong path is visible at a glance.

---

## Pairing flow

1. Boot → Dumb mode → **silent auto-reconnect** to the last paired phone.
2. If reconnect fails → pairing window opens automatically for `PAIR_INITIAL` (60 s).
   A rising two-note pairing earcon plays.
3. Phone connects → a rising major-triad earcon plays, window closes immediately (no
   longer discoverable).
4. **Both volumes held 3 s** at any time → reopen the window for `PAIR_RETRIGGER`
   (60 s). Pressing again resets the timer.
5. Window always self-closes on timeout; the device is never stuck discoverable.

Pairing uses Bluetooth Just-Works (no PIN) and is **zero-tap**: the helper app's
`ACTION_PAIRING_REQUEST` receiver registers at `SYSTEM_HIGH_PRIORITY`, auto-accepts via
`setPairingConfirmation(true)`, and calls `abortBroadcast()` to suppress the system
pairing dialog, so nothing needs to be tapped on the (dark) screen. Auto-accept stays armed
the whole time the device is in Dumb mode (a real speaker is always willing to pair); the
pairing window only controls whether the device is *discoverable*, not whether it accepts.
A phone that already knows the device's address can therefore reconnect or re-pair at any
time in Dumb mode.

---

## Config reference

The config file is at `/data/adb/loop-speaker-mode/config`. It is created from
`config.default` on first install and never overwritten by module updates; your edits
persist.

Edit it as root. If you are comfortable with `vi`:

```bash
adb shell su -c 'vi /data/adb/loop-speaker-mode/config'
# vi cheat: press i to type, Esc when done, then :wq and Enter to save and quit.
```

Or change a single value without an editor (this example renames the speaker):

```bash
adb shell su -c 'sed -i "s/^DEVICE_NAME=.*/DEVICE_NAME=\"Patio\"/" /data/adb/loop-speaker-mode/config'
```

| Key | Default | Description |
|-----|---------|-------------|
| `DEVICE_NAME` | `"theloop"` | Bluetooth name advertised in Dumb mode |
| `KEEP_DATA` | `0` | Set to `1` to leave cellular data ON in Dumb mode |
| `KEEP_WIFI` | `0` | Set to `1` to leave WiFi ON in Dumb mode |
| `PAIR_INITIAL` | `60` | Pairing window at boot (seconds) |
| `PAIR_RETRIGGER` | `60` | Pairing window on button hold (seconds) |
| `IDLE_SLEEP_MIN` | `5` | Minutes of silence before auto-suspend |
| `IDLE_OFF_MIN` | `15` | Minutes of silence before power off |
| `INPUT_KEYPAD` | `""` | Input device name for volume buttons (auto-detected at install if empty) |
| `INPUT_POWER` | `""` | Input device name for power button (auto-detected at install if empty) |
| `A2DP_SINK_PROP` | `"bluetooth.profile.a2dp.sink.enabled"` | Prop name for A2DP sink (do not change) |
| `GESTURE_PAIR_HOLD_MS` | `3000` | Hold both volumes this long to open pairing window (ms) |
| `GESTURE_MODE_HOLD_MS` | `3000` | Hold Power+Vol− this long to switch Dumb→Full (ms) |
| `POWER_SHUTDOWN_MS` | `2500` | Hold power alone this long to shut down (shorter = a tap) (ms) |
| `POWER_MULTITAP_MS` | `280` | Max gap between power taps when counting the sequence: 1 = play/pause, 2 = next, 3 = previous (ms). Also the play/pause latency, since 1-tap must wait this long to rule out a 2nd tap |
| `VOL_RAMP_DELAY_MS` | `500` | Hold a volume key this long before it starts ramping (ms) |
| `VOL_RAMP_INTERVAL_MS` | `260` | While held past the ramp delay, nudge volume every this many ms |
| `GRAB_TOUCH` | `1` | `1` = silence/grab the touchscreen in Dumb mode so it can't wake the screen |
| `CUE_VOLUME_PCT` | `30` | Volume level (% of max) for the spoken battery-percentage announcement; earcon loudness is fixed in the app (~15%) |

The `INPUT_KEYPAD` and `INPUT_POWER` defaults are shown as `""`; the real values
(`mtk-kpd` / `mtk-pmic-keys` on the validated unit) are written into the config by
`customize.sh` at install time.

After editing config, apply without rebooting:

```bash
adb shell su -c 'sh /data/adb/loop-speaker-mode/scripts/loop-mode dumb'
# or: loop-mode full
```

---

## Audio feedback & alerts

All mode/connection announcements are **synthesized musical earcons**: short tones
that play at a fixed ~15% loudness (hardcoded in the app, not configurable). They use
the ALARM audio stream so they cut through Do-Not-Disturb and mix over the music
without interrupting playback.

**Earcon vocabulary:**

| Event | Earcon |
|-------|--------|
| Power tap (play/pause) | Soft single "wake" note (confirms the device is on) |
| Pairing window open / discoverable | Rising two-note tone |
| Phone connected | Rising major triad |
| Phone disconnected | Falling two-note tone (re-entering discoverable is silent/implied) |
| Switch to Dumb-Speaker mode | Low two-note pair |
| Switch to Full-Phone mode | High two-note pair |
| Volume max or min reached | Distinct "limit" tone instead of a normal step tick |
| Volume step tick | Scales with the media volume bar (the only cue that does) |
| Idle pre-off warning (~1 min before auto-off) | Soft "winding down" tone |
| Low battery (20%) | Gentle chirp |
| Low battery (10%) | More insistent chirp |
| Low battery (~5%) | Power-off tone, then graceful shutdown |
| Power-off (shutdown) | Power-off earcon, then the device turns off |
| Battery percentage (on demand) | Spoken percentage (the only spoken cue) |

The **only spoken cue** is the battery percentage, announced on demand.
`CUE_VOLUME_PCT` (default 30) governs the volume of this announcement only; earcon
loudness is compiled into the app and is not affected by this setting.

**Do-Not-Disturb:** Dumb-Speaker mode activates DND (alarms-only), keeping phone
notifications silent. DND is lifted when switching to Full mode. Earcons still play in
Dumb mode because they use the alarm stream.

**Low-battery warnings:**

- **20%**: a gentle chirp.
- **10%**: a more insistent chirp.
- **~5%**: a power-off earcon followed by a graceful shutdown.

Warnings re-arm once you charge back above the threshold. The current low-battery state
is also re-announced when a phone connects (so you know immediately if the device is low
after a dead period).

**Idle pre-shutdown warning:** a soft "winding down" tone plays approximately 1 minute
before the 15-minute idle auto-off fires. Any button press cancels it and resets the idle
timer.

**Boot and shutdown sounds:** this project ships none. Android plays
`/system/media/bootaudio.mp3` and `/system/media/shutaudio.mp3` if they exist, and your
device's own stock files are used as-is. If you want custom ones, drop your own MP3s at
those paths through the module's `system/media/` overlay. Do not redistribute the vendor's
stock audio; use your own.

---

## Full-Phone mode: launcher and quick toggles

The rainx launcher is permanently disabled (privacy). Full-Phone mode uses **Lawnchair**,
open-source, Pixel-like, no Google account required. It is installed as a normal user
app (not part of the module) and set as the default home; the preference persists across
reboots. In Dumb mode the screen stays off, so the launcher only ever shows in Full mode.

**Dock layout** (applied by `tools/apply-lawnchair-layout.sh`):
Firefox (`org.mozilla.fennec_fdroid`) / Camera / Settings / Play Store.
All other apps are in the drawer.

**Quick Settings tiles** the project adds:
- **Mobile Data**: a true one-tap 5G on/off toggle.
- **Hotspot**: opens the Wi-Fi hotspot screen directly.

The stock Internet and Hotspot QS tiles are also present. The use case is the Loop as
a connected speaker / mobile hotspot, not a daily phone.

Reproducible setup (host with adb + root):

```bash
# Lawnchair 15 beta (Android 15 target). Latest asset at:
#   https://github.com/LawnchairLauncher/lawnchair/releases
adb install -r Lawnchair.15.0.0.Beta.3.0.apk
adb shell su -c 'cmd package set-home-activity app.lawnchair/app.lawnchair.LawnchairLauncher'

# verify:
adb shell 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME' | grep packageName
# expected: packageName=app.lawnchair

# Apply dock layout:
bash tools/apply-lawnchair-layout.sh
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

**Option 1: Magisk Safe Mode** (no adb needed): hold **Vol-Down** while the boot
animation is running. Magisk loads without any modules active; the device boots to
unmodified Android. On next normal reboot the module is active again (this only
bypasses, not removes).

**Option 2: Disable permanently** via Magisk app: Modules → loop-speaker-mode →
toggle off → reboot.

**Option 3: Remove from adb**:
```bash
adb shell su -c 'magisk --remove-modules'
# or remove just this module via the Magisk app
```

On removal, `uninstall.sh` re-enables all 29 toggled packages (the 3 rainx packages
are left as-is; they were disabled before the module was installed).
