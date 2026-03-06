package edu.uwm.cs595.goup11.frontend.features.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.frontend.core.mesh.ChatMessage

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    var input by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
//            TopAppBar(
//                title = { Text("Event Chat") }
//            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(16.dp)
        ) {

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    ChatBubble(msg)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") }
                )

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    }
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val alignment = if (message.isMine) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Card {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}