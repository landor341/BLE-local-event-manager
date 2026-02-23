package edu.uwm.cs595.goup11.frontend.features.chatroom

import android.os.Build
import androidx.annotation.RequiresApi
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import java.time.LocalDateTime

// PresentationMockData.kt
// Temporary mock data for Chat Room screen until mesh discovery is wired in.
object PresentationMockData {
    @RequiresApi(Build.VERSION_CODES.O)
    fun presentations(): List<Presentation> = listOf(
        Presentation("1", "Presentation1",
            LocalDateTime.of(2026, 3, 8, 10, 0,0),
            "buildingA","Amy"),
        Presentation("3", "Presentation2",
            LocalDateTime.of(2026, 3, 8, 10, 0,0),
            "buildingB", speaker = "Alex")
    )
}

