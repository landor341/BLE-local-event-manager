package edu.uwm.cs595.goup11.backend.network.topology

import edu.uwm.cs595.goup11.backend.network.AdvertisedName
import edu.uwm.cs595.goup11.backend.network.Message

/**
 * A peer as known by the topology layer.
 *
 * Created from a raw (endpointId, AdvertisedName) pair the moment a connection
 * is established. The topology layer never works with raw strings or the old
 * Peer class directly.
 *
 * Owns only what the topology actually needs:
 *  - The endpoint ID for sending messages via Network
 *  - The parsed identity (role, event, topology, display name) via AdvertisedName
 *  - Liveness state for keepalive tracking
 *  - A pending message queue for messages that arrive before the connection is fully ready
 */
data class TopologyPeer(
    /**
     * The Nearby Connections hardware-assigned endpoint ID for this peer.
     * Used as the transport address for all Network.sendMessage() calls.
     * In LocalNetwork this is the encoded advertised name (they are the same thing).
     */
    val hardwareId: String,

    /**
     * Parsed identity of this peer — decoded from the advertised name string
     * at connection time by Client, then passed into the topology.
     */
    val advertisedName: AdvertisedName,

    /**
     * Timestamp of the last PONG received from this peer.
     * Updated by the topology's onMessage handler whenever a PONG arrives.
     * Used by the keepalive loop to detect dead peers.
     */
    var lastPongAt: Long = System.currentTimeMillis(),

    /**
     * Messages queued while waiting for a connection to be fully ready.
     * Flushed once the peer is considered live.
     */
    val pendingMessages: ArrayDeque<Message> = ArrayDeque()
) {
    // Convenience accessors — so callers don't need to drill into advertisedName
    val role: TopologyStrategy.Role get() = advertisedName.role
    val displayName: String get() = advertisedName.displayName
    val eventName: String get() = advertisedName.eventName
    val topologyCode: String get() = advertisedName.topologyCode
}