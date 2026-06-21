#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/loop-speaker-mode.zip"
mkdir -p "$ROOT/build"
cd "$ROOT/magisk-module"
if [ ! -f "$ROOT/magisk-module/system/priv-app/LoopDLSpeaker/LoopDLSpeaker.apk" ]; then
  echo "Run tools/build-app.sh first (helper APK missing)"
  exit 1
fi
rm -f "$OUT"
zip -r9 "$OUT" . -x '.*'
echo "built: $OUT"
