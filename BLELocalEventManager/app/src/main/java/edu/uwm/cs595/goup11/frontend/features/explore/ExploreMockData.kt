package edu.uwm.cs595.goup11.frontend.features.explore

import edu.uwm.cs595.goup11.frontend.domain.models.Event

// ExploreMockData.kt
// Temporary mock data for Explore screen until mesh discovery is wired in.
object ExploreMockData {
    fun events(): List<Event> = listOf(
        Event("1", "Career Fair", "Engineering booths and recruiters", "UWM", 12),
        Event("3", "Tech Talk", "Mobile networking", "EMS", 20)
    )
}
