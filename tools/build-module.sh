#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/loop-speaker-mode.zip"
mkdir -p "$ROOT/build"
cd "$ROOT/magisk-module"
rm -f "$OUT"
zip -r9 "$OUT" . -x '.*'
echo "built: $OUT"
