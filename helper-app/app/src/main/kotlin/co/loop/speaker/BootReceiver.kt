package co.loop.speaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Intentionally does NOT trigger mode_dumb on boot. The module's service.sh is the
 * single authoritative boot trigger: it grants BT perms, forces the AVRCP Controller
 * role and bounces the BT stack, THEN broadcasts mode_dumb so the app binds the right
 * profile and pairs exactly once. Firing mode_dumb here too (pre-bounce) made the
 * speaker pair twice on every boot — once with the wrong role, then again after the
 * bounce. Left as a no-op so the manifest receiver can be re-purposed later if needed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // no-op: see class doc — service.sh drives the dumb-mode boot path
    }
}
