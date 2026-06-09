# Step 1: Unlock the bootloader via MediaTek BROM

The LoopDL's OEM bootloader lock **cannot** be removed through the normal Android
Developer Options toggle — it is absent on this device. The only path is the
MediaTek Boot ROM (BROM), which on early units requires no authentication at all.

---

## Verify your BROM is unauthenticated first

Before doing anything destructive, confirm the BROM is open on your unit:

```bash
cd ~/path/to/mtkclient
./venv/bin/python mtk.py printgpt
# Connect device: powered OFF, then plug USB — NO buttons held (see below)
```

Expected output includes:

```
HW code: 0x0959 (MT6877)
SLA: False
SBC: False
DAA (root-cert): False
```

If `SLA`, `SBC`, or the root cert show `True`, **stop** — your unit has BROM
authentication enabled and the unlock process described here will not work. See
[`docs/compatibility.md`](compatibility.md) for more details.

---

## CRITICAL: connect in PRELOADER mode, not BROM mode

> **This is the most important gotcha.** Getting the connection mode wrong causes
> mtkclient to hang indefinitely at the "Uploading stage 2" step (DRAM configuration
> never completes).

| Mode | How to enter | Result |
|------|-------------|--------|
| **PRELOADER** (correct) | Power OFF the device completely, then plug USB with **NO buttons held** | mtkclient loads the DA and stage 2 completes — all commands work |
| BROM (wrong for this) | Hold Vol-Down + Vol-Up while plugging USB | mtkclient hangs on stage-2 DRAM config; never proceeds |

The preloader runs just long enough to supply DRAM/EMI config to the DA; BROM mode
skips that, leaving the DA unable to initialize memory.

---

## Setup: mtkclient

```bash
git clone https://github.com/bkerler/mtkclient
cd mtkclient
python3.12 -m venv venv
./venv/bin/pip install -r requirements.txt   # CLI deps only; no pyside6/GUI needed
```

If you get an import error about `libfuse` / `macFUSE` on macOS, patch two files to
treat the missing library as non-fatal:

```python
# In Library/Filesystem/mtkdafs.py  and  Library/DA/mtk_da_handler.py
# Change:
except ImportError:
# To:
except (ImportError, OSError):
```

Run all mtkclient commands as:

```bash
./venv/bin/python mtk.py <command>
```

---

## Step-by-step unlock

### 1. Back up critical partitions (do this before unlocking)

See [`docs/recovery.md`](recovery.md) for the full backup recipe. At minimum, back up
`init_boot_a`, `seccfg`, and `vbmeta_a` before proceeding.

### 2. Unlock seccfg

```bash
./venv/bin/python mtk.py da seccfg unlock
```

When prompted (or when the script waits for the device):

- Power the device **completely off**
- Plug USB — **NO buttons held** (PRELOADER mode)

Expected output ends with something like:

```
Jumping to 0x200000: ok
[DA] XFlashExt SBC patched
Unlocking seccfg... done
```

**This is destructive:** the bootloader will wipe `userdata` and `metadata` on the
next boot. eSIM credentials stored in the eUICC survive (they live on the eUICC chip,
not in userdata). Everything else in userdata is gone.

### 3. Erase userdata and metadata

```bash
./venv/bin/python mtk.py e userdata,metadata
```

Same connection: device off, plug with no buttons. This pre-erases cleanly so the
first boot does not stall on an unexpected wipe prompt.

### 4. Boot and configure

Power on normally. The device boots through the standard Android setup. There is **no
orange-screen unlock warning** on this OEM — its absence is normal for rainx hardware,
not an indication that anything went wrong.

After setup, enable Developer Options (tap Build Number 7× in Settings → About phone)
and turn on **USB debugging**.

Verify the unlock:

```bash
adb shell getprop ro.boot.vbmeta.device_state   # → unlocked
adb shell getprop ro.boot.verifiedbootstate      # → orange
```

---

## What this unlocks

Once `seccfg` is unlocked, `fastboot` works fully — you can flash partitions (e.g.
`init_boot` for rooting) and the BROM remains reachable for recovery. The device is
now in a state where the root steps in [docs/02-root.md](02-root.md) work.
