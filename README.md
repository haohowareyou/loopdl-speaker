# loopdl-speaker

Turn a **rainx "LoopDL"** (the ~£95 MT6877 "5G speaker" that's a stock Android phone
underneath) into a proper, lean **Bluetooth speaker** — with an optional one-gesture
switch back to a full Android phone.

> ⚠️ **Status: work in progress.** The design is settled (see
> [`docs/design/2026-06-09-loop-speaker-mode.md`](docs/design/2026-06-09-loop-speaker-mode.md));
> implementation is in progress. This repo ships **no OEM firmware** — you dump your
> own. Modifying bootloader/firmware risks data loss; see `docs/recovery.md`.

## What you get

- **Dumb-Speaker mode** (default): radios off except Bluetooth, screen dark,
  auto-pairing, hardware media controls, one synced volume slider. Long battery life.
- **Full-Phone mode** (a 5-second button hold): a normal Android phone again — every
  app back **except** the rainx/Loop apps, which stay permanently disabled so the
  device never tracks you or force-enables mic/location.

## The walkthrough (stock → speaker)

1. [Unlock the bootloader](docs/) — MediaTek BROM via mtkclient. **Key gotcha:**
   connect in *preloader* mode (no buttons), not BROM mode, or stage-2 hangs on DRAM.
2. [Root with Magisk](docs/)
3. [Debloat](docs/)
4. [Install the speaker module](docs/)

## ⚠️ Will this work on *my* LoopDL?

Validated on early units where the **BROM is fully unauthenticated**. Before trusting
the unlock, verify your own with `mtk printgpt` — see
[`docs/compatibility.md`](docs/). The speaker module itself is generic Android +
config; only the unlock is unit-sensitive.

## License

MIT (code). No warranty. See [`LICENSE`](LICENSE).
