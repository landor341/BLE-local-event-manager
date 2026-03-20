package edu.uwm.cs595.goup11.frontend

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import edu.uwm.cs595.goup11.frontend.core.navigation.AppNavigation
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme
import edu.uwm.cs595.goup11.frontend.dev.NearbyPermissions

/**
 * MainActivity — Application Entry Point (UI Shell Only)
 *
 *
 * PURPOSE:
 * This activity is intentionally minimal and must remain minimal.
 * It acts only as a container for Jetpack Compose content.
 *
 * RESPONSIBILITIES:
 * - Initialize Compose
 * - Apply global theme
 * - Attach AppNavigation()
 *
 * STRICT RULES:
 * - Do NOT put business logic here
 * - Do NOT start Bluetooth/networking here
 * - Do NOT initialize backend Client/Network here
 * - Do NOT hold application state here
 *
 * All logic must live in:
 * - ViewModels (application logic)
 * - MeshGateway (backend bridge)
 * - backend/network (mesh implementation)
 *
 * If you need global initialization logic,
 * create a dedicated AppInitializer or use Application class.
 *
 * This file should remain under ~60 lines.
 */
class MainActivity : ComponentActivity() {

    private val nearbyPermissions = NearbyPermissions(this)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        edu.uwm.cs595.goup11.frontend.core.AppContainer.init(this)

        /*// Request necessary permissions for Nearby Connections
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        // Location is generally required for Nearby Connections to work reliably 
        // across all versions and strategies.
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }*/

        nearbyPermissions.request(this) { granted ->
            if (!granted) {
                //TODO: Error message
            }
        }

        setContent {
            BLELocalEventManagerTheme {
                AppNavigation()
            }
        }
    }
}
