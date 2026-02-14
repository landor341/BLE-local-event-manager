package edu.uwm.cs595.goup11.frontend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.uwm.cs595.goup11.frontend.core.navigation.AppNavigation
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme

/**
 * MainActivity
 *
 * Entry point of the Android application.
 * This activity is intentionally minimal.
 *
 * Responsibilities:
 * - Sets up the Compose content
 * - Applies global app theme
 * - Delegates navigation to AppNavigation
 *
 * All navigation logic, screen logic, and state handling
 * must live outside of this file.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            BLELocalEventManagerTheme {
                AppNavigation()
            }
        }
    }
}
