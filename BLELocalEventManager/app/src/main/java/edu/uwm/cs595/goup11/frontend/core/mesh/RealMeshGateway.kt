package edu.uwm.cs595.goup11.frontend.core.mesh

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.NetworkState
import edu.uwm.cs595.goup11.backend.network.PresentationEntry
import edu.uwm.cs595.goup11.backend.network.PresentationStatus
import edu.uwm.cs595.goup11.backend.network.UserRole
import edu.uwm.cs595.goup11.frontend.domain.models.Presentation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class RealMeshGateway(
    private val backend: BackendFacade,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : MeshGateway {

    private var isLeaving = false

    override val myId: String
        get() = backend.myId

    private val logger = KotlinLogging.logger {}

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

    /**
     * Maps backend [PresentationEntry] → frontend [Presentation] UI model.
     * Only ACTIVE presentations are exposed — ENDED ones are filtered out.
     */
    override val presentations: StateFlow<List<Presentation>> =
        backend.presentations
            .map { entries ->
                entries
                    .filter { it.status == PresentationStatus.ACTIVE }
                    .map { it.toUiModel() }
            }
            .stateIn(
                scope   = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    private var currentEventName: String? = null
    private var started: Boolean = false
    private var scanJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private val seenSessionIds = mutableSetOf<String>()
    private val customItinerary = mutableListOf<ItineraryItem>()

    private val chatHistory = mutableListOf<ChatMessage>()

    override fun setDisplayName(name: String) {
        backend.setDisplayName(name)
        log("Display name set to: $name")
    }

    override fun getChatHistory(): List<ChatMessage> = synchronized(chatHistory) {
        chatHistory.toList()
    }

    override suspend fun start() {
        if (started) return
        started = true

        backend.start()
        log("Gateway started. ID: $myId")

        scope.launch {
            backend.state.collect { s ->
                log("collector fired: ${s::class.simpleName} isLeaving=$isLeaving")
                if (isLeaving) {
                    if (s is NetworkState.Idle) {
                        log("backend settled to Idle, clearing isLeaving")
                        isLeaving = false
                    }
                    return@collect
                }

                _state.value = when (s) {
                    is NetworkState.Idle     -> MeshUiState.Idle
                    is NetworkState.Scanning -> MeshUiState.Scanning
                    is NetworkState.Joining  -> MeshUiState.Scanning
                    is NetworkState.Joined   -> {
                        currentEventName = s.sessionId
                        MeshUiState.InEvent(s.sessionId)
                    }
                    is NetworkState.Hosting  -> {
                        currentEventName = s.sessionId
                        MeshUiState.InEvent(s.sessionId)
                    }
                    is NetworkState.Error    -> MeshUiState.Error(s.reason)
                    else                     -> MeshUiState.Error("Unsupported Event")
                }
            }
        }

        scope.launch {
            backend.networkPeers.collect { peers ->
                val localId = backend.localEncodedName
                _connectedPeers.value = peers
                    .filter { it.endpointId != localId }
                    .map { entry ->
                        GatewayPeer(
                            endpointId  = entry.endpointId,
                            displayName = entry.displayName,
                            encodedName = entry.endpointId
                        )
                    }
            }
        }

        scope.launch {
            backend.events.collect { event ->
                when (event) {
                    is NetworkEvent.Joined               -> log("Joined network: ${event.sessionId}")
                    is NetworkEvent.EndpointConnected    -> log("endpoint connected: ${event.encodedName}")
                    is NetworkEvent.EndpointDisconnected -> log("endpoint disconnected: ${event.endpointId}")
                    is NetworkEvent.MessageReceived      -> {
                        val text = event.message.data?.toString(StandardCharsets.UTF_8).orEmpty()
                        log("Message from ${event.message.from}: $text")
                    }
                    else -> Unit
                }
            }
        }

        backend.addMessageListener(::onBackendMessage)
    }

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
                        title     = sessionId,
                        venue     = "Nearby"
                    )
                )
            }
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(120_000)
            log("Scan timed out after 120 seconds")
            stopScanning()
        }
    }

    override suspend fun stopScanning() {
        log("Stop scanning requested")
        backend.stopScan()

        scanJob?.cancel()
        scanJob = null

        scanTimeoutJob?.cancel()
        scanTimeoutJob = null

        if (currentEventName == null) {
            _state.value = MeshUiState.Idle
        }
    }

    @Deprecated(
        "Defaults to SnakeTopology. Use hostEvent(eventName, topology) to specify.",
        ReplaceWith("hostEvent(eventName, TopologyChoice.SNAKE)")
    )
    override suspend fun hostEvent(eventName: String) {
        hostEvent(eventName, TopologyChoice.SNAKE)
    }

    override suspend fun hostEvent(eventName: String, topology: TopologyChoice) {
        log("Hosting event: $eventName (topology: ${topology.code})")
        currentEventName = eventName
        backend.createNetwork(eventName, topology)
    }

    override suspend fun joinEvent(sessionId: String): JoinedEventBundle {
        log("Joining event: $sessionId")

        if (currentEventName == sessionId) {
            log("Already in session $sessionId, returning bundle immediately.")
            return createJoinedEventBundle(sessionId)
        }

        val currentState = _state.value
        if (currentState is MeshUiState.InEvent && currentState.sessionId == sessionId) {
            log("Already InEvent $sessionId, returning bundle immediately.")
            return createJoinedEventBundle(sessionId)
        }

        _state.value = MeshUiState.Scanning

        try {
            backend.stopScan()
            scanJob?.cancel()
            scanJob = null
            scanTimeoutJob?.cancel()
            scanTimeoutJob = null
            seenSessionIds.clear()

            withTimeout(15_000) {
                backend.joinNetwork(sessionId)
            }

            currentEventName = sessionId
            _state.value = MeshUiState.InEvent(sessionId)
            log("Joined network: $sessionId")

            return createJoinedEventBundle(sessionId)
        } catch (e: Exception) {
            currentEventName = null
            log("Join failed for $sessionId: ${e.message}")
            _state.value = MeshUiState.Error(e.message ?: "Failed to join event.")
            throw e
        }
    }

    private fun createJoinedEventBundle(sessionId: String): JoinedEventBundle {
        return JoinedEventBundle(
            sessionId   = sessionId,
            title       = sessionId,
            venue       = "Venue (host broadcast later)",
            description = "Joined via mesh. Chat is live; event metadata can be synced later.",
            itinerary   = customItinerary
        )
    }

    override suspend fun leaveEvent() {
        log("Leaving event")
        isLeaving = true

        backend.removeMessageListener(::onBackendMessage)

        backend.stopScan()
        scanJob?.cancel()
        scanJob = null
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null

        seenSessionIds.clear()
        currentEventName = null
        _connectedPeers.value = emptyList()
        synchronized(chatHistory) { chatHistory.clear() }

        // Clear presentations on leave so stale data doesn't persist
        backend.clearPresentations()

        runCatching {
            backend.leave()
        }.onFailure { e ->
            log("Leave failed: ${e.message}")
        }

        _state.value = MeshUiState.Idle

        backend.addMessageListener(::onBackendMessage)
    }

    override suspend fun sendChat(text: String) {
        val sessionId = currentEventName
        if (sessionId == null) {
            log("Ignoring chat send: no active event")
            _state.value = MeshUiState.Error("You are not connected to an event.")
            return
        }

        val currentState = _state.value
        if (currentState !is MeshUiState.InEvent && currentState !is MeshUiState.Hosting) {
            log("Ignoring chat send: invalid state ${currentState::class.simpleName}")
            _state.value = MeshUiState.Error("You are not connected to an event.")
            return
        }

        val chatMessage = ChatMessage(
            sessionId   = sessionId,
            sender      = backend.myId,
            senderRole  = backend.myRole,
            text        = text,
            timestampMs = System.currentTimeMillis(),
            isMine      = true,
            isBroadcast = true
        )

        synchronized(chatHistory) { chatHistory.add(chatMessage) }
        _chat.tryEmit(chatMessage)
        log("Sending chat: $text")

        val msg = Message(
            to   = "ALL",
            from = backend.localEncodedName ?: backend.myId,
            type = MessageType.TEXT_MESSAGE,
            data = text.toByteArray(StandardCharsets.UTF_8),
            ttl  = 5
        )

        runCatching {
            backend.sendMessage("ALL", msg)
        }.onFailure { e ->
            log("Chat send failed: ${e.message}")
            _state.value = MeshUiState.Error("Failed to send message.")
        }
    }

    override suspend fun sendDirectMessage(toEncodedName: String, text: String) {
        val sessionId = currentEventName
        if (sessionId == null) {
            log("Ignoring direct message send: no active event")
            _state.value = MeshUiState.Error("You are not connected to an event.")
            return
        }

        val currentState = _state.value
        if (currentState !is MeshUiState.InEvent && currentState !is MeshUiState.Hosting) {
            log("Ignoring direct message send: invalid state ${currentState::class.simpleName}")
            _state.value = MeshUiState.Error("You are not connected to an event.")
            return
        }

        val chatMessage = ChatMessage(
            sessionId   = sessionId,
            sender      = backend.myId,
            senderRole  = backend.myRole,
            text        = text,
            timestampMs = System.currentTimeMillis(),
            isMine      = true,
            isBroadcast = false,
            recipientId = toEncodedName
        )

        synchronized(chatHistory) { chatHistory.add(chatMessage) }
        _chat.tryEmit(chatMessage)

        val msg = Message(
            to   = toEncodedName,
            from = backend.localEncodedName ?: backend.myId,
            type = MessageType.TEXT_MESSAGE,
            data = text.toByteArray(StandardCharsets.UTF_8),
            ttl  = 5
        )

        runCatching {
            backend.sendMessage(toEncodedName, msg)
        }.onFailure { e ->
            log("Direct message send failed: ${e.message}")
            _state.value = MeshUiState.Error("Failed to send direct message.")
        }

        log("Direct message to $toEncodedName: $text")
    }

    // ── Presentation API ──────────────────────────────────────────────────────

    override fun addPresentation(presentation: Presentation) {
        log("addPresentation: ${presentation.name}")
        backend.addPresentation(presentation.toBackendEntry())
    }

    override fun removePresentation(presentation: Presentation) {
        log("removePresentation: ${presentation.name}")
        backend.removePresentation(presentation.toBackendEntry())
    }

    override fun updatePresentation(presentation: Presentation) {
        log("updatePresentation: ${presentation.name}")
        backend.updatePresentation(presentation.toBackendEntry())
    }

    // ── Message handling ──────────────────────────────────────────────────────

    private fun onBackendMessage(msg: Message) {
        when (msg.type) {
            MessageType.TEXT_MESSAGE -> {
                val sessionId = currentEventName ?: "unknown"
                val text = msg.data?.toString(StandardCharsets.UTF_8).orEmpty()
                log("Received chat from ${msg.from}: $text")

                val isBroadcast = msg.to == "ALL"

                val chatMessage = ChatMessage(
                    sessionId   = sessionId,
                    sender      = msg.from,
                    senderName  = AdvertisedName.decode(msg.from)?.displayName ?: msg.from,
                    senderRole  = UserRole.ATTENDEE,
                    text        = text,
                    timestampMs = System.currentTimeMillis(),
                    isMine      = (msg.from == backend.localEncodedName ?: backend.myId),
                    isBroadcast = isBroadcast,
                    recipientId = if (isBroadcast) null else backend.myId
                )

                synchronized(chatHistory) { chatHistory.add(chatMessage) }
                _chat.tryEmit(chatMessage)
            }

            else -> Unit
        }
    }

    private fun log(message: String) {
        val time = System.currentTimeMillis() % 100000
        val formatted = "[$time] $message"
        _logs.tryEmit(formatted)
        android.util.Log.d("MeshGateway", formatted)
    }

    override suspend fun addItineraryItem(item: ItineraryItem) {
        customItinerary.add(item)
        log("Itinerary item added: ${item.title}")
    }

    // ── Model mapping helpers ─────────────────────────────────────────────────

    private fun PresentationEntry.toUiModel(): Presentation = Presentation(
        id                = id,
        name              = name,
        startTime         = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(startTime), ZoneOffset.UTC
        ),
        endTime           = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(endTime), ZoneOffset.UTC
        ),
        location          = location,
        speakerName       = speakerName,
        speakerEndpointId = speakerEndpointId,
        status            = status
    )

    private fun Presentation.toBackendEntry(): PresentationEntry = PresentationEntry(
        id                = id,
        name              = name,
        startTime         = startTime.toInstant(ZoneOffset.UTC).toEpochMilli(),
        endTime           = endTime.toInstant(ZoneOffset.UTC).toEpochMilli(),
        location          = location,
        speakerName       = speakerName,
        speakerEndpointId = speakerEndpointId,
        status            = status
    )
}