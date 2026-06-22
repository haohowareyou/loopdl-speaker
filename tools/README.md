# tools/

Helper scripts for building, backing up, and managing the LoopDL speaker setup.
Run them from the repo root.

## Used in the walkthrough (see ../docs/)

- `run-partition-backup.sh` - dump this unit's per-unit identity partitions before unlocking
- `capture-unrooted-baseline.sh` - dump generic stock firmware (the pre-unlock baseline)
- `build-app.sh` - build the helper APK into the module's priv-app dir
- `build-module.sh` - build the flashable Magisk module zip (run build-app.sh first)
- `snapshot-state.sh` / `restore-state.sh` - capture / restore the logical package + settings state
- `loop-debloat.sh` - status / remove / restore the 3 rainx packages
- `apply-lawnchair-layout.sh` - set the full-mode Lawnchair dock
- `setup-ndk.sh` - install the Android NDK used to build the native daemon
- `loop-mode` - on-device helper invoked by the module (not run from the host)

## Developer convenience (not part of the user walkthrough)

- `deploy-live.sh` - push a freshly built daemon/app to a connected device for testing
- `deploy-round.sh` - scripted build + deploy iteration loop
