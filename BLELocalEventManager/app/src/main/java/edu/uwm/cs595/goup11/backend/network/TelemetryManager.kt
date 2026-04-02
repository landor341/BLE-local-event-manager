package edu.uwm.cs595.goup11.backend.network

import android.content.Context
import android.location.Location
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.android.gms.location.LocationServices
import edu.uwm.cs595.goup11.backend.network.payloads.TelemetryPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Used to handle all telemetry for the system. Call
 */
@RequiresApi(Build.VERSION_CODES.O)
object TelemetryManager {


    private final val loopTimeMillis: Long = 5;
    private var loopJob: Job? = null;
    private var client: Client? = null;

    private var RTTData: MutableList<RTTFrame> = mutableListOf<RTTFrame>();
    private var lastKnownLocation: Location? = null
    /**
     * Configures the telemetry class on how to handle data
     *
     */
    fun configure() {

    }

    fun start(client: Client, scope: CoroutineScope, context: Context) {
        this.client = client

        // Add message listener for specific Telemetry Types
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let { lastKnownLocation = it }
            }
            // Note: for continuous updates you'd use requestLocationUpdates here.
            // lastLocation is sufficient for stationary distance-vs-RTT tests.
        } catch (e: SecurityException) {
            // ACCESS_FINE_LOCATION not granted — GPS disabled silently
        }

        client.addMessageListener { message ->
            when(message.type) {
                MessageType.TELEMETRY_RTT -> {
                    val res = TelemetryPayload(
                        from = client.endpointId ?: throw IllegalStateException("Client not instantiated"),
                        epoch = Instant.now().toEpochMilli(),
                        hops = 0
                    )
                    if(lastKnownLocation != null) {
                        res.senderLat = lastKnownLocation?.latitude
                        res.senderLon = lastKnownLocation?.longitude
                        res.senderGpsAccuracyM = lastKnownLocation?.accuracy
                    }
                    val m = Message(
                        from = client.endpointId ?: throw IllegalStateException("Client not instantiated"),
                        to = message.from,
                        type = MessageType.TELEMETRY_RTT_ACK,
                        ttl = 1,
                        replyTo = message.id
                    ).addBody(TelemetryPayload.serializer(), res);

                    client.sendMessage(m)
                }
                else -> {/*ignore*/}
            }
        }

        loopJob = scope.launch {
            runTelemetryMetrics()

            delay(loopTimeMillis)
        }
    }

    fun stop() {
        if(loopJob != null) loopJob?.cancel()
    }

    /**
     * Collects and logs metric telemetry data. This method is called every [loopTimeMillis] from the
     * [loopJob] that is created during the [start] method.
     *
     * # Types of data collected
     * - **PING/PONG** message (time received/sent)
     *
     * # Format of data
     *
     * ## PING/PONG
     *
     * Contains data of periodic PING messages being sent. All time is stored as Epoch Time as a [kotlin.time.Instant]
     * Stored as [RTTFrame]
     */
    private suspend fun runTelemetryMetrics() {
        val c = requireClient()
        /**
         * Ping requests
         */

        val peers = c.directConnectedPeers()

        peers.forEach { peer ->
            val epoch = Instant.now().toEpochMilli()
            val payload = TelemetryPayload(
                from = client?.endpointId ?: throw IllegalStateException("Client endpointID is not set"),
                epoch = epoch,
                hops = 0,
            )
            if(lastKnownLocation != null) {
                payload.senderLat = lastKnownLocation?.latitude
                payload.senderLon = lastKnownLocation?.longitude
                payload.senderGpsAccuracyM = lastKnownLocation?.accuracy
            }

            val message = Message(
                to = peer.hardwareId,
                from = c.endpointId ?: throw IllegalStateException("Client endpointID is not set"),
                type = MessageType.TELEMETRY_RTT,
                ttl = 1
            ).addBody(TelemetryPayload.serializer(), payload);

            val res = c.sendMessageAndWait(message)

            if(lastKnownLocation != null) {

            }

        }
    }

    private fun requireClient(): Client {
        return this.client ?: throw IllegalStateException("Method requires client")
    }

    data class RTTFrame(
        val user: String,
        val epochSent: Long,
        val epochReceived: Long,
        /**
         * Round-trip-time
         */
        val RTT: Long,

        /**
         * Time-to-client
         */
        val TTC: Long,

        /**
         * Time-from-client
         */
        val TFC: Long,
        val selfLong: Double,
        val selfLat: Double,
        val peerLong: Double,
        val peerLat: Double,
        val estimatedDistance: Long
    )
}