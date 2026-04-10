package edu.uwm.cs595.goup11.frontend.features.home

import android.os.Build
import androidx.annotation.RequiresApi
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import edu.uwm.cs595.goup11.frontend.domain.models.User
import java.time.LocalDateTime

object HomeMockData {
    @RequiresApi(Build.VERSION_CODES.O)
    fun presentations(): List<Presentation> = listOf(
        Presentation(
            id = "a123",
            name = "Unraveling the Mysteries of Jupiter",
            time = LocalDateTime.of(2026, 3, 8, 10, 0, 0),
            endTime = LocalDateTime.of(2026, 3, 8, 11, 0, 0),
            location = "UWM Planetarium",
            speaker = User(
                id = "speaker1",
                username = "Dr. Galaxy"
            )
        ),
        Presentation(
            id = "b456",
            name = "Cooking on a Budget",
            time = LocalDateTime.of(2026, 4, 2, 12, 0, 0),
            endTime = LocalDateTime.of(2026, 4, 2, 1, 0, 0),
            location = "Culinary Building W 352",
            speaker = User(
                id = "speaker2",
                username = "Rosemary Bacon"
            )
        )
    )
}