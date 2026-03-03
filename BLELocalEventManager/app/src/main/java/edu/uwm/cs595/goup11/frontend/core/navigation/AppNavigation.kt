// AppNavigation.kt
// Central navigation controller for the app.
// Responsible for switching between high-level screens.

package edu.uwm.cs595.goup11.frontend.core.navigation

import androidx.compose.runtime.*
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventDetailScreen
import edu.uwm.cs595.goup11.frontend.features.home.HomeScreen
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreScreen
import edu.uwm.cs595.goup11.frontend.features.profile.ProfileScreen
import edu.uwm.cs595.goup11.frontend.core.AppContainer
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreViewModel
import androidx.compose.runtime.remember
import edu.uwm.cs595.goup11.frontend.features.chat.ChatScreen
import edu.uwm.cs595.goup11.frontend.features.chat.ChatViewModel
@Composable
fun AppNavigation() {
    var currentDestination by remember { mutableStateOf(Destinations.HOME) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var selectedEventName by remember { mutableStateOf("") }
    val exploreVm = remember { ExploreViewModel(AppContainer.meshGateway) }

    when (currentDestination) {
        Destinations.HOME ->
            HomeScreen(
                onExploreClick = { currentDestination = Destinations.EXPLORE },
                mesh = AppContainer.meshGateway
            )

        Destinations.EXPLORE ->
            ExploreScreen(
                onBack = { currentDestination = Destinations.HOME },
                onEventClick = { sessionId ->
                    selectedSessionId = sessionId
                    currentDestination = Destinations.EVENT_DETAIL
                },
                viewModel = exploreVm,
                mesh = AppContainer.meshGateway
            )

        Destinations.EVENT_DETAIL -> {
            val sessionId = selectedSessionId ?: "unknown"
            EventDetailScreen(
                sessionId = sessionId,
                onBack = { currentDestination = Destinations.EXPLORE },
                onOpenChat = { name ->
                    selectedEventName = name
                    currentDestination = Destinations.CHAT }

            )
        }
        Destinations.CHAT -> {
            val chatVm = remember { ChatViewModel(AppContainer.meshGateway) }
            ChatScreen(
                viewModel = chatVm,
                eventName = selectedEventName,
                onBack = { currentDestination = Destinations.EVENT_DETAIL }
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