package edu.uwm.cs595.goup11.backend.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * TelemetryManager — singleton message telemetry tracker.
 *
 * Instrument call sites in Client:
 *   - TelemetryManager.recordSent(message.type) in sendMessage / broadcastMessage
 *   - TelemetryManager.recordReceived(message.type) in onMessageReceived
 *   - TelemetryManager.recordForwarded(message.type) in handleMessage forwarding block
 *
 * Lives in backend.network so it has no frontend dependency.
 */
object TelemetryManager {

    private const val TAG = "TelemetryManager"

    // ── Lifetime counters ─────────────────────────────────────────────────────

    private val _totalSent = AtomicLong(0)
    private val _totalReceived = AtomicLong(0)
    private val _totalForwarded = AtomicLong(0)

    // Per-type breakdown: MessageType.name -> count
    private val _sentByType = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val _receivedByType = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val _forwardedByType = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    // ── Rate tracking (rolling 5-second window) ───────────────────────────────

    // Ring buffer of (timestampMs, delta) for rate calculation
    private val windowMs = 5_000L
    private val sentTimestamps = ArrayDeque<Long>()
    private val receivedTimestamps = ArrayDeque<Long>()
    private val forwardedTimestamps = ArrayDeque<Long>()
    private val tsLock = Any()

    // ── Session info ──────────────────────────────────────────────────────────

    private var sessionId: String? = null
    private var sessionStartMs: Long = 0L
    private val _directPeers = MutableStateFlow<List<String>>(emptyList())

    // ── Exposed StateFlow snapshot ────────────────────────────────────────────

    private val _snapshot = MutableStateFlow(TelemetrySnapshot())
    val snapshot: StateFlow<TelemetrySnapshot> = _snapshot.asStateFlow()

    // ── Event log (last 200 entries) ──────────────────────────────────────────

    private val _eventLog = MutableStateFlow<List<TelemetryEvent>>(emptyList())
    val eventLog: StateFlow<List<TelemetryEvent>> = _eventLog.asStateFlow()

    // ── Instrumentation API ───────────────────────────────────────────────────

    fun recordSent(type: MessageType) {
        _totalSent.incrementAndGet()
        _sentByType.getOrPut(type.name) { AtomicLong(0) }.incrementAndGet()
        synchronized(tsLock) { sentTimestamps.addLast(System.currentTimeMillis()) }
        pushSnapshot()
        Log.v(TAG, "SENT $type | total=${_totalSent.get()}")
    }

    fun recordReceived(type: MessageType) {
        _totalReceived.incrementAndGet()
        _receivedByType.getOrPut(type.name) { AtomicLong(0) }.incrementAndGet()
        synchronized(tsLock) { receivedTimestamps.addLast(System.currentTimeMillis()) }
        pushSnapshot()
    }

    fun recordForwarded(type: MessageType) {
        _totalForwarded.incrementAndGet()
        _forwardedByType.getOrPut(type.name) { AtomicLong(0) }.incrementAndGet()
        synchronized(tsLock) { forwardedTimestamps.addLast(System.currentTimeMillis()) }
        pushSnapshot()
    }

    fun updateDirectPeers(peers: List<String>) {
        _directPeers.value = peers
        pushSnapshot()
    }

    fun onSessionStarted(id: String) {
        sessionId = id
        sessionStartMs = System.currentTimeMillis()
        appendLog(TelemetryEvent.SessionEvent("Session started: $id"))
        pushSnapshot()
    }

    fun onSessionEnded() {
        appendLog(TelemetryEvent.SessionEvent("Session ended: ${sessionId ?: "unknown"}"))
        sessionId = null
        sessionStartMs = 0L
        pushSnapshot()
    }

