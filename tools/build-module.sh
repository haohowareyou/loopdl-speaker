#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/loop-speaker-mode.zip"
SRC="$ROOT/magisk-module"
mkdir -p "$ROOT/build"

if [ ! -f "$SRC/system/priv-app/LoopDLSpeaker/LoopDLSpeaker.apk" ]; then
  echo "Run tools/build-app.sh first (helper APK missing)"
  exit 1
fi

# Build provenance stamp: makes a deployed unit self-describing (which commit it was
# built from) so "is this unit outdated?" is a one-line check instead of guesswork.
# The stamp is injected into a staged COPY, so the source tree stays clean.
COMMIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
COMMIT_DATE="$(git -C "$ROOT" log -1 --format=%cs 2>/dev/null || echo unknown)"
BUILT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ]; then DIRTY=1; else DIRTY=0; fi
BASE_VERSION="$(sed -n 's/^version=//p' "$SRC/module.prop")"
SUFFIX="g${COMMIT}"
[ "$DIRTY" = 1 ] && SUFFIX="${SUFFIX}+dirty"
STAMPED_VERSION="${BASE_VERSION} (${SUFFIX} ${COMMIT_DATE})"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp -R "$SRC/." "$STAGE/"

# Drop stray "<name> 2" / "<name> 3" numbered-duplicate files from the staged copy so
# they never ship to a device. This macOS/iCloud rename artifact recurs in this tree
# (e.g. a stale "system/bin/loopkeyd 2"); strip it from the build, not the source.
find "$STAGE" -depth -name '* [0-9]' -exec rm -rf {} + 2>/dev/null || true
find "$STAGE" -depth -name '* [0-9].*' -exec rm -rf {} + 2>/dev/null || true

# 1) surface provenance in the Magisk app's version string
sed -i.bak "s#^version=.*#version=${STAMPED_VERSION}#" "$STAGE/module.prop" && rm -f "$STAGE/module.prop.bak"

# 2) machine-readable build-info -> installs at /data/adb/modules/loop-speaker-mode/build-info
#    read on-device with: adb shell su -c 'cat /data/adb/modules/loop-speaker-mode/build-info'
cat > "$STAGE/build-info" <<EOF
version=${BASE_VERSION}
commit=${COMMIT}
commit_date=${COMMIT_DATE}
built=${BUILT}
dirty=${DIRTY}
EOF

rm -f "$OUT"
( cd "$STAGE" && zip -r9 "$OUT" . -x '.*' )
echo "built: $OUT"
echo "stamp: ${STAMPED_VERSION}"
