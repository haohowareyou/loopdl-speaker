# helper-app

Android helper app (`io.github.haohowareyou.loopdl`), installed as a priv-app by the
Magisk module. Handles Bluetooth pairing and auto-accept, A2DP-sink connection, AVRCP
transport and volume sync, audio earcons, auto-reconnect, auto-sleep, battery safety, and
the Speaker Mode quick-settings tile.

Build with `bash ../tools/build-app.sh` (needs JDK 17 + Android SDK + NDK). The signed APK
is dropped into the module's priv-app dir; it is gitignored and never committed.
