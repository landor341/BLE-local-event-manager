package edu.uwm.cs595.goup11.backend.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
     *      | obfuscatedUser | epochSent | epochReceived | roundTripTime | timeToSender | timeFromSender | dist? |
     *      | STRING         | LONG      | LONG          | LONG          | LONG         | LONG           | LONG  |
     */
    private suspend fun runTelemetryMetrics() {

    }
}