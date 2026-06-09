#!/usr/bin/env bash
# Build and sign co.loop.speaker, install APK into magisk-module tree.
# Usage: bash tools/build-app.sh
# Requires: ANDROID_HOME (or ANDROID_SDK_ROOT), JAVA_HOME (JDK 17)
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$ROOT/helper-app"
OUT_DIR="$ROOT/magisk-module/system/priv-app/LoopSpeaker"

cd "$APP_DIR"

# Generate debug keystore if missing (gitignored)
if [ ! -f debug.keystore ]; then
  echo "[build-app] generating debug keystore..."
  keytool -genkey -v \
    -keystore debug.keystore \
    -storepass android \
    -alias loop \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=loop"
fi

# Assemble release APK
echo "[build-app] assembling release APK..."
./gradlew :app:assembleRelease

APK="app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$APK" ]; then
  echo "[build-app] ERROR: APK not found at $APK" >&2
  exit 1
fi

# Find apksigner in build-tools (use the newest version available)
APKSIGNER=$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if [ -z "$APKSIGNER" ]; then
  echo "[build-app] ERROR: apksigner not found under $ANDROID_HOME/build-tools/" >&2
  exit 1
fi
echo "[build-app] using apksigner: $APKSIGNER"

# Sign and output directly into the module tree
mkdir -p "$OUT_DIR"
# --v4-signing-enabled false: do NOT emit a .idsig. APK Signature Scheme v4 requires
# fs-verity on the file, which Magisk's tmpfs/overlay (/system/priv-app) does not support
# — PackageManager then fails cert collection ("Failed to measure fs-verity, errno 13")
# and silently skips the whole package. v2/v3 (always on) need no fs-verity.
"$APKSIGNER" sign \
  --ks debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --v4-signing-enabled false \
  --out "$OUT_DIR/LoopSpeaker.apk" \
  "$APK"
rm -f "$OUT_DIR/LoopSpeaker.apk.idsig"

echo "[build-app] done → $OUT_DIR/LoopSpeaker.apk"
ls -lh "$OUT_DIR/LoopSpeaker.apk"
