# Snapshots & restore — the three device stages

The Loop moves through three stages. Each has a **restore point** so you can always get back,
and a brick is recoverable because the MediaTek BROM stays open (see `docs/01-unlock.md`).

| Stage | What it is | How it's captured |
|-------|-----------|-------------------|
| 1 — **unrooted** | Pristine factory firmware, locked | Generic firmware dump from a factory unit (`tools/capture-unrooted-baseline.sh`) |
| 2 — **rooted** | Unlocked + Magisk + A2DP-sink, pre-debloat | Logical snapshot + per-unit partition dump (already captured) |
| 3 — **speaker-mode** | Speaker module installed + configured | Logical snapshot (`tools/snapshot-state.sh`) |

Each snapshot has two halves:

- **Logical state** (packages, settings, props, Magisk/module state) — captured by
  `tools/snapshot-state.sh <label>`, restored by `tools/restore-state.sh <dir>`. Fast,
  reversible, non-destructive.
- **Partition state** (the irreplaceable per-unit identity/RF/lock partitions, and the
  generic firmware) — raw mtkclient dumps. This is the brick-insurance half.

Captured snapshots live outside the repo (gitignored) because they contain device identifiers
(serial, eSIM, BT MACs). This project's are under `../loop-backup/`.

## What is already captured (this unit)

- `loop-backup/partitions-2026-06-09/` — this unit's **identity partitions**
  (nvram/protect1/protect2/persist/nvdata/nvcfg/seccfg/frp/misc/para/gpt). Irreplaceable, unique
  per device. The generic firmware was not cleanly dumped here (xflash desynced) — that's what
  stage 1 below fills in.
- `loop-backup/snapshot-2026-06-09-rooted-baseline/` — **stage 2** logical snapshot.
- `loop-backup/snapshot-<date>-speaker-mode/` — **stage 3** logical snapshot.

## Capturing stage 1 (unrooted) — do this on a NEW unit

This unit is already rooted, so a pristine unrooted image can't be taken from it. Capture the
generic stock firmware from a **new, factory LoopDL** (cross-unit identical, so it's a valid
baseline for any unit):

```bash
# New unit: power OFF, plug USB in PRELOADER mode (no buttons).
tools/capture-unrooted-baseline.sh ../mtkclient
# -> ../loop-backup/stage1-unrooted-<date>/  (boot/init_boot/vbmeta/lk/tee/... + gpt)
```

Identity partitions are NOT taken from another unit — they're unique per device. Each unit's
own identity partitions are dumped separately (`run-partition-backup.sh`).

## Capturing / restoring the logical state

```bash
# Capture the current logical state under a stage label:
tools/snapshot-state.sh speaker-mode        # -> snapshots/snapshot-<date>-speaker-mode/

# Roll the package layout back to any snapshot (reversible):
tools/restore-state.sh ../loop-backup/snapshot-2026-06-09-rooted-baseline
```

`restore-state.sh` only re-applies the package enable/disable layout (safe and reversible).
Settings/props are kept in the snapshot for reference but not auto-applied — they're too
device-stateful to blindly replay.

## Moving between stages

- **speaker-mode → rooted:** disable the Magisk module (Magisk app → toggle off → reboot), or
  `magisk --remove-modules`. The module writes no partitions, so this fully reverts mode
  behavior. Then `restore-state.sh` the rooted snapshot to re-sync packages.
- **rooted → unrooted (fully stock + locked):** flash stock `init_boot` (removes Magisk),
  restore any touched identity partitions, then `mtk.py da seccfg lock`. See
  `docs/01-unlock.md` / `docs/recovery.md` for the exact mtkclient commands.
- **unrooted → rooted → speaker-mode:** follow `docs/02-root.md` then `docs/04-speaker-mode.md`.

## Permanently removing rainx/loop bloatware

Beyond the default disable, the 3 rainx packages can be hard-removed (reversibly) — see
`tools/loop-debloat.sh` and `docs/03-debloat.md`.
