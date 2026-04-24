// Presentation.kt
// Represents a presentation within an event.
// Fields will be defined once presentation requirements are finalized.

package edu.uwm.cs595.goup11.frontend.domain.models

import edu.uwm.cs595.goup11.backend.network.PresentationEntry
import edu.uwm.cs595.goup11.backend.network.PresentationStatus
import java.time.LocalDateTime
import java.time.ZoneOffset

data class Presentation(
    val id: String,
    val name: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val location: String,
    val speakerName: String,
    val speakerEndpointId: String,
    val status: PresentationStatus
)

fun PresentationEntry.toUiModel(): Presentation = Presentation(
    id                = id,
    name              = name,
    startTime         = LocalDateTime.ofEpochSecond(startTime / 1000, 0, ZoneOffset.UTC),
    endTime           = LocalDateTime.ofEpochSecond(endTime / 1000, 0, ZoneOffset.UTC),
    location          = location,
    speakerName       = speakerName,
    speakerEndpointId = speakerEndpointId,
    status            = status
)