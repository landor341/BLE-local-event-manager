// Presentation.kt
// Represents a presentation within an event.
// Fields will be defined once presentation requirements are finalized.

package edu.uwm.cs595.goup11.frontend.domain.models

import java.time.LocalDateTime
data class Presentation(
    val id: String,
    val name: String,
    val time: LocalDateTime,
    val location: String,
    val speaker: String,
   // val event: Event,

)