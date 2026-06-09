# Design — Loop Speaker Mode

Status: **draft for review** · Date: 2026-06-09 · Target device: rainx "theloop" (MT6877, Android 15), rooted (Magisk)

## 1. Goal

Turn a rooted LoopDL into a dual-personality device with a hardware-button-driven
switch:

- **Dumb-Speaker mode** (default on every boot) — a lean Bluetooth speaker. Radios
  off except Bluetooth, screen dark, auto-pairing, media-key controls, single synced
  volume. Maximum battery life.
- **Full-Phone mode** (occasional) — a normal Android phone with all apps back,
  **except** the rainx/Loop apps, which stay permanently disabled so the device never
  tracks the user or force-enables mic/location. (double check this first, if disbaling rainx/loop app is good enough then great)

Everything must be **reproducible on a stock LoopDL** and shippable as a public,
personal-data-free git repo (see §9).

## 2. Modes

| Aspect | Dumb-Speaker (default) | Full-Phone |
|---|---|---|
| Cellular data | OFF | ON |
| WiFi | OFF | ON |
| NFC / location | OFF | restored |
| Screen | forced off, kept dark | normal |
| Bluetooth | ON + A2DP sink + auto-pair flow | ON, not auto-discoverable |
| `MODE_TOGGLED` apps (29) | disabled | **enabled** |
| `PERMANENT_DISABLE` apps (rainx, 3) | disabled | **disabled (always)** |
| Button daemon | active (speaker gestures) | released (normal phone buttons) |
| Auto-sleep | 5 min → suspend, 15 min → power off | normal Android timeout |

**Package sets** (exact lists pinned in `scripts/packages.*`):
- `PERMANENT_DISABLE` = `co.rainx.loop.launcher`, `co.rainx.loop.setup`,
  `vendor.rainx.setupwizard.overlay`. Never enabled in either mode. This is the
  privacy guarantee: rainx code cannot run, so no always-on mic/location/tracking.
- `MODE_TOGGLED` = the 29 non-rainx apps debloated for speaker use (Chrome, Gmail,
  Maps, Photos, Docs, YouTube/Music/Kids, Videos, Calendar, Files, Meet, Wellbeing,
  Health, SafetyHub, ADM, Restore, carrierwifi, federatedcompute,
  ondevicepersonalization, turbo, Google app + Assistant + searchselector, Dialer,
  Messaging, Contacts, cellbroadcast). `pm disable-user` in Dumb, `pm enable` in Full.

Switching toggles the 29 `MODE_TOGGLED` packages (and re-enforces the 3 rainx
disables) → takes a few seconds; a TTS cue ("Full mode" / "Speaker mode") covers the
gap since the screen is off.

> **Build-time check (user request):** confirm `pm disable-user --user 0` on the rainx
> packages is genuinely sufficient for the privacy guarantee (no background
> service/mic/location survives a disable). If a disabled priv-app can still be woken,
> escalate to removing the APKs from the overlay (root) instead. Verify with
> `dumpsys activity processes | grep rainx` + `appops` after a disable.

## 3. Pairing flow

1. Boot → Dumb mode → **silent auto-reconnect** of last paired phone.
2. If reconnect fails → **open pairing window** (discoverable + auto-accept) for
   `PAIR_INITIAL` (180 s) → TTS "Pairing".
3. Phone connects → TTS "Connected", window closes immediately (no longer
   discoverable = secure).
4. **Both volumes held 3 s** anytime → reopen window for `PAIR_RETRIGGER` (60 s).
   Pressing again resets the timer.
5. Window always self-closes on timeout (never stuck discoverable).

Auto-accept is done by the helper app's `ACTION_PAIRING_REQUEST` receiver calling
`setPairingConfirmation(true)` (Just-Works), gated to fire only while a window is open.

## 4. Button map (Dumb mode)

```
Vol+ / Vol-  tap        → volume up/down (applied instantly; double-tap detected on top)
Vol+  double-tap        → next track       (AVRCP passthrough → source phone)
Vol-  double-tap        → previous track
Power tap               → play / pause      (AVRCP)
Power hold              → normal power off/on (preserved)
Both volumes hold 3 s   → open pairing window
All 3 buttons hold 5 s  → toggle Dumb / Full
```

- Fallback: if Power+Volume combos are intercepted by hardware/kernel before the
  daemon sees them, the mode toggle falls back to **both volumes held 5 s** (distinct
  from the 3 s pairing hold). Confirmed during build via `getevent`.
- Volume stays instant: applied on first tap, with a short window to detect a second
  tap for skip — no added latency to normal volume.

## 5. Volume — single synced slider

Goal: **Bluetooth Absolute Volume (AVRCP 1.4+) ON** so the iPhone and Loop share one
volume that tracks on both sides (not two multiplied mixers).

- Primary: enable via BT-stack config (e.g. clear `persist.bluetooth.disableabsvol`
  / appropriate AVRCP-target setting) and verify with the iPhone as source.
- Fallback: if the Android *sink* role doesn't honor incoming absolute-volume cleanly,
  the helper app **bridges** volume — mirrors iPhone↔Loop changes — to present one
  synced slider regardless.

## 6. Components (all inside one Magisk module `loop-speaker-mode`)