    fun reset() {
        _totalSent.set(0)
        _totalReceived.set(0)
        _totalForwarded.set(0)
        _sentByType.clear()
        _receivedByType.clear()
        _forwardedByType.clear()
        synchronized(tsLock) {
            sentTimestamps.clear()
            receivedTimestamps.clear()
            forwardedTimestamps.clear()
        }
        _eventLog.value = emptyList()
        sessionId = null
        sessionStartMs = 0L
        pushSnapshot()
        Log.d(TAG, "Telemetry reset")
    }

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportCsv(): String {
        val snap = _snapshot.value
        val sb = StringBuilder()
        sb.appendLine("BLE Local Event Manager — Telemetry Export")
        sb.appendLine(
            "Generated,${
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.US
                ).format(Date())
            }"
        )
        sb.appendLine("Session,${snap.sessionId ?: "none"}")
        sb.appendLine("Session Duration (s),${snap.sessionDurationSec}")
        sb.appendLine()
        sb.appendLine("--- Totals ---")
        sb.appendLine("Metric,Count")
        sb.appendLine("Sent,${snap.totalSent}")
        sb.appendLine("Received,${snap.totalReceived}")
        sb.appendLine("Forwarded,${snap.totalForwarded}")
        sb.appendLine()
        sb.appendLine("--- Rates (per second, 5s window) ---")
        sb.appendLine("Sent/s,${snap.sentPerSec}")
        sb.appendLine("Received/s,${snap.receivedPerSec}")
        sb.appendLine("Forwarded/s,${snap.forwardedPerSec}")
        sb.appendLine()
        sb.appendLine("--- By Type ---")
        sb.appendLine("Type,Sent,Received,Forwarded")
        val allTypes =
            (_sentByType.keys + _receivedByType.keys + _forwardedByType.keys).toSortedSet()
        allTypes.forEach { t ->
            val s = _sentByType[t]?.get() ?: 0
            val r = _receivedByType[t]?.get() ?: 0
            val f = _forwardedByType[t]?.get() ?: 0
            sb.appendLine("$t,$s,$r,$f")
        }
        sb.appendLine()
        sb.appendLine("--- Direct Peers ---")
        snap.directPeers.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("--- Event Log ---")
        _eventLog.value.forEach { sb.appendLine(it.formatted()) }
        return sb.toString()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun pushSnapshot() {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs
        val (sentRate, recvRate, fwdRate) = synchronized(tsLock) {
            while (sentTimestamps.firstOrNull()
                    ?.let { it < cutoff } == true
            ) sentTimestamps.removeFirst()
            while (receivedTimestamps.firstOrNull()
                    ?.let { it < cutoff } == true
            ) receivedTimestamps.removeFirst()
            while (forwardedTimestamps.firstOrNull()
                    ?.let { it < cutoff } == true
            ) forwardedTimestamps.removeFirst()
            Triple(
                sentTimestamps.size / (windowMs / 1000f),
                receivedTimestamps.size / (windowMs / 1000f),
                forwardedTimestamps.size / (windowMs / 1000f)
            )
        }

        val durationSec = if (sessionStartMs > 0) (now - sessionStartMs) / 1000L else 0L

        _snapshot.value = TelemetrySnapshot(
            totalSent = _totalSent.get(),
            totalReceived = _totalReceived.get(),
            totalForwarded = _totalForwarded.get(),
            sentPerSec = sentRate,
            receivedPerSec = recvRate,
            forwardedPerSec = fwdRate,
            sentByType = _sentByType.mapValues { it.value.get() },
            receivedByType = _receivedByType.mapValues { it.value.get() },
            forwardedByType = _forwardedByType.mapValues { it.value.get() },
            directPeers = _directPeers.value,
            sessionId = sessionId,
            sessionDurationSec = durationSec,
            snapshotTimeMs = now
        )
    }

    private fun appendLog(event: TelemetryEvent) {
        val current = _eventLog.value.toMutableList()
        current.add(event)
        if (current.size > 200) current.removeAt(0)
        _eventLog.value = current
    }
}

// ── Data models ───────────────────────────────────────────────────────────────

data class TelemetrySnapshot(
    val totalSent: Long = 0,
    val totalReceived: Long = 0,
    val totalForwarded: Long = 0,
    val sentPerSec: Float = 0f,
    val receivedPerSec: Float = 0f,
    val forwardedPerSec: Float = 0f,
    val sentByType: Map<String, Long> = emptyMap(),
    val receivedByType: Map<String, Long> = emptyMap(),
    val forwardedByType: Map<String, Long> = emptyMap(),
    val directPeers: List<String> = emptyList(),
    val sessionId: String? = null,
    val sessionDurationSec: Long = 0,
    val snapshotTimeMs: Long = 0
)

sealed class TelemetryEvent {
    abstract fun formatted(): String

    data class SessionEvent(val message: String) : TelemetryEvent() {
        private val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        override fun formatted() = "[$time] SESSION: $message"
    }

    data class MessageEvent(val direction: String, val type: String) : TelemetryEvent() {
        private val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        override fun formatted() = "[$time] $direction: $type"
    }
}