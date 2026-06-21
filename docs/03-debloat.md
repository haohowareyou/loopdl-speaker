# Step 3: Debloat

After rooting, disable or remove packages that are unwanted for a speaker use-case.
The speaker module manages the toggle list automatically once installed; this page
documents the package strategy and how to manage the permanent rainx removals.

**Manual vs automatic:** once the speaker module ([step 4](04-speaker-mode.md)) is
installed it applies the 29 toggled packages and enables the A2DP sink on every boot for
you. The only one-time manual action on this page is hard-removing the 3 rainx packages
(`loop-debloat.sh remove`), and that is idempotent and can be done before or after the
module. The A2DP commands further down are shown so you understand what the module does;
you do not have to run them by hand for normal use.

---

## Package strategy

Packages are split into two lists, both under `magisk-module/scripts/`:

### Permanent removes (3 packages): `packages-permanent-disable.txt`

These are permanently removed (uninstalled for user 0) in **both** Dumb-Speaker and
Full-Phone mode. They are never re-enabled by the module. This is the privacy rationale:
with the vendor's preinstalled packages removed, none of that code runs, which reduces the
device's privacy attack surface.

```
co.rainx.loop.launcher
co.rainx.loop.setup
vendor.rainx.setupwizard.overlay
```

`tools/loop-debloat.sh` manages these:

```bash
tools/loop-debloat.sh status   # show current install state
tools/loop-debloat.sh remove   # uninstall all 3 for user 0
tools/loop-debloat.sh restore  # reinstall from system APK (reversible)
```

`remove` uses `pm uninstall --user 0`; the system APK stays on the read-only
partition, so `restore` can bring them back. The packages are **currently removed**
on this unit.

### Toggled packages (29 packages): `packages-toggled.txt`

These are `pm disable-user` in Dumb-Speaker mode and `pm enable` in Full-Phone mode.
They cover standard Android apps that are useful on a phone but unnecessary (and
battery/privacy expensive) on a speaker:

```
com.android.chrome
com.google.android.apps.adm
com.google.android.apps.books
com.google.android.apps.carrier.carrierwifi
com.google.android.apps.docs
com.google.android.apps.googleassistant
com.google.android.apps.maps
com.google.android.apps.messaging
com.google.android.apps.nbu.files
com.google.android.apps.photos
com.google.android.apps.restore
com.google.android.apps.safetyhub
com.google.android.apps.setupwizard.searchselector
com.google.android.apps.tachyon
com.google.android.apps.turbo
com.google.android.apps.wellbeing
com.google.android.apps.youtube.kids
com.google.android.apps.youtube.music
com.google.android.calendar
com.google.android.cellbroadcastreceiver
com.google.android.contacts
com.google.android.dialer
com.google.android.federatedcompute
com.google.android.gm
com.google.android.googlequicksearchbox
com.google.android.healthconnect.controller
com.google.android.ondevicepersonalization.services
com.google.android.videos
com.google.android.youtube
```

These package lists are the source of truth. If you add or remove entries, the module
picks up the change on the next mode switch.

**Packages kept enabled at all times** (the minimum working set for the speaker):
Play Store, GMS/gsf, eUICC (eSIM), `com.android.phone` (mobile data stack),
`com.android.launcher3` (home screen), Latin keyboard, TTS engine.

---

## A2DP sink enablement

> The speaker module does all of this automatically on every boot (see Persistence below).
> The commands here are for understanding and one-off manual testing; you do not need to
> run them by hand for normal use.

By default, Android does not expose the Bluetooth A2DP sink profile (receive audio
from a phone). Enabling it requires two `resetprop` calls and a Bluetooth stack
restart, and the restart has a gotcha:

```bash
# As root:
resetprop bluetooth.profile.a2dp.sink.enabled true
resetprop bluetooth.profile.a2dp.source.enabled false

# Toggling the adapter alone does NOT make the stack re-read props:
#   cmd bluetooth_manager disable && cmd bluetooth_manager enable   ← DOES NOT WORK

# You must force-stop the BT process instead:
am force-stop com.android.bluetooth
# Then re-enable:
cmd bluetooth_manager enable
```

Confirm it took:

```bash
dumpsys bluetooth_manager | grep -A2 'A2DP Sink'
# Expected: A2DP Sink State: Enabled
```

### Persistence across reboots

The module installs a `post-fs-data.d` script that sets these props **before** the
Bluetooth stack starts on every boot. A clean boot brings the sink up automatically.

The persistence script lives at:
```
/data/adb/post-fs-data.d/a2dp-sink.sh
```

and is installed by the Magisk module in [step 4](04-speaker-mode.md).

---

## Rolling back over-debloat

If you accidentally disable something essential, use the snapshot's
`restore-package-state.sh` (in your rooted-baseline snapshot directory under
`../loop-backup/`):

```bash
./restore-package-state.sh
```

This re-enables every package that was enabled at the time of the snapshot and
verifies the result. See [docs/recovery.md](recovery.md) for the full recovery ladder.
