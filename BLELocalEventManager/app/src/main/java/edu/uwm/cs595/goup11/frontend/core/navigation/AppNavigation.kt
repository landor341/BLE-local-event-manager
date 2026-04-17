package edu.uwm.cs595.goup11.frontend.core.navigation

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import edu.uwm.cs595.goup11.frontend.core.AppContainer
import edu.uwm.cs595.goup11.frontend.features.chat.ChatScreen
import edu.uwm.cs595.goup11.frontend.features.chat.ChatViewModel
import edu.uwm.cs595.goup11.frontend.features.connectedusers.ConnectedUsersScreen
import edu.uwm.cs595.goup11.frontend.features.connectedusers.ConnectedUsersViewModel
import edu.uwm.cs595.goup11.frontend.features.createevent.CreateEventScreen
import edu.uwm.cs595.goup11.frontend.features.createevent.CreateEventViewModel
import edu.uwm.cs595.goup11.frontend.features.createpresentation.CreatePresentationScreen
import edu.uwm.cs595.goup11.frontend.features.createpresentation.CreatePresentationViewModel
import edu.uwm.cs595.goup11.frontend.features.developer.DeveloperScreen
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventDetailScreen
import edu.uwm.cs595.goup11.frontend.features.eventdetail.EventDetailViewModel
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreScreen
import edu.uwm.cs595.goup11.frontend.features.explore.ExploreViewModel
import edu.uwm.cs595.goup11.frontend.features.home.HomeScreen
import edu.uwm.cs595.goup11.frontend.features.inbox.InboxScreen
import edu.uwm.cs595.goup11.frontend.features.inbox.InboxViewModel
import edu.uwm.cs595.goup11.frontend.features.profile.EditProfileScreen
import edu.uwm.cs595.goup11.frontend.features.profile.ProfileScreen
import edu.uwm.cs595.goup11.frontend.features.profile.ProfileSetupScreen
import edu.uwm.cs595.goup11.frontend.features.profile.UserViewModel
import edu.uwm.cs595.goup11.frontend.features.tutorial.TutorialScreen
import edu.uwm.cs595.goup11.frontend.features.tutorial.introTutorialScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

private const val PROFILE_SETUP_ROUTE = "profile_setup"

