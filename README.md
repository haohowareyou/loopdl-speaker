# loopdl-speaker

Root the **rainx LoopDL** (*"Portable 5G WiFi & Bluetooth Speaker"*, sold at
[loopdl.co.uk](https://www.loopdl.co.uk), MediaTek **MT6877 / Dimensity 900**, Android 15,
a full Android phone underneath) and turn it into a lean **Bluetooth speaker**, with a
one-gesture switch back to a complete Android phone.

This repo ships **no OEM firmware**. You dump your own. Modifying the bootloader or
firmware risks data loss; see [`docs/recovery.md`](docs/recovery.md).

This is an independent, unofficial project. It is not affiliated with, endorsed by, or
sponsored by the maker or seller of the LoopDL. "LoopDL" and other product names are used
only to identify the device this works on; the marks belong to their respective owners.

## About this project

I am not a developer and I am not certified in any of this. I built it by vibe coding
with an AI assistant, because I wanted my own LoopDL to work as a simple Bluetooth
speaker.

It works on my unit, but I cannot vouch for yours, and I will not pretend to understand
every detail of how it works. If you want to reproduce it, the most reliable path is
probably to point your LLM or coding agent of choice at this repo and let it walk you
through the steps for your own device. Read everything before you run it, keep your own
backups, and do it all at your own risk. If something breaks or bricks, that is on you,
not me.

## Risks and prerequisites

Read this before you start. Some of what follows cannot be undone:

- **Unlocking wipes the device.** The bootloader unlock erases all user data. Your eSIM
  survives (it lives on the eUICC chip), everything else in userdata is gone.
- **It voids your warranty.** Treat the device as out of support the moment you unlock.
- **It can soft-brick a protected unit.** The unlock only works if your BROM is
  unauthenticated, meaning `SLA`, `SBC`, and `DAA` all read `False`. Run `mtk printgpt`
  and check first; if any reads `True`, stop. The procedure here will not work on your
  unit and pushing ahead risks leaving it unbootable.
- **Back up before you start.** Dump this unit's partitions while it still works, before
  the destructive unlock; it is the only thing that makes recovery possible. See step 1 of
  [`docs/01-unlock.md`](docs/01-unlock.md).

What the host computer needs (the machine you run this from):

- **Python 3.12** for mtkclient (`brew install python@3.12`, or from python.org).
- **Android Platform Tools** for `adb` and `fastboot`, on your PATH.
- To build the helper app yourself: **JDK 17**, the **Android SDK** build-tools, and the
  **NDK** (see `tools/setup-ndk.sh`).

The docs assume macOS. Linux needs minor path changes; Windows is untested. There is no
pre-built download. You build the module and app from source.

## What you get

- **Dumb-Speaker mode** (default on every boot): Bluetooth A2DP sink, hardware media
  controls, one synced volume slider, radios off, screen dark. Auto-pairing, idle
  auto-sleep, audio earcon feedback, battery safety shutdowns.
- **Full-Phone mode** (one gesture): a normal Android phone again, all apps restored
  except the 3 rainx packages (permanently removed to cut the privacy attack surface).
  Lawnchair launcher with
  Firefox/Camera/Settings/Play Store dock, and quick-toggle QS tiles for Mobile Data and
  Hotspot.
- **Audio earcons**: synthesized musical tones on the alarm stream (the screenless
  feedback channel). The only spoken cue is battery percentage on demand.
- **Battery safety**: low-battery earcons at 20% and 10%; graceful shutdown at ~5%.
- **BT PAN off**: the speaker never uses the phone's data over Bluetooth (audio only).
- **Snapshots**: three restore points (unrooted / rooted / speaker-mode), each with a
  logical-state snapshot and partition backups.

## Walkthrough (stock → speaker)

1. [Unlock the bootloader](docs/01-unlock.md): MediaTek BROM via mtkclient.
   **Key gotcha:** connect in *preloader* mode (power off, no buttons), not BROM mode
   or stage-2 hangs on DRAM config.
2. [Root with Magisk](docs/02-root.md): flash patched `init_boot` over fastboot.
3. [Debloat](docs/03-debloat.md): the 3 rainx packages permanently removed;
   29 toggled packages managed by the module per-mode.
4. [Install the speaker module](docs/04-speaker-mode.md): the `loop-speaker-mode`
   Magisk module. Installs in 3 steps; boots to speaker mode.
5. [Snapshots](docs/05-snapshots.md): capture and restore logical + partition state.

Also: [Compatibility](docs/compatibility.md) | [Recovery](docs/recovery.md)

## Will this work on my LoopDL?

Validated on early units where the BROM is fully unauthenticated. Run `mtk printgpt`
to check yours before trusting the unlock; see
[`docs/compatibility.md`](docs/compatibility.md). The speaker module itself is generic
Android shell + priv-app + native daemon; only the unlock is unit-sensitive.

## Tools

| Script | What it does |
|--------|-------------|
| `tools/build-module.sh` | Build `build/loop-speaker-mode.zip` (the flashable Magisk module) |
| `tools/build-app.sh` | Build the helper APK (`io.github.haohowareyou.loopdl`) |
| `tools/snapshot-state.sh` | Capture a logical-state snapshot (packages/settings/props) |
| `tools/restore-state.sh` | Restore package enable/disable layout from a snapshot |
| `tools/run-partition-backup.sh` | Dump this unit's per-unit identity partitions before unlocking |
| `tools/capture-unrooted-baseline.sh` | Dump pristine stock firmware (the pre-unlock baseline) |
| `tools/loop-debloat.sh` | Manage the 3 rainx permanent-removes: `status` / `remove` / `restore` |
| `tools/apply-lawnchair-layout.sh` | Set the Lawnchair dock to Firefox / Camera / Settings / Play Store |

## License

MIT (code). No warranty. See [`LICENSE`](LICENSE).
