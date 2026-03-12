// Destinations.kt
// Defines top-level navigation destinations for the app.

package edu.uwm.cs595.goup11.frontend.core.navigation

import kotlinx.serialization.Serializable


enum class Destinations {
    HOME,
    EXPLORE,
    EVENT_DETAIL,
    PROFILE,
    EDIT_PROFILE,
    CREATE_EVENT,
    CHAT
}

//sealed class will make it easier to pass information between screens when needed
@Serializable
sealed class SealedDestinations(val route: String) {
    @Serializable
    object HOME: SealedDestinations("HOME")
    @Serializable
    object EXPLORE: SealedDestinations("EXPLORE")
    @Serializable
    object EVENT_DETAIL: SealedDestinations("EVENT_DETAIL")
    @Serializable
    object PROFILE: SealedDestinations("PROFILE")
    @Serializable
    object EDIT_PROFILE : SealedDestinations("EDIT_PROFILE")
    @Serializable
    object CREATE_EVENT: SealedDestinations("CREATE_EVENT")
    @Serializable
    object CHAT: SealedDestinations("CHAT")
    @Serializable
    object INBOX: SealedDestinations("INBOX")
}