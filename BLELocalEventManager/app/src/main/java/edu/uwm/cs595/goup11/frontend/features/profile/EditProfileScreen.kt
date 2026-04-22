package edu.uwm.cs595.goup11.frontend.features.profile

import android.content.Context
import android.util.Xml
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import edu.uwm.cs595.goup11.R
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme
import kotlinx.io.IOException
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.StringWriter

fun writeProfileData(userName: String, interests: List<String>, context: Context) {
    val fileName = "profile_data.xml"
    val file = File(context.filesDir, fileName)

    try {
        val serializer: XmlSerializer = Xml.newSerializer()
        val writer = StringWriter()

        serializer.setOutput(writer)
        serializer.startDocument("UTF-8", true)

        serializer.startTag("", "user")

        serializer.startTag("", "name")
        serializer.text(userName)
        serializer.endTag("", "name")

        serializer.startTag("", "interests")
        for (interest in interests) {
            serializer.startTag("", "interest")
            serializer.text(interest)
            serializer.endTag("", "interest")
        }
        serializer.endTag("", "interests")

        serializer.endTag("", "user")
        serializer.endDocument()

        file.writeText(writer.toString())
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    viewModel: UserViewModel,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val userState by viewModel.user.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val saved = retrieveUserData(context)
        if (saved != null) {
            if (userState.username.isBlank()) {
                viewModel.updateName(saved.userName)
            }
            if (userState.interests.isEmpty()) {
                saved.interests.forEach { viewModel.addInterest(it) }
            }
        }
    }

    var addDialog by remember { mutableStateOf(false) }
    var newInterest by remember { mutableStateOf("") }
    var showNameError by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { viewModel.updateProfileImage(it.toString()) }
        }
    )

    val headerBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.background
        )
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Edit Profile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Button(
                        onClick = {
                            if (userState.username.trim().isBlank()) {
                                showNameError = true
                                return@Button
                            }

                            val trimmedName = userState.username.trim()

                            writeProfileData(
                                userName = trimmedName,
                                interests = userState.interests,
                                context = context
                            )

                            markProfileSetupComplete(
                                context = context,
                                name = trimmedName,
                                isGuest = trimmedName.equals("Guest", ignoreCase = true)
                            )

                            onSave()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("Save Changes")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(headerBrush)
                .padding(innerPadding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        AsyncImage(
                            model = userState.profileImageUri ?: R.drawable.profile_picture,
                            contentDescription = "Profile Picture",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .clickable {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                        )

                        Surface(
                            modifier = Modifier.clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Box(
                                modifier = Modifier.padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Change photo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Tap photo to update",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = userState.username,
                        onValueChange = {
                            viewModel.updateName(it)
                            if (it.isNotBlank()) showNameError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Display name") },
                        placeholder = { Text("Enter your name") },
                        singleLine = true,
                        isError = showNameError,
                        shape = RoundedCornerShape(18.dp),
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        }
                    )

                    if (showNameError) {
                        Text(
                            text = "Please enter a name before saving.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Interests,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Interests",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        FilledTonalButton(
                            onClick = { addDialog = true },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (userState.interests.isEmpty()) {
                        Text(
                            text = "No interests added yet. Add a few to make your profile feel more personal.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        EditableInterestGrid(
                            interests = userState.interests,
                            onRemove = { interest -> viewModel.removeInterest(interest) }
                        )
                    }
                }
            }
            IconButton(onClick = { addDialog = true },
                modifier = Modifier.semantics{contentDescription = "Add interests"}) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    writeProfileData(userState.username, userState.interests, context)
                    onSave()
                },
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
            onDismissRequest = {
                addDialog = false
                newInterest = ""
            },
            title = {
                Text(
                    text = "Add Interest",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                OutlinedTextField(
                    modifier = Modifier.semantics{contentDescription = "Add an interest"},
                    value = newInterest,
                    onValueChange = { newInterest = it },
                    label = { Text("Interest") },
                    placeholder = { Text("Photography, Tech, Music...") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newInterest.trim()
                        if (trimmed.isNotBlank() && !userState.interests.any { it.equals(trimmed, ignoreCase = true) }) {
                            viewModel.addInterest(trimmed)
                        }
                        newInterest = ""
                        addDialog = false
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        addDialog = false
                        newInterest = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EditableInterestGrid(
    interests: List<String>,
    onRemove: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        interests.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { interest ->
                    Box(modifier = Modifier.weight(1f)) {
                        EditableInterestChip(
                            text = interest,
                            onRemove = { onRemove(interest) }
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EditableInterestChip(
    text: String,
    onRemove: () -> Unit
) {
    AssistChip(
        onClick = onRemove,
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove interest",
                modifier = Modifier.size(18.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            labelColor = MaterialTheme.colorScheme.primary,
            trailingIconContentColor = MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(999.dp)
    )
}