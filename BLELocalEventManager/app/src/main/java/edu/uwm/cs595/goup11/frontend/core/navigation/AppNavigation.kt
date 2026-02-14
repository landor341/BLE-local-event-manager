// AppNavigation.kt
// Central navigation controller for the app.
// Responsible for switching between high-level screens.

package edu.uwm.cs595.goup11.frontend.core.navigation

import androidx.compose.runtime.*
import edu.uwm.cs595.goup11.frontend.features.home.HomeScreen
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreScreen
import edu.uwm.cs595.goup11.frontend.features.profile.ProfileScreen

@Composable
fun AppNavigation() {
    var currentDestination by remember { mutableStateOf(Destinations.HOME) }

    when (currentDestination) {
        Destinations.HOME ->
            HomeScreen(onExploreClick = {
                currentDestination = Destinations.EXPLORE
            })

        Destinations.EXPLORE ->
            ExploreScreen(onBack = {
                currentDestination = Destinations.HOME
            })

        Destinations.PROFILE ->
            ProfileScreen()
    }
}
