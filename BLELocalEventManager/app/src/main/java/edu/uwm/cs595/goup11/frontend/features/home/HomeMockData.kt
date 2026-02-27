package edu.uwm.cs595.goup11.frontend.features.home

import android.os.Build
import androidx.annotation.RequiresApi
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import java.time.LocalDateTime

//mock presentation data for nearby presentations section

object HomeMockData {
    @RequiresApi(Build.VERSION_CODES.O)
    fun presentations(): List<Presentation> = listOf(
        Presentation("a123", "Unraveling the Mysteries of Jupiter",
            LocalDateTime.of(2026, 3, 8, 10, 0,0),
            "UWM Planetarium", "Dr. Galaxy" ),
        Presentation("b456", "Cooking on a Budget",
            LocalDateTime.of(2026, 4, 2, 12, 0, 0),
            "Culinary Building W 352", "Rosemary Bacon")

    )
}
