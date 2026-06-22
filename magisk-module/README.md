# magisk-module

The `loop-speaker-mode` Magisk module: boot-time mode enforcement, the native button
daemon (`loopkeyd`), the helper app shipped as a priv-app, per-mode package toggling,
A2DP-sink enablement, and the root IPC poller. The `scripts/` subdir holds the shell logic
(`loop-dumb`/`loop-full`/`loop-ipc`/`lib.sh` and friends).

Build into a flashable zip with `bash ../tools/build-module.sh` (run `build-app.sh` first
so the helper APK is present).
