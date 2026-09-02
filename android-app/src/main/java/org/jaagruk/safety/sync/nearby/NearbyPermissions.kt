package org.jaagruk.safety.sync.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The permission set Nearby Connections actually needs, per API level.
 *
 * This is fiddly enough to be worth isolating. Nearby's requirements changed twice in the range this
 * app supports (API 29 to 35), and getting it wrong produces a runtime failure with an unhelpful
 * message rather than a permission prompt:
 *
 *  * **API 29–30:** legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` plus `ACCESS_FINE_LOCATION`. Location is
 *    genuinely required — pre-31 Android treated a BLE scan as a location capability, regardless of
 *    what the app does with it.
 *  * **API 31–32:** `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`. Fine location is no
 *    longer needed once `neverForLocation` is declared, which the manifest does.
 *  * **API 33+:** as above plus `NEARBY_WIFI_DEVICES` for the Wi-Fi Direct upgrade path.
 *
 * A worker asked to grant location access just to run a buddy drill will refuse, and be right to. The
 * whole reason the manifest caps `ACCESS_FINE_LOCATION` at API 30 is so a modern handset never sees
 * that prompt.
 */
object NearbyPermissions {

    /** Permissions to request on this device, in the order they should be presented. */
    val required: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            // Both, and in this order. The platform requires FINE and COARSE to be requested together;
            // asking for FINE alone is silently downgraded on some builds, and a downgraded grant makes
            // Nearby fail on precisely the pre-31 handsets this branch exists for.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    fun allGranted(context: Context): Boolean = required.all { granted(context, it) }

    fun missing(context: Context): List<String> = required.filterNot { granted(context, it) }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * A short explanation of what is still missing, for the UI.
     *
     * Phrased in terms of what the worker is trying to do rather than the permission name. "Allow
     * nearby devices so your phone can find your buddy's phone" is actionable; "grant
     * BLUETOOTH_ADVERTISE" is not.
     */
    fun rationaleKeyFor(context: Context): String? {
        val missing = missing(context)
        return when {
            missing.isEmpty() -> null
            missing.any { it == Manifest.permission.ACCESS_FINE_LOCATION } ->
                "nearby_rationale_location"

            else -> "nearby_rationale_bluetooth"
        }
    }
}
