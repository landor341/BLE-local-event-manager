package edu.uwm.cs595.goup11.backend.network


import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PresentationEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startTime: Long,        // epoch ms
    val endTime: Long,          // epoch ms
    val location: String,
    val speakerName: String,
    val speakerEndpointId: String,
    val status: PresentationStatus = PresentationStatus.ACTIVE
)

@Serializable
enum class PresentationStatus {
    ACTIVE,
    ENDED
}