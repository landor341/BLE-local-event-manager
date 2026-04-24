package edu.uwm.cs595.goup11.frontend.features.createevent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LinearScale
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
import edu.uwm.cs595.goup11.frontend.core.mesh.TopologyChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEventScreen(
    viewModel: CreateEventViewModel,
    onBack: () -> Unit,
    onHostingStarted: (String) -> Unit = {},
    onNavigateToCreatePresentation: () -> Unit = {}
) {
    val draft by viewModel.draft.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Create Event") },
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
            CreateEventUiState.Editing,
            is CreateEventUiState.Error,
            CreateEventUiState.Submitting -> {
                CreateEventForm(
                    draft          = draft,
                    uiState        = uiState,
                    onTitleChange  = viewModel::updateTitle,
                    onTopologyChange = viewModel::updateTopology,
                    onSubmit       = viewModel::hostEvent,
                    onBack         = onBack,
                    modifier       = Modifier.padding(innerPadding)
                )
            }

            is CreateEventUiState.Hosting -> {
                HostingSuccessScreen(
                    sessionId         = state.sessionId,
                    draft             = draft,
                    onAddPresentation = { onNavigateToCreatePresentation() },
                    onDone            = { onHostingStarted(state.sessionId) },
                    onCreateAnother   = { viewModel.reset() },
                    modifier          = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

// ── Form ──────────────────────────────────────────────────────────────────────

@Composable
private fun CreateEventForm(
    draft: CreateEventDraft,
    uiState: CreateEventUiState,
    onTitleChange: (String) -> Unit,
    onTopologyChange: (TopologyChoice) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSubmitting = uiState is CreateEventUiState.Submitting
    val errorMessage = (uiState as? CreateEventUiState.Error)?.message

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

        // ── Event name ────────────────────────────────────────────────────────
        Card(
            shape     = RoundedCornerShape(28.dp),
            colors    = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier            = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text       = "Event details",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value         = draft.title,
                    onValueChange = onTitleChange,
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    label         = { Text("Event name") },
                    placeholder   = { Text("Spring showcase, CS meetup, design review…") },
                    leadingIcon   = {
                        Icon(
                            imageVector        = Icons.Default.CalendarMonth,
                            contentDescription = null
                        )
                    },
                    shape          = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        }

        // ── Topology picker ───────────────────────────────────────────────────
        Card(
            shape     = RoundedCornerShape(28.dp),
            colors    = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier            = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text       = "Network topology",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text  = "Choose how devices connect to each other in this event.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TopologyOption(
                    title       = "Snake",
                    description = "Devices connect in a chain — each node links to the next. " +
                            "Best for linear setups like a row of seats or a hallway. " +
                            "Simple and predictable, but if the middle node drops, the chain splits.",
                    icon        = Icons.Default.LinearScale,
                    selected    = draft.topology == TopologyChoice.SNAKE,
                    onClick     = { onTopologyChange(TopologyChoice.SNAKE) }
                )

                TopologyOption(
                    title       = "Mesh",
                    description = "Every device connects to as many nearby peers as possible. " +
                            "More resilient — if one node drops, traffic routes around it. " +
                            "Best for open spaces where people move around freely.",
                    icon        = Icons.Default.AccountTree,
                    selected    = draft.topology == TopologyChoice.MESH,
                    onClick     = { onTopologyChange(TopologyChoice.MESH) }
                )
            }
        }

        // ── Preview ───────────────────────────────────────────────────────────
        PreviewCard(draft = draft)

        // ── Actions ───────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick  = onSubmit,
                enabled  = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text("Starting host…")
                } else {
                    Icon(
                        imageVector        = Icons.Default.Podcasts,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text("Start hosting")
                }
            }

            FilledTonalButton(
                onClick  = onBack,
                enabled  = !isSubmitting,
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

// ── Topology option card ──────────────────────────────────────────────────────

@Composable
private fun TopologyOption(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    val bgColor     = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = bgColor, shape = RoundedCornerShape(18.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment     = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier           = Modifier.size(20.dp)
            )
        }

        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                if (selected) {
                    Icon(
                        imageVector        = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint               = MaterialTheme.colorScheme.primary,
                        modifier           = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text  = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Hero ──────────────────────────────────────────────────────────────────────

@Composable
private fun HeroSection() {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
        )
    )

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(30.dp),
        colors    = CardDefaults.cardColors(
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
                verticalAlignment     = Alignment.CenterVertically
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
                    text       = "Host a live session",
                    color      = MaterialTheme.colorScheme.onPrimary,
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text       = "Launch an event people nearby can discover instantly.",
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

// ── Preview card ──────────────────────────────────────────────────────────────

@Composable
private fun PreviewCard(draft: CreateEventDraft) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(24.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier            = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text       = "Preview",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text       = draft.title.ifBlank { "Untitled Event" },
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            MetaRow(
                icon  = Icons.Default.AccountTree,
                value = "Topology: ${draft.topology.name.lowercase().replaceFirstChar { it.uppercase() }}"
            )
        }
    }
}

// ── Hosting success ───────────────────────────────────────────────────────────

@Composable
private fun HostingSuccessScreen(
    sessionId: String,
    draft: CreateEventDraft,
    onAddPresentation: () -> Unit,
    onDone: () -> Unit,
    onCreateAnother: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Card(
            modifier  = Modifier.fillMaxWidth(),
            shape     = RoundedCornerShape(30.dp),
            colors    = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier            = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.primary,
                        modifier           = Modifier.size(30.dp)
                    )
                }

                Text(
                    text       = "Your event is live",
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text  = "Nearby users can now discover and join this session.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                MetaRow(
                    icon  = Icons.Default.CalendarMonth,
                    value = draft.title.ifBlank { sessionId }
                )

                MetaRow(
                    icon  = Icons.Default.AccountTree,
                    value = "Topology: ${draft.topology.name.lowercase().replaceFirstChar { it.uppercase() }}"
                )

                MetaRow(
                    icon  = Icons.Default.Podcasts,
                    value = "Session ID: $sessionId"
                )
            }
        }

        Button(
            onClick  = onAddPresentation,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("Add a Presentation")
        }

        FilledTonalButton(
            onClick  = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("Done")
        }

        TextButton(
            onClick  = onCreateAnother,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Create another event")
        }
    }
}

// ── Shared ────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(22.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text       = "Couldn't start event",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text  = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun MetaRow(icon: ImageVector, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier           = Modifier.size(18.dp)
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}