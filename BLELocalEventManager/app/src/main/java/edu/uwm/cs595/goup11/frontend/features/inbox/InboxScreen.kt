package edu.uwm.cs595.goup11.frontend.features.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.domain.models.ChatPeer
import edu.uwm.cs595.goup11.frontend.domain.models.User

@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onBack: () -> Unit,
    onNavigateToChat: (String, String) -> Unit
) {
    val peers by viewModel.chatPeers.collectAsState()

    InboxContent(
        activePeers = peers,
        onBack = onBack,
        onNavigateToChat = onNavigateToChat
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxContent(
    activePeers: List<ChatPeer>,
    onBack: () -> Unit,
    onNavigateToChat: (String, String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inbox") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (activePeers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No active P2P connections nearby.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                items(activePeers) { peer ->
                    ListItem(
                        headlineContent = { Text(peer.user.username) },
                        supportingContent = {
                            Text(
                                text = peer.lastMessage.ifEmpty { "No messages yet" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = {
                            Surface(
                                shape = CircleShape,
                                color = if (peer.isOnline) Color(0xFF4CAF50) else Color.Gray,
                                modifier = Modifier.size(12.dp)
                            ) {}
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    peer.timestamp,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Icon(
                                    imageVector = Icons.Default.SignalCellularAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (peer.rssi > -70) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Gray
                                    }
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            onNavigateToChat(peer.user.id, peer.user.username)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Connected State")
@Composable
fun PreviewInboxConnected() {
    MaterialTheme {
        InboxContent(
            activePeers = listOf(
                ChatPeer(
                    User(id = "1", username = "user1"),
                    "Hello",
                    "12:01 PM",
                    rssi = -40
                ),
                ChatPeer(
                    User(id = "2", username = "user2"),
                    "",
                    "11:50 AM",
                    isOnline = false
                )
            ),
            onBack = {},
            onNavigateToChat = { _, _ -> }
        )
    }
}

@Preview(showBackground = true, name = "Empty State")
@Composable
fun PreviewInboxIdle() {
    MaterialTheme {
        InboxContent(
            activePeers = emptyList(),
            onBack = {},
            onNavigateToChat = { _, _ -> }
        )
    }
}