package edu.uwm.cs595.goup11.backend.network.payloads

import kotlinx.serialization.Serializable

@Serializable
data class TelemetryPayload(
    val from: String,
    val epoch: Long,
    val hops: Int,

    /** Sender's GPS latitude at time of send. Null if no fix available. */
    var senderLat: Double? = null,

    /** Sender's GPS longitude at time of send. Null if no fix available. */
    var senderLon: Double? = null,

    /** Sender's GPS accuracy in metres. Null if no fix available. */
    var senderGpsAccuracyM: Float? = null
)
