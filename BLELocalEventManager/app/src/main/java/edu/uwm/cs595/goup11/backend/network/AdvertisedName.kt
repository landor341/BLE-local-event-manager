package edu.uwm.cs595.goup11.backend.network

import edu.uwm.cs595.goup11.backend.network.topology.TopologyStrategy

/**
 * The decoded contents of an advertised endpoint name string.
 *
 * In Google Nearby Connections, when a device advertises itself it provides a plain
 * string name. We encode all the metadata we need into that string so that any
 * discovering device knows exactly what it is connecting to before the connection
 * is established.
 *
 * Encoded format: EVT:<eventName>|TOP:<topoCode>|TYP:<roleChar>|N:<displayName>
 *
 * Layer responsibilities:
 *  - Network layer  → passes the raw encoded String, never parses it
 *  - Client         → decodes it into AdvertisedName at the boundary
 *  - Topology       → receives AdvertisedName, never the raw string
 *
 * This is the ONLY class that should encode or decode that string.
 */
data class AdvertisedName(
    /** The name of the event this node belongs to e.g. "TechConf2024" */
    val eventName: String,

    /** Short topology code e.g. "snk", "hub", "msh" */
    val topologyCode: String,

    /** The role this node is currently playing in the topology */
    val role: TopologyStrategy.Role,

    /** Human-readable display name of this node */
    val displayName: String
) {
    /**
     * Encodes this into the advertised name string.
     * This is what gets passed to network.startAdvertising().
     */
    fun encode(): String =
        "EVT:$eventName|TOP:$topologyCode|TYP:${role.toChar()}|N:$displayName"

    companion object {
        private val REGEX = Regex("""EVT:([^|]+)\|TOP:([^|]+)\|TYP:([^|]+)\|N:(.+)""")
        private val VALID_TOPOLOGIES = setOf("snk", "hub", "msh")

        /**
         * Decodes a raw advertised name string into an [AdvertisedName].
         *
         * Returns null if the string is not in the expected format rather than
         * throwing — callers should treat a null result as "not our app" and
         * ignore the endpoint during discovery.
         */
        fun decode(raw: String): AdvertisedName? {
            val match = REGEX.matchEntire(raw) ?: return null
            val (eventName, topologyCode, roleChar, displayName) = match.destructured
            if (topologyCode !in VALID_TOPOLOGIES) return null
            val role = TopologyStrategy.Role.fromChar(roleChar) ?: return null
            return AdvertisedName(eventName, topologyCode, role, displayName)
        }
    }
}

// ---------------------------------------------------------------------------
// Role <-> single-char mapping
// Defined here so the mapping lives in exactly one place.
// ---------------------------------------------------------------------------

fun TopologyStrategy.Role.toChar(): String = when (this) {
    TopologyStrategy.Role.ROUTER -> "r"
    TopologyStrategy.Role.LEAF -> "l"
    TopologyStrategy.Role.PEER -> "p"
}

fun TopologyStrategy.Role.Companion.fromChar(c: String): TopologyStrategy.Role? = when (c) {
    "r" -> TopologyStrategy.Role.ROUTER
    "l" -> TopologyStrategy.Role.LEAF
    "p" -> TopologyStrategy.Role.PEER
    else -> null
}
