package edu.uwm.cs595.goup11.frontend.features.chatroom


import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import edu.uwm.cs595.goup11.frontend.features.chatroom.components.PresentationItem

// ChatLobbyScreen.kt

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatLobbyScreen(
    presentations: List<Presentation>,
    onJoinChat: (Presentation) -> Unit,
    onBack: () -> Unit
) {
    val allPresentations = remember { PresentationMockData.presentations() }
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(allPresentations) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allPresentations
        else allPresentations.filter {
            it.name.lowercase().contains(q)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Rooms") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                },
                placeholder = { Text("Search presentations") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filtered, key = { it.id }) { presentation ->
                    PresentationItem(
                        presentation = presentation,
                        onJoinClick = { onJoinChat(presentation) }
                    )
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true)
@Composable
fun ChatLobbyPreview() {
    edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme {
        ChatLobbyScreen(
            presentations = PresentationMockData.presentations(),
            onJoinChat = {},
            onBack = {}
        )
    }
}