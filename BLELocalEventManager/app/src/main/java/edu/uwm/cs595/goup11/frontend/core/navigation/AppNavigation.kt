// AppNavigation.kt
// Central navigation controller for the app.
// Responsible for switching between high-level screens.

package edu.uwm.cs595.goup11.frontend.core.navigation

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import edu.uwm.cs595.goup11.frontend.features.home.HomeScreen
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreScreen
import androidx.navigation.compose.composable
import androidx.navigation.compose.NavHost
import edu.uwm.cs595.goup11.frontend.core.AppContainer
import edu.uwm.cs595.goup11.frontend.features.chat.ChatScreen
import edu.uwm.cs595.goup11.frontend.features.chat.ChatViewModel
import edu.uwm.cs595.goup11.frontend.features.createevent.CreateEventScreen
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventDetailScreen
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventMockData
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreViewModel
import edu.uwm.cs595.goup11.frontend.features.inbox.InboxScreen
import edu.uwm.cs595.goup11.frontend.features.profile.EditProfileScreen
import edu.uwm.cs595.goup11.frontend.features.profile.ProfileScreen
import edu.uwm.cs595.goup11.frontend.features.profile.UserViewModel
import edu.uwm.cs595.goup11.frontend.features.inbox.InboxViewModel
import kotlinx.coroutines.launch

//
//@Composable
//fun AppNavigation() {
//    var currentDestination by remember { mutableStateOf(Destinations.CREATE_EVENT) }
//
//    when (currentDestination) {
//        Destinations.HOME ->
//            HomeScreen(onExploreClick = {
//                currentDestination = Destinations.EXPLORE
//            })
//
//        Destinations.EXPLORE ->
//            ExploreScreen(onBack = {
//                currentDestination = Destinations.HOME
//            })
//
//        Destinations.EVENT_DETAIL -> {
//            val mockEvent = EventMockData.events().first()
//            EventDetailScreen(
//                event = mockEvent,
//                onBack = { currentDestination = Destinations.EXPLORE }
//            )
//        }
//        Destinations.CREATE_EVENT -> {
//            CreateEventScreen(onBack = {currentDestination = Destinations.EVENT_DETAIL})
//
//        }
//
//        Destinations.PROFILE ->
//            ProfileScreen()
//    }
//}

/*
how to add new pages to navigation
    1. add a new serializable object within the SealedDestinations class in Destinations.kt
    2. inside the appNavigation() function add a composable with a function call to the desired
        page, here is where you may pass any needed arguments to the page
    3. use the drawer item or create a NavigationDrawerItem with a label and a function call
        to your desired page
 */


@Composable
fun drawerItem(navigate: () -> Unit, navLabel: String){
    NavigationDrawerItem(
        label = { Text(navLabel) },
        selected = false,
        onClick = navigate ,
        )
}



@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun navDrawer(
    navController: NavController,
    content: @Composable (PaddingValues) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    //add new drawer items here so they can appear in the navigation sidebar
                    drawerItem(
                        { navController.navigate(SealedDestinations.HOME.route) },
                        "Home"
                    )
                    drawerItem(
                            { navController.navigate(SealedDestinations.INBOX.route) },
                    "Inbox"
                    )
                    drawerItem(
                        { navController.navigate(SealedDestinations.EXPLORE.route) },
                        "Explore"
                    )
                    drawerItem(
                        {navController.navigate(SealedDestinations.CREATE_EVENT.route)},
                        "Create Event"
                    )
                    drawerItem(
                        {navController.navigate(SealedDestinations.PROFILE.route)},
                        "Profile"
                    )

                }
            }
        },
        drawerState = drawerState
    ) {
        Scaffold(

        ) { innerPadding ->
            content(innerPadding)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (drawerState.isClosed) {
                                drawerState.open()
                            } else {
                                drawerState.close()
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd),

                    containerColor = Color.Transparent,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Menu",
                            modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}



@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AppNavigation(){
    val navController = rememberNavController()
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var selectedEventName by remember { mutableStateOf("") }
    val meshGateway = AppContainer.meshGateway
    val exploreVm = remember { ExploreViewModel(meshGateway) }
    val userVm = remember { UserViewModel() }
    val inboxVm = remember { InboxViewModel(meshGateway) }

    navDrawer(navController = navController) {
        padding ->
        NavHost(
            navController = navController,
            startDestination = SealedDestinations.HOME.route,
        ) {
            composable(SealedDestinations.HOME.route) {
                HomeScreen(
                    onProfileClick = { navController.navigate(SealedDestinations.PROFILE.route) },
                    onExploreClick = { navController.navigate(SealedDestinations.EXPLORE.route) },
                    mesh = meshGateway
                )
            }

            composable(SealedDestinations.EXPLORE.route) {
                ExploreScreen(
                    onBack = { navController.popBackStack() },
                    onEventClick = { sessionId ->
                        selectedSessionId = sessionId
                        navController.navigate(SealedDestinations.EVENT_DETAIL.route)
                    },
                    viewModel = exploreVm,
                    mesh = meshGateway
                )
            }

            val mockEvent = EventMockData.events().first()
            composable(SealedDestinations.EVENT_DETAIL.route) {
                val sessionId = selectedSessionId ?: "unknown"
                EventDetailScreen(
                    sessionId = sessionId,
                    onBack = {navController.popBackStack()},
                    onOpenChat = {navController.navigate(SealedDestinations.CHAT.route)},
                )}

            composable(SealedDestinations.CREATE_EVENT.route) {
                CreateEventScreen( onBack = {navController.popBackStack()})
            }

            composable(SealedDestinations.PROFILE.route) {
                ProfileScreen(
                    viewModel = userVm,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(SealedDestinations.EDIT_PROFILE.route) }
                )
            }

            composable(SealedDestinations.EDIT_PROFILE.route) {
                EditProfileScreen(
                    viewModel = userVm,
                    onBack = { navController.popBackStack() },
                    onSave = { navController.popBackStack() }
                )
            }

            composable(SealedDestinations.CHAT.route) {
                val chatVm = remember { ChatViewModel(meshGateway) }
                ChatScreen(
                    viewModel = chatVm,
                    onBack = { navController.popBackStack() },
                    eventName = selectedEventName
                )
            }

            composable(SealedDestinations.INBOX.route) {
                InboxScreen(
                    viewModel = inboxVm,
                    onBack = { navController.popBackStack() },
                    onNavigateToChat = { peerId ->
                        navController.navigate("${SealedDestinations.CHAT.route}/$peerId")
                    }
                )
            }

        }
    }

}












