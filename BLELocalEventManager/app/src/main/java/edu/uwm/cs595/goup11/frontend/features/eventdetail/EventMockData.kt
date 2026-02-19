package edu.uwm.cs595.goup11.frontend.features.eventdetail

import edu.uwm.cs595.goup11.frontend.domain.models.Event

// EventMockData.kt
// Temporary mock data for EventDetail screen until mesh discovery is wired in.
object EventMockData {
    fun events(): List<Event> = listOf(
        Event("1", "Career Fair", "Engineering booths and recruiters", "UWM", 12, time = "2026/02/16",
            location = "buildingA"),
        Event("3", "Tech Talk", "Mobile networking", "EMS", 20, time = "2026/02/16",
            location = "buildingB")
    )
}
