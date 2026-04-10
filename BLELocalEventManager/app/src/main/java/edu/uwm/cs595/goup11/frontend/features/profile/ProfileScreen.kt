package edu.uwm.cs595.goup11.frontend.features.profile

import android.content.Context
import android.util.Xml
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import edu.uwm.cs595.goup11.R
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme
import org.xmlpull.v1.XmlPullParser
import java.io.File

class User(val userName: String, val interests: List<String>)

fun retrieveUserData(context: Context): User? {
    val fileName = "profile_data.xml"
    val file = File(context.filesDir, fileName)

    if (!file.exists()) return null

    var userName = ""
    val interests = mutableListOf<String>()

    try {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(file.inputStream(), null)

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> userName = parser.nextText()
                    "interest" -> interests.add(parser.nextText())
                }
            }
            eventType = parser.next()
        }

        return User(userName, interests)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: UserViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    val userState by viewModel.user.collectAsState()
    val context = LocalContext.current
    val persistedUser = retrieveUserData(context)
    val savedDisplayName = getSavedDisplayName(context)
    val savedIsGuest = isGuestProfile(context)

    LaunchedEffect(savedDisplayName, savedIsGuest, persistedUser) {
        val resolvedName = when {
            savedIsGuest -> "Guest"
            savedDisplayName.isNotBlank() -> savedDisplayName
            persistedUser?.userName?.isNotBlank() == true -> persistedUser.userName
            else -> ""
        }

        if (resolvedName.isNotBlank() && userState.username != resolvedName) {
            viewModel.updateName(resolvedName)
        }

        if (userState.interests.isEmpty() && persistedUser?.interests?.isNotEmpty() == true) {
            persistedUser.interests.forEach { viewModel.addInterest(it) }
        }
    }

    val displayName = when {
        userState.username.isNotBlank() -> userState.username
        savedIsGuest -> "Guest"
        savedDisplayName.isNotBlank() -> savedDisplayName
        persistedUser?.userName?.isNotBlank() == true -> persistedUser.userName
        else -> "Guest"
    }

    val interests = when {
        userState.interests.isNotEmpty() -> userState.interests
        persistedUser != null -> persistedUser.interests
        else -> emptyList()
    }

    val profileImageModel = userState.profileImageUri ?: R.drawable.profile_picture

    val headerBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.background
        )
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Profile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(headerBrush)
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        AsyncImage(
                            model = profileImageModel,
                            contentDescription = "Profile picture",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                        )

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            tonalElevation = 2.dp,
                            shadowElevation = 0.dp
                        ) {
                            Box(
                                modifier = Modifier.padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = if (interests.isEmpty()) {
                                "No interests added yet"
                            } else {
                                "${interests.size} interest${if (interests.size == 1) "" else "s"} added"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = onEdit,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Edit profile")
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
                    Text(
                        text = "Interests",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (interests.isEmpty()) {
                        Text(
                            text = "Add a few interests to personalize your profile and make it feel more complete.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        InterestGrid(interests = interests)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(
    label: String,
    value: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun InterestGrid(interests: List<String>) {
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
                        InterestChip(text = interest)
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
private fun InterestChip(text: String) {
    AssistChip(
        onClick = { },
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            labelColor = MaterialTheme.colorScheme.primary
        ),
        shape = RoundedCornerShape(999.dp)
    )
}

@Preview
@Composable
fun PreviewProfileScreen() {
    val previewVm = androidx.compose.runtime.remember { UserViewModel() }

    BLELocalEventManagerTheme {
        ProfileScreen(
            viewModel = previewVm,
            onBack = {},
            onEdit = {}
        )
    }
}