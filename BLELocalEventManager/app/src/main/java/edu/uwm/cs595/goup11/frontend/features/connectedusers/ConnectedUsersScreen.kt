package edu.uwm.cs595.goup11.frontend.features.connectedusers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.frontend.domain.models.User

enum class PeerStatus {
    CONNECTED,
    NEARBY,
    OUT_OF_RANGE
}

data class ConnectedUserUi(
    val user: User,
    val status: PeerStatus
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedUsersScreen(
    sessionId: String,
    users: List<ConnectedUserUi>,
    onBack: () -> Unit,
    onUserClick: (User) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connected Users") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (users.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No users connected to this event yet.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Text(
                    text = "Session: $sessionId",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(users) { item ->
                        ConnectedUserRow(
                            user = item.user,
                            status = item.status,
                            onClick = { onUserClick(item.user) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedUserRow(
    user: User,
    status: PeerStatus,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.username.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = user.username,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.size(2.dp))

                Text(
                    text = user.role.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { },
                    label = {
                        Text(user.role.displayName())
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (user.role == UserRole.ADMIN) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                )

                StatusPill(status = status)
            }
        }
    }
}

@Composable
private fun StatusPill(status: PeerStatus) {
    val text = when (status) {
        PeerStatus.CONNECTED -> "Connected"
        PeerStatus.NEARBY -> "Nearby"
        PeerStatus.OUT_OF_RANGE -> "Out of range"
    }

    val color = when (status) {
        PeerStatus.CONNECTED -> Color(0xFF4CAF50)
        PeerStatus.NEARBY -> Color(0xFF2196F3)
        PeerStatus.OUT_OF_RANGE -> Color.Gray
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape)
        )

        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun UserRole.displayName(): String {
    return when (this) {
        UserRole.ADMIN -> "Admin"
        UserRole.ATTENDEE -> "Attendee"
        else -> this.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectedUsersPreview() {
    MaterialTheme {
        ConnectedUsersScreen(
            sessionId = "event-123",
            users = listOf(
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
                )
            ),
            onBack = {},
            onUserClick = {}
        )
    }
}