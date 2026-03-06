package edu.uwm.cs595.goup11.frontend.core.navigation

/**
 * Destinations / Routes
 *
 * RULES:
 * - Routes must be stable strings (do not rename casually).
 * - EventDetail requires a sessionId argument.
 */
object Destinations {
    const val HOME = "home"
    const val EXPLORE = "explore"
    const val PROFILE = "profile"
    const val CHAT = "chat"
    const val EVENT_DETAIL = "event_detail"
    const val EVENT_DETAIL_ROUTE = "$EVENT_DETAIL/{sessionId}"

    fun eventDetail(sessionId: String) = "$EVENT_DETAIL/$sessionId"
}