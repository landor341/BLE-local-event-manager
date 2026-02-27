package edu.uwm.cs595.goup11.frontend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.uwm.cs595.goup11.frontend.core.navigation.AppNavigation
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        edu.uwm.cs595.goup11.frontend.core.AppContainer.init(this)

        setContent {
            BLELocalEventManagerTheme {
                AppNavigation()
            }
        }
    }
}
