package edu.uwm.cs595.goup11.frontend.core.mesh

import edu.uwm.cs595.goup11.backend.network.Message
import edu.uwm.cs595.goup11.backend.network.MessageType
import edu.uwm.cs595.goup11.backend.network.NetworkEvent
import edu.uwm.cs595.goup11.backend.network.NetworkState
import edu.uwm.cs595.goup11.backend.network.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val _state = MutableStateFlow<MeshUiState>(MeshUiState.Idle)
    override val state: StateFlow<MeshUiState> = _state.asStateFlow()

    private val _discovered = MutableSharedFlow<DiscoveredEventSummary>(extraBufferCapacity = 64)
    override val discoveredEvents: Flow<DiscoveredEventSummary> = _discovered.asSharedFlow()

    private val _chat = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 256)
    override val chat: Flow<ChatMessage> = _chat.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 500)
    override val logs: Flow<String> = _logs.asSharedFlow()

    private var router: Peer? = null
    private var started: Boolean = false
    private var scanJob: Job? = null
    private val seenSessionIds = mutableSetOf<String>()

    override suspend fun start() {
        if (started) return
        started = true

        backend.start()
        log("Gateway started. ID: $myId")

        scope.launch {
            backend.state.collect { s ->
                log("Backend state: ${s::class.simpleName}")
                _state.value = when (s) {
                    is NetworkState.Idle -> MeshUiState.Idle
                    is NetworkState.Scanning -> MeshUiState.Scanning
                    is NetworkState.Joining -> MeshUiState.Joining(s.sessionId)
                    is NetworkState.Joined -> MeshUiState.InEvent(s.sessionId)
                    is NetworkState.Hosting -> MeshUiState.Hosting(s.sessionId)
                    is NetworkState.Error -> MeshUiState.Error(s.reason)
                }
            }
        }

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

        if (router == null) {
            _state.value = MeshUiState.Idle
        }
    }

    override suspend fun hostEvent(eventName: String) {
        log("Hosting event: $eventName")
        backend.createNetwork(eventName)
    }

    override suspend fun joinEvent(sessionId: String): JoinedEventBundle {
        log("Joining event: $sessionId")
        _state.value = MeshUiState.Joining(sessionId)

        router = backend.joinNetwork(sessionId)
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

    override suspend fun leaveEvent() {
        log("Leaving event")
        backend.stopScan()
        scanJob?.cancel()
        scanJob = null
        seenSessionIds.clear()
        router = null
        backend.leave()
        _state.value = MeshUiState.Idle
    }

    override suspend fun sendChat(text: String) {
        val r = router ?: return
        val sessionId = backend.currentSessionId.value ?: "unknown"

        _chat.tryEmit(
            ChatMessage(
                sessionId = sessionId,
                sender = backend.myId,
                senderRole = backend.myRole,
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
            ttl = 5,
            senderRole = backend.myRole
        )

        backend.sendMessage(r.endpointId, msg)
    }

    private fun onBackendMessage(msg: Message) {
        when (msg.type) {
            MessageType.TEXT_MESSAGE -> {
                if (msg.from == backend.myId) return

                val sessionId = backend.currentSessionId.value ?: "unknown"
                val text = msg.data?.toString(StandardCharsets.UTF_8).orEmpty()
                log("Received chat from ${msg.from}: $text")

                _chat.tryEmit(
                    ChatMessage(
                        sessionId = sessionId,
                        sender = msg.from,
                        senderRole = msg.senderRole,
                        text = text,
                        timestampMs = System.currentTimeMillis(),
                        isMine = false
                    )
                )
            }

            else -> Unit
        }
    }

    private fun log(message: String) {
        val time = System.currentTimeMillis() % 100000 // short timestamp
        _logs.tryEmit("[$time] $message")
    }
}