#!/usr/bin/env bash
# Locate the Android NDK and export NDK= for the native Makefile.
set -euo pipefail

if [ -z "${NDK:-}" ]; then
  NDK="$(ls -d "${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"/ndk/* 2>/dev/null | tail -1 || true)"
fi

[ -n "${NDK:-}" ] || {
  echo "Install NDK: 'sdkmanager --install ndk;26.*' or brew install --cask android-ndk" >&2
  exit 1
}

echo "NDK=$NDK"
