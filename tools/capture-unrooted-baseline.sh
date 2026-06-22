#!/usr/bin/env bash
# Capture STAGE 1: a pristine UNROOTED firmware baseline, from a factory LoopDL.
#
# Run this against any factory / pre-unlock unit -- your own device BEFORE you unlock it,
# or a separate untouched unit -- to capture a clean stock firmware set you can reflash to
# return ANY unit to unrooted. (It cannot be taken from an already-rooted unit, so dump it
# before you unlock, or from another factory unit.)
#
# It dumps the GENERIC firmware partitions only. These are identical across MT6877 LoopDL
# units on the SAME FIRMWARE VERSION, so a clean copy from any factory unit is a valid stock
# baseline -- but a dump from a different firmware version is NOT a safe cross-unit restore
# baseline. Record ro.build.fingerprint of BOTH the source and target units before relying on
# a cross-unit restore (the script echoes this warning too).
#
# It deliberately does NOT dump the per-unit identity partitions (nvram/protect/persist/
# nvdata/seccfg): those are unique per device and must come from each unit itself, via
# tools/run-partition-backup.sh. It also skips userdata/super-bulk (huge, not needed to
# restore "unrooted").
#
# Device prep: power OFF, then plug USB in PRELOADER mode (NO buttons held). mtkclient polls
# the short preloader window and retries the handshake until it lands (screen stays black).
#
# Usage:  tools/capture-unrooted-baseline.sh [mtkclient_dir] [out_dir]
set -uo pipefail
MTK_DIR="${1:-../mtkclient}"
OUT="${2:-../loop-backup/firmware-stock}"

[ -x "$MTK_DIR/venv/bin/python" ] || { echo "mtkclient venv not found at $MTK_DIR (pass its path as \$1)"; exit 1; }
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"   # absolute, so it survives the cd into MTK_DIR below

# Generic, cross-unit firmware (both slots) -- a clean stock set to reflash to unrooted.
PARTS="boot_a boot_b init_boot_a init_boot_b vbmeta_a vbmeta_b vbmeta_system_a vbmeta_system_b \
vbmeta_vendor_a vbmeta_vendor_b dtbo_a dtbo_b lk_a lk_b tee_a tee_b md1img_a md1img_b \
scp_a scp_b sspm_a sspm_b spmfw_a spmfw_b mcupmfw_a mcupmfw_b gpt"

echo ">> dumping stock firmware partitions to $OUT"
echo ">> (preloader mode: device OFF, replug, NO buttons; mtkclient will retry the handshake)"
echo ">> WARNING: this dump is a valid cross-unit restore baseline ONLY for units on the same"
echo ">>   firmware version. Record ro.build.fingerprint of BOTH source and target units"
echo ">>   before using this dump to restore a different unit."
cd "$MTK_DIR" || { echo "cannot cd into mtkclient dir: $MTK_DIR"; exit 1; }
for p in $PARTS; do
  echo "   -- $p"
  ./venv/bin/python mtk.py r "$p" "$OUT/$p.img" 2>/dev/null || echo "   (skip $p - not present / read failed)"
done

cat > "$OUT/README.md" <<EOF
# Stage 1 - unrooted stock firmware baseline ($(date +%F))

Generic MT6877 LoopDL firmware dumped from a factory unit. Cross-unit identical, so this is
a valid stock baseline to reflash ANY LoopDL back to unrooted.

NOT included (must come per-unit, not from this dump):
  - identity/RF/lock: nvram, protect1/2, persist, nvdata, nvcfg, seccfg
    (dump them per-unit with tools/run-partition-backup.sh -> ../loop-backup/identity/)

## Return a unit to fully stock + locked
1. Flash stock boot/init_boot/vbmeta/etc from here (mtkclient \`w <part> <file>\`).
2. Restore that unit's OWN identity partitions if they were touched.
3. Re-lock: \`mtk.py da seccfg lock\`.
See docs/05-snapshots.md.

## ⚠️ Verify sizes
mtkclient reading by-name can over-read to a default length in some DA sessions. Cross-check
each .img against the GPT geometry (gpt.img) before trusting a restore; truncate to the real
partition size if a file is padded.
EOF
echo ">> done -> $OUT"
echo ">> verify the .img files are non-empty before relying on them:  ls -lh \"$OUT\""
