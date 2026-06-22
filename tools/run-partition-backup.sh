#!/usr/bin/env bash
# Back up THIS unit's per-unit identity partitions before unlocking.
#
# These partitions are UNIQUE per device (serial, RF calibration, lock state, secure
# storage) and cannot be recovered from any other unit or re-downloaded. If the device
# bricks without this backup, that data is gone for good. Run this on a still-working,
# pre-unlock unit; it is the brick-insurance half of a stage backup. The GENERIC
# firmware half (boot/init_boot/vbmeta/lk/tee/...) is a separate dump: see
# capture-unrooted-baseline.sh.
#
# Device prep: power OFF, then plug USB in PRELOADER mode (NO buttons held). mtkclient
# polls the short preloader window and retries the handshake until it lands (screen
# stays black).
#
# Usage:  tools/run-partition-backup.sh [mtkclient_dir] [out_dir]
set -uo pipefail
MTK_DIR="${1:-../mtkclient}"
OUT="${2:-../loop-backup/identity}"

[ -x "$MTK_DIR/venv/bin/python" ] || { echo "mtkclient venv not found at $MTK_DIR (pass its path as \$1)"; exit 1; }
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"   # absolute, so it survives the cd into MTK_DIR below

# Per-unit identity / lock / secure-state partitions (small, unique per device).
PARTS="nvram protect1 protect2 persist nvdata nvcfg frp seccfg para misc gpt"
# A failed read of one of these is dangerous (not merely "absent"); warn loudly.
CRITICAL="nvram protect1 protect2 seccfg persist"

echo ">> dumping per-unit identity partitions to $OUT"
echo ">> (preloader mode: device OFF, replug, NO buttons; mtkclient will retry the handshake)"
echo ">> these are UNIQUE to this unit and cannot be recovered from anywhere else."
cd "$MTK_DIR" || { echo "cannot cd into mtkclient dir: $MTK_DIR"; exit 1; }
for p in $PARTS; do
  echo "   -- $p"
  if ! ./venv/bin/python mtk.py r "$p" "$OUT/$p.img" 2>>"$OUT/backup-errors.log"; then
    case " $CRITICAL " in
      *" $p "*) echo "   !! WARNING: $p is a critical identity partition and FAILED to read -- do NOT proceed; see $OUT/backup-errors.log and retry" ;;
      *)        echo "   (skip $p - not present / read failed)" ;;
    esac
  fi
done

cat > "$OUT/README.md" <<EOF
# Per-unit identity partitions ($(date +%F))

This unit's unique identity / RF / lock / secure-state partitions, dumped before
unlocking. Irreplaceable: they cannot come from another unit or be re-downloaded.

Pair this with a generic firmware baseline (capture-unrooted-baseline.sh) for a
complete restore set. To recover a partition, write it back over the BROM in
PRELOADER mode (see docs/recovery.md):

  mtk.py w <partition> <file>.img

Some dumps may be padded larger than the real partition (read fell back to a default
length); the true data is at offset 0. docs/recovery.md explains truncating before
writing.
EOF

echo ">> done -> $OUT"
echo ">> verify the .img files are non-empty before relying on them:  ls -lh \"$OUT\""
