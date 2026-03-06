package edu.uwm.cs595.goup11.frontend.features.profile

// EditProfileScreen.kt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import coil3.compose.AsyncImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.R
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    viewModel: UserViewModel,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val userState by viewModel.user.collectAsState()

    var addDialog by remember { mutableStateOf(false) }
    var newInterest by remember { mutableStateOf("") }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { viewModel.updateProfileImage(it.toString()) }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { "" },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Back")
                    }
                }
            )
        }
    ){ innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.Start,
        ){
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ){
                AsyncImage(
                    model = userState.profileImageUri ?: R.drawable.profile_picture,
                    contentDescription = "Profile Picture",
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .clickable {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                )

                Spacer(Modifier.height(20.dp))

                TextField(
                    value = userState.username,
                    onValueChange = { newName ->
                        viewModel.updateName(newName)
                    },
                    placeholder = { Text("Enter your name") },
                    singleLine = true
                )


            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Interests",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            if (userState.interests.isEmpty()) {
                Text(
                    text = "No interests added yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.ui.graphics.Color.Gray,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else{
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ){
                    for (interest in userState.interests) {
                        InterestCard(
                            text = interest,
                            onClick = { viewModel.removeInterest(interest) }
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
            IconButton(onClick = { addDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Save Personal Information")
            }
        }
    }

    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Add New Interest") },
            text = {
                OutlinedTextField(
                    value = newInterest,
                    onValueChange = { newInterest = it },
                    label = { Text("Interest Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newInterest.isNotBlank()) {
                        viewModel.addInterest(newInterest)
                        newInterest = ""
                        addDialog = false
                    }
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { addDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

}

@Composable
private fun InterestCard(text: String, onClick: ()-> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ){
            Text(
                text = text,
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp
                ),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
            Icon(Icons.Default.Close, contentDescription = "Delete")
        }

    }
}

@Preview
@Composable
fun PreviewEditProfileScreen() {
    BLELocalEventManagerTheme {
        EditProfileScreen(
            viewModel = UserViewModel(),
            onBack = {},
            onSave = {}
        )
    }
}

