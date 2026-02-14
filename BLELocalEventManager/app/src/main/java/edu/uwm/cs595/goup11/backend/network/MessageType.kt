package edu.uwm.cs595.goup11.backend.network

enum class MessageType {
    HELLO,
    ACK,
    JOIN,
    DISCONNECT,
    DIRECTORY_UPDATE,
    HEARTBEAT
}