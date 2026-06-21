package io.github.haohowareyou.loopdl

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: 1-tap jump to the Wi-Fi hotspot screen. The Loop's useful "phone" job
 * is being a mobile hotspot (share its 5G), so this is a fast way in.
 *
 * We open the hotspot settings screen rather than toggling SoftAP headlessly: a true headless
 * toggle is privileged, overrides the saved hotspot SSID/password, and doesn't reliably bring
 * up tethering NAT on this ROM. The settings screen has the real toggle, the saved config, and
 * working internet sharing, so it's the robust "hotkey".
 */
class HotspotTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Hotspot"
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent()
            .setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$WifiTetherSettingsActivity"
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A14+ requires the PendingIntent overload of startActivityAndCollapse.
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        startActivityAndCollapse(pi)
    }
}