1. **Mode scripts** — `loop-dumb.sh`, `loop-full.sh`: set radios, screen policy,
   toggle `MODE_TOGGLED` packages, enforce `PERMANENT_DISABLE`, set BT name, write a
   state file. Plus a Mac-side `tools/loop-mode speaker|full` adb wrapper.
2. **Button daemon** — respawning root service; `EVIOCGRAB`s the input devices in Dumb
   mode, implements the gesture map (re-injecting normal volume), releases grabs in
   Full mode. Invokes the helper app / scripts for actions.
3. **Helper app** — headless system **priv-app** with `BLUETOOTH_PRIVILEGED` (granted
   via a `privapp-permissions` allowlist placed by the module). Owns: discoverable
   window, auto-accept pairing, auto-allow A2DP, AVRCP passthrough (play/pause/next/
   prev), absolute-volume bridge, audio cues + TTS, battery announcement,
   auto-reconnect. Driven by broadcasts from the daemon.
4. **Boot hooks** — `post-fs-data.d/a2dp-sink.sh` (existing sink props) + a
   `service.sh` that enters Dumb mode on boot.

## 7. Extras (all selected)

- **Voice prompts (TTS)** via Google TTS (kept enabled): "Pairing", "Connected",
  "Speaker mode" / "Full mode", "Battery N percent". All cues play at **half volume**
  (`CUE_VOLUME_PCT=50`) for now — the user will tune after testing. (The bootloader
  startup chime itself is LK-level and out of scope here; tracked separately.)
- **Battery announcement** — on connect and via a gesture → spoken %.
- **Auto-sleep (two-stage)** — `IDLE_SLEEP_MIN` (5) idle with nothing connected and no
  audio → suspend; `IDLE_OFF_MIN` (15) → full power off. Never triggers during
  playback (A2DP wakelock). Any button / reconnect wakes from suspend.
- **Auto-reconnect** last phone on boot/wake before opening a pairing window.

## 8. Config (`/data/adb/loop-speaker-mode/config`)

```
DEVICE_NAME="Loop A"          # this unit's BT name; others set their own
KEEP_DATA=0                   # 1 = leave cellular data on in Dumb mode
KEEP_WIFI=0
PAIR_INITIAL=180              # seconds, boot pairing window
PAIR_RETRIGGER=60             # seconds, button-triggered window
IDLE_SLEEP_MIN=5
IDLE_OFF_MIN=15
INPUT_KEYPAD="mtk-kpd"        # device-specific, auto-detected at install
INPUT_POWER="mtk-pmic-keys"
A2DP_SINK_PROP="bluetooth.profile.a2dp.sink.enabled"
GESTURE_PAIR_HOLD_MS=3000
GESTURE_MODE_HOLD_MS=5000
DOUBLE_TAP_WINDOW_MS=300
CUE_VOLUME_PCT=50             # TTS / audio-cue loudness (user will tune)
```

## 9. Distribution & reproducibility

Repo `loopdl-speaker/` (MIT). Layout: `README.md` (master stock→speaker walkthrough),
`docs/` (01-unlock … 04-speaker-mode, recovery, compatibility), `magisk-module/`
(+`build.sh` → flashable zip), `helper-app/` (source + build/sign), `tools/`,
`scripts/`.

Rules baked in:
1. **No personal data committed** — `.gitignore` excludes device dumps, snapshots,
   serials, BT MACs, eSIM IDs; docs use placeholders (`<your-serial>`). `DEVICE_NAME`
   is per-user config.
2. **No firmware redistributed** — stock images are copyright rainx and git-ignored;
   docs instruct each user to dump their own. mtkclient referenced as upstream, not
   vendored.
3. **Device-parameterized + honest caveats** — `compatibility.md` states this is
   validated on theloop/MT6877 and that the **open-BROM unlock is likely specific to
   early units**; every user must verify their own BROM is unauthenticated
   (`mtk printgpt`) before trusting the unlock. Input codes and the sink prop are
   config, not hardcoded.

The already-completed unlock/root/debloat/snapshot work becomes docs steps 1–3; the
speaker module is step 4.

## 10. Error handling & safety

- Button daemon auto-respawns (Magisk service loop); if it dies, buttons fail safe to
  normal Android behavior.
- Pairing window always self-closes; never left discoverable.
- Always boots to Dumb regardless of last state.
- Power-button long-press shutdown preserved even though tap is repurposed.
- `PERMANENT_DISABLE` (rainx) re-enforced on every mode switch and boot — never runs.
- Auto-sleep suppressed during playback.
- Disabling/removing the Magisk module = clean revert to plain rooted device.

## 11. Verification plan

Independently testable units:
1. Mode scripts flip radios + 29 packages correctly; rainx stays disabled.
2. `getevent` confirms real button codes; gesture map fires correctly; volume instant.
3. Fresh phone pairs with **zero taps** inside a window; window closes after.
4. AVRCP play/pause/next/prev control the source phone.
5. Absolute volume: one synced slider iPhone↔Loop (native or bridged).
6. TTS cues + battery announcement fire.
7. Auto-sleep: 5 min → suspend, 15 min → off; not during playback; wake works.
8. Auto-reconnect on boot before pairing window.
9. Reboot → Dumb + pairing window auto-opens. Mode toggle Dumb↔Full both ways.
10. Module disable → clean revert.
