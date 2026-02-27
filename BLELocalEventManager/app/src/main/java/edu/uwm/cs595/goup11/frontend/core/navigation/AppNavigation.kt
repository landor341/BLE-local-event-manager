// AppNavigation.kt
// Central navigation controller for the app.
// Responsible for switching between high-level screens.

package edu.uwm.cs595.goup11.frontend.core.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHost
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import edu.uwm.cs595.goup11.frontend.features.createevent.CreateEventScreen
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventDetailScreen
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventMockData
import edu.uwm.cs595.goup11.frontend.features.home.HomeScreen
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreScreen
import edu.uwm.cs595.goup11.frontend.features.profile.ProfileScreen
import kotlinx.coroutines.launch

@Composable
fun AppNavigation() {
    var currentDestination by remember { mutableStateOf(Destinations.CREATE_EVENT) }

    when (currentDestination) {
        Destinations.HOME ->
            HomeScreen(onExploreClick = {
                currentDestination = Destinations.EXPLORE
            })

        Destinations.EXPLORE ->
            ExploreScreen(onBack = {
                currentDestination = Destinations.HOME
            })

        Destinations.EVENT_DETAIL -> {
            val mockEvent = EventMockData.events().first()
            EventDetailScreen(
                event = mockEvent,
                onBack = { currentDestination = Destinations.EXPLORE }
            )
        }
        Destinations.CREATE_EVENT -> {
            CreateEventScreen(onBack = {currentDestination = Destinations.EVENT_DETAIL})

        }

        Destinations.PROFILE ->
            ProfileScreen()
    }
}
