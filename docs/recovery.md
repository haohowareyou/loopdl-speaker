# Recovery

Layered recovery options, from least to most invasive. Start at layer 1 and only
escalate if the previous layer does not help.

---

## Layer 1: Disable the module (Magisk Safe Mode)

Use this when the module is causing a boot loop or unexpected behavior.

**No adb required.** Hold **Vol-Down** during the boot animation. Magisk boots without
activating any modules. The device returns to normal Android.

On the next normal reboot, the module is active again. To permanently disable it, use
the Magisk app (Modules → loop-speaker-mode → toggle off) or remove it.

To remove from adb:

```bash
adb shell su -c 'magisk --remove-modules'
```

`uninstall.sh` runs automatically on removal and re-enables the 29 toggled packages.
The 3 rainx packages are not re-enabled (they were disabled before the module was
installed; leave them disabled).

---

## Layer 2: Restore package state (over-debloat)

If you've accidentally disabled something essential and the device is still functional
enough to run adb:

```bash
# From the repo root:
cd loop-backup/snapshot-2026-06-09-rooted-baseline
./restore-package-state.sh
```

This script re-enables every package that was enabled at the time of the rooted
baseline snapshot and verifies the result. It does not touch partitions or root.

Reboot after running it.

---

## Layer 3: Flash stock init_boot (un-root)

To remove Magisk without touching anything else:

```bash
# Boot to fastboot:
adb reboot bootloader

# Flash the original unpatched init_boot:
fastboot flash init_boot /path/to/stock/init_boot_a.img
fastboot reboot
```

This removes root completely. The bootloader remains unlocked (seccfg still shows
unlocked state). You can re-root at any time by repeating [step 2](02-root.md).

---

## Layer 4: Restore individual partitions via mtkclient

Use this when you need to recover a specific partition (e.g. `seccfg`, `vbmeta`,
`init_boot`) from the raw backup in `loop-backup/partitions-2026-06-09/`.

**Device must be in PRELOADER mode** (power off, plug USB - **NO buttons held**; see
[docs/01-unlock.md](01-unlock.md) for the explanation of why this matters).

```bash
cd ~/path/to/mtkclient

# Restore a single partition:
./venv/bin/python mtk.py w <partition_name> \
    ../loop-backup/partitions-2026-06-09/<partition_name>.img
```

For example, to restore `seccfg`:

```bash
./venv/bin/python mtk.py w seccfg \
    ../loop-backup/partitions-2026-06-09/seccfg.img
```

### Over-read caveat

Some `.img` files in the partition backup were read with mtkclient falling back to a
default length, producing a 64 MiB file even though the real partition is smaller.
**The true partition data is at offset 0**; the tail is padding. For a clean restore,
truncate to the real size from the GPT before writing:

```bash
# 1. Find the true partition size (bytes) from gpt.bin or mtk printgpt output
# 2. Truncate:
head -c <true_size_bytes> seccfg.img > seccfg.trunc.img
# 3. Flash the truncated image:
./venv/bin/python mtk.py w seccfg ../loop-backup/partitions-2026-06-09/seccfg.trunc.img
```

`nvdata.img` and `nvcfg.img` already have correct geometry and can be restored
directly without truncation.

### Re-lock (return to fully stock)

To lock the bootloader and remove root:

```bash
# Flash stock init_boot first (removes Magisk):
./venv/bin/python mtk.py w init_boot_a \
    /path/to/stock-firmware/init_boot_a.img

# Then re-lock seccfg:
./venv/bin/python mtk.py da seccfg lock
```

Device will boot with `verifiedbootstate=green` and OEM lock re-applied.

---

## Layer 5: Open BROM (ultimate net)

If the device will not boot at all and mtkclient can connect, the BROM is your
fallback. As long as the MediaTek BROM is unauthenticated (confirmed via
`mtk printgpt`; see [docs/compatibility.md](compatibility.md)), every partition on
the device can be rewritten over USB.

Connect in PRELOADER mode (see layer 4) and use `mtk.py w` to restore any partition
from backup. There is no state of the software stack that is unrecoverable as long as
the BROM remains open.
