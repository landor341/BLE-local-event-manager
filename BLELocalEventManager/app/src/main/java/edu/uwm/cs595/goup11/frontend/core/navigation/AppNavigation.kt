package edu.uwm.cs595.goup11.frontend.core.navigation

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.dev.DevNetworkActivity
import edu.uwm.cs595.goup11.frontend.features.home.HomeScreen
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.frontend.core.AppContainer
import edu.uwm.cs595.goup11.frontend.domain.models.User
import edu.uwm.cs595.goup11.frontend.features.chat.ChatScreen
import edu.uwm.cs595.goup11.frontend.features.chat.ChatViewModel
import edu.uwm.cs595.goup11.frontend.features.connectedusers.ConnectedUserUi
import edu.uwm.cs595.goup11.frontend.features.connectedusers.ConnectedUsersScreen
import edu.uwm.cs595.goup11.frontend.features.connectedusers.PeerStatus
import edu.uwm.cs595.goup11.frontend.features.createevent.CreateEventScreen
import edu.uwm.cs595.goup11.frontend.features.developer.DeveloperScreen
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventDetailScreen
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreScreen
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreViewModel
import edu.uwm.cs595.goup11.frontend.features.home.HomeScreen
import edu.uwm.cs595.goup11.frontend.features.inbox.InboxScreen
import edu.uwm.cs595.goup11.frontend.features.inbox.InboxViewModel
import edu.uwm.cs595.goup11.frontend.features.profile.EditProfileScreen
import edu.uwm.cs595.goup11.frontend.features.profile.ProfileScreen
import edu.uwm.cs595.goup11.frontend.features.profile.UserViewModel
import edu.uwm.cs595.goup11.frontend.features.tutorial.TutorialScreen
import edu.uwm.cs595.goup11.frontend.features.tutorial.introTutorialScreen
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun drawerItem(navigate: () -> Unit, navLabel: String) {
    NavigationDrawerItem(
        label = { Text(navLabel) },
        selected = false,
        onClick = navigate,
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
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
                        { navController.navigate(SealedDestinations.CREATE_EVENT.route) },
                        "Create Event"
                    )
                    drawerItem(
                        { navController.navigate(SealedDestinations.PROFILE.route) },
                        "Profile"
                    )
                    drawerItem(
                        { navController.navigate(SealedDestinations.TUTORIAL_INTRO.route) },
                        "Help"
                    )
                    drawerItem(
                        { navController.navigate(SealedDestinations.DEVELOPER.route) },
                        "Developer"
                    )
                }
            }
        },
        drawerState = drawerState
    ) {
        Scaffold { innerPadding ->
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
                    modifier = Modifier.align(Alignment.TopEnd),
                    containerColor = Color.Transparent,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Menu",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    var selectedSessionId by remember { mutableStateOf<String?>(null) }

    val meshGateway = AppContainer.meshGateway
    val exploreVm = remember { ExploreViewModel(meshGateway) }
    val userVm = remember { UserViewModel() }
    val inboxVm = remember { InboxViewModel(meshGateway) }

    val mockConnectedUsers = remember {
        listOf(
            ConnectedUserUi(
                user = User(id = "1", username = "Matthew", role = UserRole.ADMIN),
                status = PeerStatus.CONNECTED
            ),
            ConnectedUserUi(
                user = User(id = "2", username = "Angelo", role = UserRole.ATTENDEE),
                status = PeerStatus.NEARBY
            ),
            ConnectedUserUi(
                user = User(id = "3", username = "Labib", role = UserRole.ATTENDEE),
                status = PeerStatus.OUT_OF_RANGE
            ),
            ConnectedUserUi(
                user = User(id = "4", username = "Landon", role = UserRole.ATTENDEE),
                status = PeerStatus.NEARBY
            )
        )
    }

    navDrawer(navController = navController) { padding ->
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

            composable(SealedDestinations.EVENT_DETAIL.route) {
                val sessionId = selectedSessionId ?: "unknown"
                EventDetailScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onOpenChat = {},
                    onViewConnectedUsers = {
                        navController.navigate(SealedDestinations.CONNECTED_USERS.route)
                    }
                )
            }

            composable(SealedDestinations.CONNECTED_USERS.route) {
                val sessionId = selectedSessionId ?: "unknown"
                ConnectedUsersScreen(
                    sessionId = sessionId,
                    users = mockConnectedUsers,
                    onBack = { navController.popBackStack() },
                    onUserClick = { user ->
                        navController.navigate("${SealedDestinations.CHAT.route}/${user.id}/${user.username}")
                    }
                )
            }

            composable(SealedDestinations.CREATE_EVENT.route) {
                CreateEventScreen(
                    onBack = { navController.popBackStack() }
                )
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

            composable("${SealedDestinations.CHAT.route}/{peerId}/{userName}") { backStackEntry ->
                val peerId = backStackEntry.arguments?.getString("peerId") ?: "Unknown User"
                val userName = backStackEntry.arguments?.getString("userName") ?: "Unknown User"
                val chatVm = remember { ChatViewModel(meshGateway, peerId) }

                ChatScreen(
                    viewModel = chatVm,
                    onBack = { navController.popBackStack() },
                    peerId = peerId,
                    sender = userName
                )
            }

            composable(SealedDestinations.TUTORIAL_INTRO.route) {
                introTutorialScreen(
                    onYesClick = { navController.navigate(SealedDestinations.TUTORIAL.route) },
                    onNoClick = { navController.navigate(SealedDestinations.HOME.route) },
                )
            }

            composable(SealedDestinations.TUTORIAL.route) {
                TutorialScreen(
                    onNoClick = { navController.navigate(SealedDestinations.HOME.route) },
                    onBack = { navController.navigate(SealedDestinations.HOME.route) },
                )
            }

            composable(SealedDestinations.INBOX.route) {
                InboxScreen(
                    viewModel = inboxVm,
                    onBack = { navController.popBackStack() },
                    onNavigateToChat = { peerId, userName ->
                        navController.navigate("${SealedDestinations.CHAT.route}/$peerId/$userName")
                    }
                )
            }

            composable(SealedDestinations.DEVELOPER.route) {
                DeveloperScreen(
                    mesh = meshGateway,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}