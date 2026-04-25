package edu.uwm.cs595.goup11.backend.network

enum class MessageType {
    /**
     * Test Description
     */
    HELLO,

    /*
     * Routing / mesh control
     */

    /**
     * Confirms that the previous message was received
     */
    ACK,

    /**
     * Delivery failed
     */
    NACK,

    /**
     * Heartbeat. Used between routers to verify status
     */
    PING,

    /**
     * Return status from PING
     */
    PONG,


    /*
     * ATTACHING TO ROUTER
    */

    /**
     * Sent from router after a leaf attempts to connect of it. Message includes router connections
     */
    ROUTER_STATUS,

    /**
     * Sent from leaf informing router it will connect to it
     */
    ATTACH,

    /**
     * Sent from router telling leaf that it has attached successfully
     */
    ATTACH_OK,

    /**
     * Request to join network
     */
    JOIN,

    /**
     * Sent to router to disconnect from router
     */
    DISCONNECT,

    /**
     * Send directly after connecting to peer. Returns metadata on the peer.
     */
    HANDSHAKE,

    /*
     * DIRECTORY
     */

    /**
     * Sent on endpoint connected. Contains the sender's full peer directory.
     */
    DIRECTORY_SYNC,

    /**
     * Response to DIRECTORY_SYNC. Contains the responder's full peer directory.
     */
    DIRECTORY_SYNC_ACK,

    /**
     * Broadcast to all neighbors when a new peer is discovered via directory merge.
     */
    DIRECTORY_PEER_ADDED,

    /**
     * Broadcast to all neighbors when a peer disconnects. Entry is tombstoned, not deleted.
     */
    DIRECTORY_PEER_DISCONNECTED,

    /**
     * Sent to the least-recently-verified peer. Contains a hash of the sender's directory.
     */
    DIRECTORY_VERIFY,

    /**
     * Response to DIRECTORY_VERIFY. Contains OK status, or MISMATCH + full directory.
     */
    DIRECTORY_VERIFY_ACK,

    /*
      Presentation info
     */

    /**
     * Sent to new user to sync their list of presentations
     */
    PRESENTATION_SYNC,

    /**
     * Sent to all users when a presentation is added
     */
    PRESENTATION_ADDED,

    /**
     * Sent to all users when a presentation ended
     */
    PRESENTATION_ENDED,

    /**
     * Sent to all users that a presentation info has been updated
     */
    PRESENTATION_UPDATED,

    /**
     * Sent when presentation owners want to give info or alerts about presentation
     */
    PRESENTATION_ALERT,

    /*
    Security Mesages
     */

    /**
     * Public key exchange
     */
    KEY_EXCHANGE,

    /**
     * Sent after key exchange to verify message is ready
     */
    SESSION_ESTABLISHED,


    /*
     * Basic Messages
     */
    TEXT_MESSAGE,


    /*
     * Data sync messages (used for custom sync)
     */


    DATA_SYNC,

    DATA_SYNC_ACK,

    DATA_UPDATE_REMOVE,

    DATA_UPDATE_ADD,

    DATA_UPDATE_MOD


}