private data class DrawerDestination(
    val label: String,
    val route: String,
    val icon: ImageVector
)

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(text = label, style = MaterialTheme.typography.bodyLarge) },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
        },
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary
        )
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDrawer(
    navController: NavHostController,
    showDrawerButton: Boolean,
    displayName: String,
    content: @Composable (PaddingValues) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val primaryItems = listOf(
        DrawerDestination("Home", SealedDestinations.HOME.route, Icons.Default.Home),
        DrawerDestination("Inbox", SealedDestinations.INBOX.route, Icons.Default.ChatBubbleOutline),
        DrawerDestination("Explore", SealedDestinations.EXPLORE.route, Icons.Default.Explore),
        DrawerDestination("Create Event", SealedDestinations.CREATE_EVENT.route, Icons.Default.AddCircleOutline),
        DrawerDestination("Profile", SealedDestinations.PROFILE.route, Icons.Default.Person)
    )

    val secondaryItems = listOf(
        DrawerDestination("Help", SealedDestinations.TUTORIAL_INTRO.route, Icons.Default.HelpOutline),
        DrawerDestination("Developer", SealedDestinations.DEVELOPER.route, Icons.Default.BugReport)
    )

    fun isRouteSelected(itemRoute: String): Boolean {
        return when (itemRoute) {
            SealedDestinations.PROFILE.route ->
                currentRoute == SealedDestinations.PROFILE.route ||
                        currentRoute == SealedDestinations.EDIT_PROFILE.route

            SealedDestinations.TUTORIAL_INTRO.route ->
                currentRoute == SealedDestinations.TUTORIAL_INTRO.route ||
                        currentRoute == SealedDestinations.TUTORIAL.route

            else -> currentRoute == itemRoute
        }
    }

    fun navigateToTopLevel(route: String) {
        scope.launch { drawerState.close() }
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = showDrawerButton,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                            MaterialTheme.colorScheme.primaryContainer
                                        )
                                    )
                                )
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                modifier = Modifier.size(52.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "BLE Local Event Manager",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (displayName.isBlank()) "Welcome" else displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.size(4.dp))

                    primaryItems.forEach { item ->
                        DrawerItem(
                            label = item.label,
                            icon = item.icon,
                            selected = isRouteSelected(item.route),
                            onClick = { navigateToTopLevel(item.route) }
                        )
                    }

                    Spacer(modifier = Modifier.size(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.size(8.dp))

                    secondaryItems.forEach { item ->
                        DrawerItem(
                            label = item.label,
                            icon = item.icon,
                            selected = isRouteSelected(item.route),
                            onClick = { navigateToTopLevel(item.route) }
                        )
                    }
                }
            }
        }
    ) {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                content(PaddingValues())

                if (showDrawerButton) {
                    Surface(
                        onClick = {
                            scope.launch {
                                if (drawerState.isClosed) drawerState.open() else drawerState.close()
                            }
                        },
                        shape = CircleShape,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 16.dp, top = 12.dp)
                    ) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                }
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open menu",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }

    val meshGateway = AppContainer.meshGateway
    val exploreVm = remember { ExploreViewModel(meshGateway) }
    val userVm = remember { UserViewModel() }
    val inboxVm = remember { InboxViewModel(meshGateway) }
    val eventDetailVm = remember { EventDetailViewModel(meshGateway) }
    val createEventVm = remember { CreateEventViewModel(meshGateway) }
    val createPresentationVm = remember { CreatePresentationViewModel(meshGateway) }

    val userState by userVm.user.collectAsState()
    val drawerDisplayName = userState.username.ifBlank { "Guest" }

    val connectedUsersVm = remember { ConnectedUsersViewModel(meshGateway) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route

    val showDrawerButton = currentDestination?.hierarchy?.any { destination ->
        destination.route in setOf(
            SealedDestinations.HOME.route,
            SealedDestinations.INBOX.route,
            SealedDestinations.EXPLORE.route,
            SealedDestinations.CREATE_EVENT.route,
            SealedDestinations.PROFILE.route,
            SealedDestinations.EDIT_PROFILE.route,
            SealedDestinations.TUTORIAL_INTRO.route,
            SealedDestinations.TUTORIAL.route,
            SealedDestinations.DEVELOPER.route
        )
    } == true && currentRoute != PROFILE_SETUP_ROUTE

    LaunchedEffect(userState.username) {
        if (userState.username.isNotBlank()) {
            meshGateway.setDisplayName(userState.username)
        }
    }

    AppDrawer(
        navController = navController,
        showDrawerButton = showDrawerButton,
        displayName = drawerDisplayName
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = PROFILE_SETUP_ROUTE,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(PROFILE_SETUP_ROUTE) {
                ProfileSetupScreen(
                    viewModel = userVm,
                    onContinue = {
                        navController.navigate(SealedDestinations.HOME.route) {
                            popUpTo(PROFILE_SETUP_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(SealedDestinations.HOME.route) {
                HomeScreen(
                    onProfileClick = {
                        navController.navigate(SealedDestinations.PROFILE.route) {
                            launchSingleTop = true
                        }
                    },
                    onExploreClick = {
                        navController.navigate(SealedDestinations.EXPLORE.route) {
                            launchSingleTop = true
                        }
                    },
                    onHostClick = {
                        navController.navigate(SealedDestinations.CREATE_EVENT.route) {
                            launchSingleTop = true
                        }
                    },
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
                    viewModel = exploreVm
                )
            }

            composable(SealedDestinations.EVENT_DETAIL.route) {
                val sessionId = selectedSessionId

                if (sessionId == null) {
                    navController.popBackStack(SealedDestinations.HOME.route, false)
                    return@composable
                }

                EventDetailScreen(
                    sessionId = sessionId,
                    viewModel = eventDetailVm,
                    onBack = { navController.popBackStack() },
                    onOpenChat = {
                        val encodedName = URLEncoder.encode(
                            "Event Chat",
                            StandardCharsets.UTF_8.toString()
                        )
                        navController.navigate("${SealedDestinations.CHAT.route}/router/$encodedName")
                    },
                    onViewConnectedUsers = {
                        navController.navigate(SealedDestinations.CONNECTED_USERS.route)
                    },
                    onLeaveSuccess = {
                        selectedSessionId = null
                        navController.popBackStack(SealedDestinations.HOME.route, false)
                    }
                )
            }

            composable(SealedDestinations.CONNECTED_USERS.route) {
                ConnectedUsersScreen(
                    sessionId = selectedSessionId ?: "unknown",
                    viewModel = connectedUsersVm,
                    onBack = { navController.popBackStack() },
                    onUserClick = { user ->
                        val encodedUserName = URLEncoder.encode(
                            user.username,
                            StandardCharsets.UTF_8.toString()
                        )
                        navController.navigate(
                            "${SealedDestinations.CHAT.route}/${user.id}/$encodedUserName"
                        )
                    }
                )
            }

            composable(SealedDestinations.CREATE_EVENT.route) {
                CreateEventScreen(
                    viewModel = createEventVm,
                    onBack = { navController.popBackStack() },
                    onHostingStarted = { hostedSessionId ->
                        selectedSessionId = hostedSessionId
                        navController.navigate(SealedDestinations.EVENT_DETAIL.route)
                    },
                    onNavigateToCreatePresentation = {
                        navController.navigate(SealedDestinations.CREATE_PRESENTATION.route)
                    }
                )
            }

            composable(SealedDestinations.CREATE_PRESENTATION.route) {
                CreatePresentationScreen(
                    viewModel = createPresentationVm,
                    onBack = { navController.popBackStack() },
                    onSuccess = {
                        navController.popBackStack()
                    }
                )
            }

            composable(SealedDestinations.PROFILE.route) {
                ProfileScreen(
                    viewModel = userVm,
                    onBack = { navController.popBackStack() },
                    onEdit = {
                        navController.navigate(SealedDestinations.EDIT_PROFILE.route)
                    }
                )
            }

            composable(SealedDestinations.EDIT_PROFILE.route) {
                EditProfileScreen(
                    viewModel = userVm,
                    onBack = { navController.popBackStack() },
                    onSave = { navController.popBackStack() }
                )
            }

            composable("${SealedDestinations.CHAT.route}/{peerId}/{userName}") { entry ->
                val peerId = entry.arguments?.getString("peerId") ?: "router"
                val userName = entry.arguments?.getString("userName")?.let {
                    URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
                } ?: "Unknown User"

                val chatVm = remember(peerId) { ChatViewModel(meshGateway, peerId) }

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
                    onNoClick = { navController.navigate(SealedDestinations.HOME.route) }
                )
            }

            composable(SealedDestinations.TUTORIAL.route) {
                TutorialScreen(
                    onNoClick = { navController.navigate(SealedDestinations.HOME.route) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(SealedDestinations.INBOX.route) {
                InboxScreen(
                    viewModel = inboxVm,
                    onBack = { navController.popBackStack() },
                    onNavigateToChat = { peerId, userName ->
                        val encodedUserName = URLEncoder.encode(
                            userName,
                            StandardCharsets.UTF_8.toString()
                        )
                        navController.navigate(
                            "${SealedDestinations.CHAT.route}/$peerId/$encodedUserName"
                        )
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