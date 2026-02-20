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
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventDetailScreen
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventMockData
import edu.uwm.cs595.goup11.frontend.features.home.HomeScreen
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreScreen
import edu.uwm.cs595.goup11.frontend.features.profile.ProfileScreen
import kotlinx.coroutines.launch

@Composable
fun AppNavigation() {
    var currentDestination by remember { mutableStateOf(Destinations.EVENT_DETAIL) }

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

        Destinations.PROFILE ->
            ProfileScreen()
    }
}

/*
* val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()




    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier.padding(15.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Top

                ) {
                    Text("Navigation", modifier = Modifier.padding(16.dp))
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text(text = "Test item") },
                        selected = false,
                        onClick = {

                        }
                    )
                    NavigationDrawerItem(
                        label = { Text(text = "Explore")},
                        selected = false,
                        onClick = onExploreClick

                    )
                }
            }
        },


        ) {


        Scaffold() { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start
            ){
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.TopStart
                ) {

                    FloatingActionButton(
                        onClick = {
                            scope.launch {
                                drawerState.apply {
                                    if (isClosed) open() else close()
                                }
                            } },
                    ){
                        Icon(Icons.Filled.Add, "Navigation")
                    }

                }
            }
        }
    }val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()




    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier.padding(15.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Top

                ) {
                    Text("Navigation", modifier = Modifier.padding(16.dp))
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text(text = "Test item") },
                        selected = false,
                        onClick = {

                        }
                    )
                    NavigationDrawerItem(
                        label = { Text(text = "Explore")},
                        selected = false,
                        onClick = onExploreClick

                    )
                }
            }
        },


        ) {


        Scaffold() { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start
            ){
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.TopStart
                ) {

                    FloatingActionButton(
                        onClick = {
                            scope.launch {
                                drawerState.apply {
                                    if (isClosed) open() else close()
                                }
                            } },
                    ){
                        Icon(Icons.Filled.Add, "Navigation")
                    }

                }
            }
        }
    }
    * */