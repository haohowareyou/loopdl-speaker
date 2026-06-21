# loopdl-speaker

Root the **rainx LoopDL** — the *"Portable 5G WiFi & Bluetooth Speaker"* sold at
[loopdl.co.uk](https://www.loopdl.co.uk) (MediaTek **MT6877 / Dimensity 900**, Android 15,
a full Android phone underneath) — and turn it into a lean **Bluetooth speaker**, with a
one-gesture switch back to a complete Android phone.

This repo ships **no OEM firmware** — you dump your own. Modifying bootloader/firmware
risks data loss; see [`docs/recovery.md`](docs/recovery.md).

## What you get

- **Dumb-Speaker mode** (default on every boot): Bluetooth A2DP sink, hardware media
  controls, one synced volume slider, radios off, screen dark. Auto-pairing, idle
  auto-sleep, audio earcon feedback, battery safety shutdowns.
- **Full-Phone mode** (one gesture): a normal Android phone again — all apps restored
  except the 3 rainx packages (permanently removed for privacy). Lawnchair launcher with
  Firefox/Camera/Settings/Play Store dock, and quick-toggle QS tiles for Mobile Data and
  Hotspot.
- **Audio earcons**: synthesized musical tones on the alarm stream — the screenless
  feedback channel. The only spoken cue is battery percentage on demand.
- **Battery safety**: low-battery earcons at 20% and 10%; graceful shutdown at ~5%.
- **BT PAN off**: the speaker never uses the phone's data over Bluetooth (audio only).
- **Snapshots**: three restore points (unrooted / rooted / speaker-mode), each with a
  logical-state snapshot and partition backups.

## Walkthrough (stock → speaker)

1. [Unlock the bootloader](docs/01-unlock.md) — MediaTek BROM via mtkclient.
   **Key gotcha:** connect in *preloader* mode (power off, no buttons), not BROM mode
   or stage-2 hangs on DRAM config.
2. [Root with Magisk](docs/02-root.md) — flash patched `init_boot` over fastboot.
3. [Debloat](docs/03-debloat.md) — the 3 rainx packages permanently removed;
   29 toggled packages managed by the module per-mode.
4. [Install the speaker module](docs/04-speaker-mode.md) — the `loop-speaker-mode`
   Magisk module. Installs in 3 steps; boots to speaker mode.
5. [Snapshots](docs/05-snapshots.md) — capture and restore logical + partition state.

Also: [Compatibility](docs/compatibility.md) | [Recovery](docs/recovery.md)

## Will this work on my LoopDL?

Validated on early units where the BROM is fully unauthenticated. Run `mtk printgpt`
to check yours before trusting the unlock — see
[`docs/compatibility.md`](docs/compatibility.md). The speaker module itself is generic
Android shell + priv-app + native daemon; only the unlock is unit-sensitive.

## Tools

| Script | What it does |
|--------|-------------|
| `tools/build-module.sh` | Build `build/loop-speaker-mode.zip` — the flashable Magisk module |
| `tools/build-app.sh` | Build the helper APK (`io.github.haohowareyou.loopdl`) |
| `tools/snapshot-state.sh` | Capture a logical-state snapshot (packages/settings/props) |
| `tools/restore-state.sh` | Restore package enable/disable layout from a snapshot |
| `tools/capture-unrooted-baseline.sh` | Dump pristine firmware from a new factory unit (stage 1) |
| `tools/loop-debloat.sh` | Manage the 3 rainx permanent-removes: `status` / `remove` / `restore` |
| `tools/apply-lawnchair-layout.sh` | Set the Lawnchair dock to Firefox / Camera / Settings / Play Store |

## License

MIT (code). No warranty. See [`LICENSE`](LICENSE).
