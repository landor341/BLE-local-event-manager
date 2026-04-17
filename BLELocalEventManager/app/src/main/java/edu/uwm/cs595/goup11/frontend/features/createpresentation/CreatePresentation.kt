package edu.uwm.cs595.goup11.frontend.features.createpresentation

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePresentationScreen(
    viewModel: CreatePresentationViewModel,
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    val draft by viewModel.draft.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Add Presentation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            CreatePresentationUiState.Editing,
            is CreatePresentationUiState.Error,
            CreatePresentationUiState.Submitting -> {
                CreatePresentationForm(
                    draft = draft,
                    uiState = uiState,
                    onTitleChange = viewModel::updateTitle,
                    onDescriptionChange = viewModel::updateDescription,
                    onStartTimeClick = {
                        showLegacyTimePicker(context, draft.startTime) { h, m ->
                        viewModel.updateStartTime(h, m)
                    } },
                    onEndTimeClick = {
                        showLegacyTimePicker(context, draft.endTime) { h, m ->
                        viewModel.updateEndTime(h, m)
                    } },
                    onLocationChange = viewModel::updateLocation,
                    onSpeakerChange = viewModel::updateSpeakerName,
                    onSubmit = viewModel::addPresentation,
                    onBack = onBack,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            CreatePresentationUiState.Success -> {
                LaunchedEffect(Unit) {
                    onSuccess()
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun CreatePresentationForm(
    draft: CreatePresentationDraft,
    uiState: CreatePresentationUiState,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onStartTimeClick: () -> Unit,
    onEndTimeClick: () -> Unit,
    onLocationChange: (String) -> Unit,
    onSpeakerChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSubmitting = uiState is CreatePresentationUiState.Submitting
    val errorMessage = (uiState as? CreatePresentationUiState.Error)?.message

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        HeroSection()

        if (errorMessage != null) {
            ErrorCard(message = errorMessage)
        }

        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Presentation details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                PresentationField(
                    value = draft.title,
                    onValueChange = onTitleChange,
                    label = "Presentation name",
                    placeholder = "Enter presentation title...",
                    icon = Icons.Default.CalendarMonth,
                    singleLine = true
                )

                PresentationField(
                    value = draft.description,
                    onValueChange = onDescriptionChange,
                    label = "Description",
                    placeholder = "Briefly describe the presentation...",
                    icon = Icons.Default.Description,
                    singleLine = false,
                    minLines = 4
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeDisplayField(
                        label = "Start Time",
                        time = draft.startTime.toDisplayTime(),
                        onClick = onStartTimeClick,
                        modifier = Modifier.weight(1f)
                    )
                    TimeDisplayField(
                        label = "End Time",
                        time = draft.endTime.toDisplayTime(),
                        onClick = onEndTimeClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                PresentationField(
                    value = draft.location,
                    onValueChange = onLocationChange,
                    label = "Location",
                    placeholder = "Enter the location...",
                    icon = Icons.Default.LocationOn,
                    singleLine = true
                )

                PresentationField(
                    value = draft.speaker,
                    onValueChange = onSpeakerChange,
                    label = "Speaker name",
                    placeholder = "Enter Speaker's name...",
                    icon = Icons.Default.Person,
                    singleLine = true
                )
            }
        }

        PreviewCard(draft = draft)

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSubmit,
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(10.dp))
                    Text("Syncing Schedule...")
                } else {
                    Text("Add to Schedule")
                }
            }

            FilledTonalButton(
                onClick = onBack,
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun TimeDisplayField(
    label: String,
    time: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(time, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
private fun HeroSection() {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape
                        )
                )
                Text(
                    text = "Add a Talk",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "Schedule a session that attendees can follow in real-time.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )

            Text(
                text = "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)
            )
        }
    }
}

@Composable
private fun PresentationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    singleLine: Boolean,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
        },
        shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default
        )
    )
}

@Composable
private fun PreviewCard(
    draft: CreatePresentationDraft
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = draft.title.ifBlank { "Untitled Presentation" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (draft.description.isNotBlank()) {
                Text(
                    text = draft.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            MetaRow(
                icon = Icons.Default.CalendarMonth,
                value = "${draft.startTime.toDisplayTime()} - ${draft.endTime.toDisplayTime()}"
            )

            MetaRow(
                icon = Icons.Default.LocationOn,
                value = draft.location.ifBlank { "Location not set" }
            )

            MetaRow(
                icon = Icons.Default.Person,
                value = draft.speaker.ifBlank { "Speaker not set" }
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Couldn't add presentation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun MetaRow(
    icon: ImageVector,
    value: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
fun java.time.LocalDateTime.toDisplayTime(): String {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH)
    return this.format(formatter)
}

fun showLegacyTimePicker(context: android.content.Context, initialTime: java.time.LocalDateTime, onSelected: (Int, Int) -> Unit) {
    android.app.TimePickerDialog(context, { _, h, m -> onSelected(h, m) }, initialTime.hour, initialTime.minute, false).show()
}


