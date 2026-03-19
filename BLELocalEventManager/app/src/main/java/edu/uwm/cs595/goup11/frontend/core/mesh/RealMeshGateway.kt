package edu.uwm.cs595.goup11.frontend.core.mesh

import android.os.Build
import androidx.annotation.RequiresApi
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.NetworkState
import edu.uwm.cs595.goup11.backend.network.Peer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * ==========================================================
 * RealMeshGateway — UI ↔ Backend Adapter (Sprint 3)
 * ==========================================================
 *
 * PURPOSE
 * -------
 * This gateway adapts the backend/network primitives (NetworkState, Message, Peer)
 * into UI-safe models defined in MeshGateway.kt.
 *
 * UI/ViewModels MUST NOT import backend.network.* directly.
 * They should talk ONLY to MeshGateway.
 *
 * DATA FLOW
 * ---------
 * Backend (Client/Network) ->
 *   BackendFacade ->
 *     RealMeshGateway ->
 *       ViewModels ->
 *         Compose UI
 *
 * SPRINT 3 SCOPE
 * --------------
 * - "Nearby events" are discovered session IDs (from backend scan flow).
 * - Joining uses backend.joinNetwork(sessionId) and stores returned router Peer.
 * - Chat uses MessageType.TEXT_MESSAGE with UTF-8 payload.
 * - Event details are mocked until backend supports metadata broadcast.
 *
 * IMPORTANT DESIGN NOTES
 * ----------------------
 * 1) AppContainer.init() only CONSTRUCTS dependencies.
 *    You must call mesh.start() to attach collectors and message listeners.
 *
 * 2) Some backend implementations might not immediately emit NetworkState.Scanning
 *    when scan starts (depending on how BackendFacade is implemented).
 *
 *    To ensure the UI always leaves Idle when the user taps "Discover",
 *    we set MeshUiState.Scanning optimistically inside startScanning().
 *
 * FILE MAINTAINER
 * --------------
 * Primary maintainer: Frontend integration (Labib)
 */
