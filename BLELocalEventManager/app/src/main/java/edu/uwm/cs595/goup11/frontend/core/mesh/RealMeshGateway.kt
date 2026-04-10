package edu.uwm.cs595.goup11.frontend.core.mesh

import android.os.Build
import androidx.annotation.RequiresApi
import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.NetworkState
import edu.uwm.cs595.goup11.backend.network.UserRole
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets


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

    private val _discovered = MutableSharedFlow<DiscoveredEventSummary>(extraBufferCapacity = 64)
    override val discoveredEvents: Flow<DiscoveredEventSummary> = _discovered.asSharedFlow()

    private val _chat = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 256)
    override val chat: Flow<ChatMessage> = _chat.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 500)
    override val logs: Flow<String> = _logs.asSharedFlow()

    private val _connectedPeers = MutableStateFlow<List<GatewayPeer>>(emptyList())
    override val connectedPeers: StateFlow<List<GatewayPeer>> = _connectedPeers.asStateFlow()

    // ------------------ Internal Session Tracking ------------------

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
    private var scanJob: Job? = null
    private val seenSessionIds = mutableSetOf<String>()

    private val customItinerary = mutableListOf<ItineraryItem>()

    override fun setDisplayName(name: String) {
        backend.setDisplayName(name)
        log("Display name set to: $name")
    }

    override suspend fun start() {
        if (started) return
        started = true

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
                    is NetworkState.Joining -> MeshUiState.Joining("")
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
                        val text = event.message.data?.toString(StandardCharsets.UTF_8).orEmpty()
                        log("Message from ${event.message.from}: $text")
                    }
                    else -> Unit
                }
            }
        }

        backend.addMessageListener(::onBackendMessage)
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
        seenSessionIds.clear()
        _state.value = MeshUiState.Scanning

        if (scanJob?.isActive == true) return

        val discoveredFlow = backend.scanNetworks()
        scanJob = scope.launch {
            discoveredFlow.collect { sessionId ->
                if (!seenSessionIds.add(sessionId)) return@collect

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

    override suspend fun stopScanning() {
        log("Stop scanning requested")
        backend.stopScan()
        scanJob?.cancel()
        scanJob = null

        if (currentEventName == null) {
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

    override suspend fun joinEvent(sessionId: String): JoinedEventBundle {
        log("Joining event: $sessionId")

        // Handle the case where we are already in the session (e.g. as host)
        // Check currentEventName synchronously first as _state.value is updated asynchronously via flow collection.
        if (currentEventName == sessionId) {
            log("Already in session $sessionId, returning bundle immediately.")
            return createJoinedEventBundle(sessionId)
        }

        val currentState = _state.value
        if ((currentState is MeshUiState.Hosting && currentState.sessionId == sessionId) ||
            (currentState is MeshUiState.InEvent && currentState.sessionId == sessionId)) {
            log("Already in event $sessionId, returning bundle immediately.")
            return createJoinedEventBundle(sessionId)
        }

        @Suppress("DEPRECATION")
        _state.value = MeshUiState.Joining(sessionId)

        currentEventName = sessionId

        backend.joinNetwork(sessionId)
        log("Joined network: $sessionId")

        return createJoinedEventBundle(sessionId)
    }

    private fun createJoinedEventBundle(sessionId: String): JoinedEventBundle {
        return JoinedEventBundle(
            sessionId = sessionId,
            title = sessionId,
            venue = "Venue (host broadcast later)",
            description = "Joined via mesh. Chat is live; event metadata can be synced later.",
            itinerary = customItinerary
        )
    }

    override suspend fun leaveEvent() {
        log("Leaving event")
        backend.removeMessageListener(::onBackendMessage)
        backend.stopScan()
        scanJob?.cancel()
        scanJob = null
        seenSessionIds.clear()
        currentEventName = null
        _connectedPeers.value = emptyList()
        backend.leave()
        _state.value = MeshUiState.Idle
        backend.addMessageListener(::onBackendMessage) // re-register for next session
    }

    override suspend fun sendChat(text: String) {
        val sessionId = currentEventName ?: return // not in an event, discard

        _chat.tryEmit(
            ChatMessage(
                sessionId   = sessionId,
                sender      = backend.myId,
                senderRole  = backend.myRole,
                text        = text,
                timestampMs = System.currentTimeMillis(),
                isMine      = true
            )
        )
        log("Sending chat: $text")

        // to is left empty — the topology's resolveNextHop floods to all
        // connected peers, so every node in the event receives the message.
        val msg = Message(
            to   = "",
            from = backend.myId,
            type = MessageType.TEXT_MESSAGE,
            data = text.toByteArray(StandardCharsets.UTF_8),
            ttl  = 5
        )

        backend.sendMessage("", msg)
    }

    override suspend fun sendDirectMessage(toEncodedName: String, text: String) {
        val sessionId = currentEventName ?: return

        _chat.tryEmit(
            ChatMessage(
                sessionId   = sessionId,
                sender      = backend.myId,
                senderRole  = backend.myRole,
                text        = text,
                timestampMs = System.currentTimeMillis(),
                isMine      = true
            )
        )

        val msg = Message(
            to   = toEncodedName,
            from = backend.myId,
            type = MessageType.TEXT_MESSAGE,
            data = text.toByteArray(StandardCharsets.UTF_8),
            ttl  = 5
        )
        backend.sendMessage(toEncodedName, msg)
        log("Direct message to ${toEncodedName}: $text")
    }

    private fun onBackendMessage(msg: Message) {
        when (msg.type) {
            MessageType.TEXT_MESSAGE -> {
                val sessionId = currentEventName ?: "unknown"
                val text = msg.data?.toString(StandardCharsets.UTF_8).orEmpty()
                log("Received chat from ${msg.from}: $text")

                _chat.tryEmit(
                    ChatMessage(
                        sessionId  = sessionId,
                        sender     = msg.from,
                        senderRole = UserRole.ATTENDEE, // role not carried in network Message
                        text       = text,
                        timestampMs = System.currentTimeMillis(),
                        isMine     = (msg.from == backend.myId)
                    )
                )
            }

            else -> Unit
        }
    }

    private fun log(message: String) {
        val time = System.currentTimeMillis() % 100000 // short timestamp
        _logs.tryEmit("[$time] $message")
        logger.debug { "[$time] $message" }
    }

    override suspend fun addItineraryItem(item: ItineraryItem) {
        customItinerary.add(item)
        log("Presentation added to local memory: ${item.title}")
    }
}