package edu.uwm.cs595.goup11.frontend.features.presentation

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import edu.uwm.cs595.goup11.R
import edu.uwm.cs595.goup11.backend.network.PresentationStatus
import edu.uwm.cs595.goup11.frontend.core.ui.theme.BLELocalEventManagerTheme
import java.time.LocalDateTime

// PresentationDetailScreen.kt
// Appears when the user selects a presentation and displays information relevant to the presentation

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentationDetailScreen(
    presentation: Presentation,
    onBack: () -> Unit,
    onNavigateToSpeaker: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(presentation.name) },
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
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
//            Image(
//                painter = painterResource(id = R.drawable.map_sample),
//                contentDescription = "Presentation Location",
//                contentScale = ContentScale.Crop,
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .height(250.dp)
//            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = presentation.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Time: ${presentation.startTime.toDisplayTime()} - ${presentation.endTime.toDisplayTime()}",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray
            )
            Text(
                text = "Location: ${presentation.location}",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(Modifier.height(24.dp))

            if (presentation.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = presentation.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Presented by",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.profile_picture),
                    contentDescription = "Speaker Profile",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )

                Text(
                    text = presentation.speakerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true)
@Composable
fun PresentationDetailPreview() {
    BLELocalEventManagerTheme {
        PresentationDetailScreen(
            presentation = Presentation(
                id                = "1",
                name              = "testPresentation",
                startTime              = LocalDateTime.of(2026, 3, 19, 10, 0),
                endTime           = LocalDateTime.of(2026, 3, 19, 11, 30),
                location          = "room 012",
                speakerName       = "Speaker1",
                speakerEndpointId = "speaker-endpoint-id",
                status            = PresentationStatus.ACTIVE
            ),
            onBack = {},
            onNavigateToSpeaker = {}
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
fun LocalDateTime.toDisplayTime(): String {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH)
    return this.format(formatter)
}