package edu.uwm.cs595.goup11.backend.network

import android.os.Build
import androidx.annotation.RequiresApi
import edu.uwm.cs595.goup11.backend.network.payloads.TelemetryPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration

/**
 * Used to handle all telemetry for the system. Call
 */
object TelemetryManager {


    private final val loopTimeMillis: Long = 5;
    private var loopJob: Job? = null;
    private var client: Client? = null;

    /**
     * Configures the telemetry class on how to handle data
     *
     */
    fun configure() {

    }

    fun start(client: Client, scope: CoroutineScope) {
        this.client = client
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
     *
     *      | obfuscatedUser | epochSent | epochReceived | RTT | TTS | TFS | dist? |
     *      | STRING         | LONG      | LONG          | LONG| LONG| LONG| LONG  |
     */
    @RequiresApi(Build.VERSION_CODES.O)
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
                hops = 0
            )

            val message = Message(
                to = peer.hardwareId,
                from = c.endpointId ?: throw IllegalStateException("Client endpointID is not set"),
                type = MessageType.TELEMETRY_RTT,
                ttl = 1
            ).addBody(TelemetryPayload.serializer(), payload);

            val res = c.sendMessageAndWait(message)


        }
    }

    private fun requireClient(): Client {
        return this.client ?: throw IllegalStateException("Method requires client")
    }

    data class RTTFrame(
        val user: String,
        val epochSent: Long,
        val epochRecieved: Long,
        val RTT: Long,
        val TTS: Long,
        val TFS: Long
    )
}