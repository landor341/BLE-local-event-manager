package edu.uwm.cs595.goup11.frontend.features.profile

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val PROFILE_PREFS = "profile_setup_prefs"
private const val KEY_SETUP_COMPLETE = "setup_complete"
private const val KEY_IS_GUEST = "is_guest"
private const val KEY_DISPLAY_NAME = "display_name"

fun markProfileSetupComplete(context: Context, name: String, isGuest: Boolean) {
    context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_SETUP_COMPLETE, true)
        .putBoolean(KEY_IS_GUEST, isGuest)
        .putString(KEY_DISPLAY_NAME, name)
        .apply()
}

fun hasCompletedProfileSetup(context: Context): Boolean {
    return context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_SETUP_COMPLETE, false)
}

fun getSavedDisplayName(context: Context): String {
    return context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_DISPLAY_NAME, "") ?: ""
}

fun isGuestProfile(context: Context): Boolean {
    return context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_IS_GUEST, false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    viewModel: UserViewModel,
    onContinue: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val userState by viewModel.user.collectAsState()

    val hasSetup = remember { hasCompletedProfileSetup(context) }
    val savedName = remember { getSavedDisplayName(context) }
    val savedIsGuest = remember { isGuestProfile(context) }

    val isReturningUser = hasSetup && savedName.isNotBlank()

    var isEditingName by remember { mutableStateOf(!isReturningUser) }
    var name by remember { mutableStateOf(if (savedIsGuest) "" else savedName) }
    var showError by remember { mutableStateOf(false) }

    LaunchedEffect(isReturningUser) {
        if (isReturningUser) {
            isEditingName = false
        }
    }

    LaunchedEffect(savedName, savedIsGuest, userState.username) {
        if (userState.username.isBlank()) {
            val initialName = when {
                savedIsGuest -> "Guest"
                savedName.isNotBlank() -> savedName
                else -> ""
            }

            if (initialName.isNotBlank()) {
                viewModel.updateName(initialName)
            }
        }
    }

    fun continueAsGuest() {
        viewModel.updateName("Guest")
        markProfileSetupComplete(context, "Guest", true)
        onContinue()
    }

    fun continueWithEnteredName() {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            showError = true
            return
        }

        viewModel.updateName(trimmed)
        markProfileSetupComplete(context, trimmed, false)
        onContinue()
    }

    fun continueAsSavedUser() {
        val finalName = if (savedIsGuest) "Guest" else savedName.ifBlank { "Guest" }
        viewModel.updateName(finalName)
        markProfileSetupComplete(context, finalName, savedIsGuest)
        onContinue()
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(88.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isReturningUser) Icons.Default.Person else Icons.Default.PersonOutline,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = if (isReturningUser) {
                        if (savedIsGuest) "Welcome back,\nGuest"
                        else "Welcome back,\n$savedName"
                    } else {
                        "Welcome"
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isReturningUser) {
                        ""
                    } else {
                        "Set a display name to personalize your app experience, or continue as a guest."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(36.dp))

                AnimatedContent(
                    targetState = isReturningUser && !isEditingName,
                    label = "welcome_state"
                ) { showReturningState ->
                    if (showReturningState) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Button(
                                onClick = { continueAsSavedUser() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    text = if (savedIsGuest) {
                                        "Continue as Guest"
                                    } else {
                                        "Continue as $savedName"
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            TextButton(
                                onClick = {
                                    isEditingName = true
                                    name = if (savedIsGuest) "" else savedName
                                    showError = false
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Edit name")
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = {
                                    name = it
                                    if (it.isNotBlank()) showError = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                isError = showError,
                                placeholder = { Text("Enter your name") },
                                shape = RoundedCornerShape(18.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = { continueWithEnteredName() }
                                )
                            )

                            AnimatedVisibility(
                                visible = showError,
                                enter = fadeIn() + slideInVertically(),
                                exit = fadeOut()
                            ) {
                                Text(
                                    text = "Please enter a name or continue as guest.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Button(
                                onClick = { continueWithEnteredName() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(
                                    text = if (isReturningUser) "Save and continue" else "Continue",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            FilledTonalButton(
                                onClick = { continueAsGuest() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.filledTonalButtonColors()
                            ) {
                                Text(
                                    text = "Continue as Guest",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            if (isReturningUser) {
                                TextButton(
                                    onClick = {
                                        isEditingName = false
                                        showError = false
                                        name = if (savedIsGuest) "" else savedName
                                    }
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = if (isReturningUser) {
                    " "
                } else {
                    "You can update this later from your profile."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}