package edu.uwm.cs595.goup11.frontend.dev

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Handles runtime permission requests for Google Nearby Connections.
 *
 * Nearby Connections requires different permissions depending on Android version:
 *
 *   Android 12+ (API 31+):
 *     BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT
 *     ACCESS_FINE_LOCATION (still required alongside Bluetooth perms)
 *
 *   Android 9–11 (API 28–30):
 *     ACCESS_FINE_LOCATION
 *     ACCESS_WIFI_STATE, CHANGE_WIFI_STATE  (declared in manifest, auto-granted)
 *     BLUETOOTH, BLUETOOTH_ADMIN            (declared in manifest, auto-granted)
 *
 *   Below Android 9 (API < 28):
 *     ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION
 *
 * Usage — create in Activity.onCreate(), then call [request] when needed:
 *
 *   private val nearbyPerms = NearbyPermissions(this)
 *
 *   // In your Compose button or wherever you trigger network actions:
 *   nearbyPerms.request { granted ->
 *       if (granted) createNetwork(...)
 *       else showError("Permissions required")
 *   }
 */
class NearbyPermissions(activity: ComponentActivity) {

    private var onResult: ((Boolean) -> Unit)? = null

    private val required: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // All required permissions must be granted
        val allGranted = required.all { results[it] == true }
        onResult?.invoke(allGranted)
        onResult = null
    }

    /**
     * Check if all required permissions are already granted.
     */
    fun allGranted(context: Context): Boolean =
        required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * If all permissions are already granted, [onGranted] is called immediately.
     * Otherwise the system dialog is shown and [onGranted] is called with the result.
     */
    fun request(context: Context, onGranted: (Boolean) -> Unit) {
        if (allGranted(context)) {
            onGranted(true)
            return
        }
        onResult = onGranted
        launcher.launch(required)
    }
}