class RealMeshGateway(
    private val backend: BackendFacade,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : MeshGateway {

    override val myId: String get() = backend.myId

    private val logger = KotlinLogging.logger {}

    // ------------------ UI State ------------------

    /**
     * UI-facing state flow. Compose screens read this to show "Idle/Scanning/Joined/etc."
     */
    private val _state = MutableStateFlow<MeshUiState>(MeshUiState.Idle)
    override val state: StateFlow<MeshUiState> = _state.asStateFlow()

    // ------------------ UI Streams ------------------

    /**
     * UI-facing discovered events stream.
     * Each item represents a nearby "event" (network sessionId).
     */
    private val _discovered = MutableSharedFlow<DiscoveredEventSummary>(extraBufferCapacity = 64)
    override val discoveredEvents: Flow<DiscoveredEventSummary> = _discovered.asSharedFlow()

    /**
     * UI-facing chat stream.
     */
    private val _chat = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 256)
    override val chat: Flow<ChatMessage> = _chat.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 500)
    override val logs: Flow<String> = _logs.asSharedFlow()

    // ------------------ Internal Session Tracking ------------------

    /**
     * Router returned by backend.joinNetwork(). For Sprint 3, chat is routed to this peer.
     * Cleared when leaveEvent() is called.
     */
    @Suppress("DEPRECATION")
    private var router: Peer? = null

    /** Tracks the current event name locally, replacing the deprecated backend.currentSessionId. */
    private var currentEventName: String? = null

    /**
     * Prevent multiple scanning collectors from being started if startScanning() is called repeatedly.
     */
    private var scanningCollectorStarted: Boolean = false

    /**
     * Prevent multiple start() collectors being created if start() is called multiple times.
     * This is a common bug: every start() adds a new backend.state collector.
     */
    private var started: Boolean = false

    // ------------------ Lifecycle ------------------

    /**
     * Must be called once early (e.g., in a ViewModel init or LaunchedEffect).
     *
     * Sets up:
     * - backend.start()
     * - mapping backend NetworkState -> MeshUiState
     * - message listener -> chat stream
     */
    override suspend fun start() {
        if (started) return
        started = true

        // Starts backend components (Client/Network initialization, etc.)
        backend.start()
        log("Gateway started. ID: $myId")

        // Map backend NetworkState -> UI state
        // This keeps UI consistent when backend transitions (Idle/Scanning/Joined/Hosting/Error).
        @Suppress("DEPRECATION")
        scope.launch {
            backend.state.collect { s ->
                log("Backend state: ${s::class.simpleName}")
                _state.value = when (s) {
                    is NetworkState.Idle -> MeshUiState.Idle
                    is NetworkState.Scanning -> MeshUiState.Scanning
                    is NetworkState.Joining -> MeshUiState.Joining
                    is NetworkState.Joined -> MeshUiState.InEvent(s.sessionId)
                    is NetworkState.Hosting -> MeshUiState.Hosting(s.sessionId)
                    is NetworkState.Error -> MeshUiState.Error(s.reason)
                    else -> MeshUiState.Error("Unsupported Event")
                }
            }
        }

        @Suppress("DEPRECATION")
        scope.launch {
            backend.events.collect { event ->
                when (event) {
                    is NetworkEvent.Joined -> log("Joined network: ${event.sessionId}")
                    is NetworkEvent.PeerConnected -> log("Peer connected: ${event.peer.endpointId}")
                    is NetworkEvent.PeerDisconnected -> log("Peer disconnected: ${event.endpointId}")
                    is NetworkEvent.MessageReceived -> {
                        val text = event.message.data?.toString(StandardCharsets.UTF_8) ?: "no data"
                        log("Message from ${event.message.from}: $text")
                    }
                    else -> Unit
                }
            }
        }

        // Listen for incoming backend messages (Sprint 3: TEXT_MESSAGE only)
        backend.addMessageListener { msg ->
            onBackendMessage(msg)
        }
    }


    private fun log(message: String) {
        logger.debug { message }
        _logs.tryEmit(message)
    }

    // ------------------ Scanning ------------------

    /**
     * Starts scanning for networks and emits DiscoveredEventSummary items as session IDs are found.
     *
     * IMPORTANT:
     * - We set MeshUiState.Scanning immediately to avoid being stuck in Idle if backend doesn't
     *   emit NetworkState.Scanning quickly (or if BackendFacade.scanNetworks() doesn't trigger it).
     * - backend.scanNetworks() must eventually call into network.startScan() for real discovery.
     */
    override suspend fun startScanning() {
        log("Start scanning requested")
        // Optimistic UI state change: user asked to scan, so UI should not remain Idle.
        _state.value = MeshUiState.Scanning

        // Only start one collector; repeated calls should be safe no-ops.
        if (scanningCollectorStarted) return
        scanningCollectorStarted = true

        // Backend returns a flow of discovered sessionIds.
        // NOTE: BackendFacade.scanNetworks() should start the scan internally (or you must add that there).
        val discoveredFlow = backend.scanNetworks()

        scope.launch {
            discoveredFlow.collect { sessionId ->
                log("Discovered nearby network: $sessionId")
                _discovered.emit(
                    DiscoveredEventSummary(
                        sessionId = sessionId,
                        title = sessionId,
                        venue = "Nearby"
                    )
                )
            }
        }
    }

    /**
     * Stops scan on the backend.
     * Depending on backend implementation, discovery may still emit briefly.
     */
    override suspend fun stopScanning() {
        log("Stop scanning requested")
        scanningCollectorStarted = false
        backend.stopScan()

        // If we are not joined/hosting, going back to Idle is reasonable for Sprint 3.
        if (router == null) {
            _state.value = MeshUiState.Idle
        }
    }

    // ------------------ Hosting / Joining ------------------

    /**
     * Hosts an event (creates network + starts advertising).
     *
     * Backend detail:
     * - Client.createNetwork() calls Network.create(name) then Network.startAdvertising()
     * - backend.state collector should transition UI to Hosting(sessionId)
     */
    @Deprecated("Defaults to SnakeTopology. Use hostEvent(eventName, topology) to specify.",
        ReplaceWith("hostEvent(eventName, TopologyChoice.SNAKE)"))
    override suspend fun hostEvent(eventName: String) {
        hostEvent(eventName, TopologyChoice.SNAKE)
    }

    override suspend fun hostEvent(eventName: String, topology: TopologyChoice) {
        log("Hosting event: $eventName (topology: ${topology.code})")
        currentEventName = eventName
        backend.createNetwork(eventName, topology)
        // UI state transitions to Hosting via backend.state collector.
    }

    /**
     * Joins a session and returns a UI event bundle.
     *
     * Sprint 3:
     * - Backend does not broadcast full event metadata yet.
     * - We return a mocked JoinedEventBundle so EventDetail UI can be built.
     */
    override suspend fun joinEvent(sessionId: String): JoinedEventBundle {
        log("Joining event: $sessionId")
        @Suppress("DEPRECATION")
        _state.value = MeshUiState.Joining

        currentEventName = sessionId

        // backend.joinNetwork returns the router peer we joined through
        @Suppress("DEPRECATION")
        router = backend.joinNetwork(sessionId)
        @Suppress("DEPRECATION")
        log("Joined via router: ${router?.endpointId}")

        return JoinedEventBundle(
            sessionId = sessionId,
            title = sessionId,
            venue = "Venue (host broadcast later)",
            description = "Joined via mesh. Chat is live; event metadata can be synced later.",
            itinerary = listOf(
                ItineraryItem("it-1", "Welcome / Announcements", "1:00 PM", "Main Hall"),
                ItineraryItem("it-2", "Talk: Offline Mesh Networking", "1:30 PM", "Room A", "Group 11"),
                ItineraryItem("it-3", "Q&A / Chat", "2:15 PM", "Lobby")
            )
        )
    }

    /**
     * Leaves current event and resets routing state.
     */
    override suspend fun leaveEvent() {
        log("Leaving event")
        @Suppress("DEPRECATION")
        router = null
        currentEventName = null
        backend.leave()

        // Force UI back to Idle immediately; backend.state collector should also move to Idle.
        _state.value = MeshUiState.Idle
    }

    // ------------------ Chat ------------------

    /**
     * Sends a TEXT_MESSAGE to the router endpointId.
     *
     * Sprint 3 design:
     * - Optimistically emit the message locally (so UI feels instant)
     * - Send UTF-8 payload as bytes
     */
    override suspend fun sendChat(text: String) {
        @Suppress("DEPRECATION")
        val r = router ?: return
        val sessionId = currentEventName ?: "unknown"

        // Optimistic local emit
        _chat.tryEmit(
            ChatMessage(
                sessionId = sessionId,
                sender = backend.myId,
                text = text,
                timestampMs = System.currentTimeMillis(),
                isMine = true
            )
        )
        log("Sending chat: $text to ${r.endpointId}")

        val msg = Message(
            to = r.endpointId,
            from = backend.myId,
            type = MessageType.TEXT_MESSAGE,
            data = text.toByteArray(StandardCharsets.UTF_8),
            ttl = 5
        )

        backend.sendMessage(r.endpointId, msg)
    }

    /**
     * Internal handler for raw backend messages.
     *
     * Sprint 3:
     * - Only TEXT_MESSAGE is converted to ChatMessage.
     * - Other message types are ignored for UI.
     */
    private fun onBackendMessage(msg: Message) {
        when (msg.type) {
            MessageType.TEXT_MESSAGE -> {
                val sessionId = currentEventName ?: "unknown"
                val text = msg.data?.toString(StandardCharsets.UTF_8).orEmpty()
                log("Received chat from ${msg.from}: $text")

                _chat.tryEmit(
                    ChatMessage(
                        sessionId = sessionId,
                        sender = msg.from,
                        text = text,
                        timestampMs = System.currentTimeMillis(),
                        isMine = (msg.from == backend.myId)
                    )
                )
            }
            else -> Unit
        }
    }
}