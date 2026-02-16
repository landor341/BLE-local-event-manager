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

    /*
     * DIRECTORY
     */

    /**
     * Sent via router to leaf to inform leaf of all users in network
     */
    DIRECTORY_SNAPSHOT,

    /**
     * When sent, will request a snapshot of all users from the nearest router
     */
    REQUEST_SNAPSHOT,

    /**
     * Sent from ROUTER ONLY. When sent, this message should propagate to every node and each
     * node should return a [REQUEST_DIRECTORY_UPDATE_ALL_RESPONSE]
     */
    REQUEST_DIRECTORY_UPDATE_ALL,

    /**
     * Sent in response to [REQUEST_DIRECTORY_UPDATE_ALL]
     */
    REQUEST_DIRECTORY_UPDATE_ALL_RESPONSE,

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




}