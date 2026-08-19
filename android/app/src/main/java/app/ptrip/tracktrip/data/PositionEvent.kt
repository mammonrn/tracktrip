package app.ptrip.tracktrip.data

import org.json.JSONObject

/**
 * A frame the server sent down the socket.
 *
 * Parsed here, apart from the socket itself, so the grammar can be tested
 * without a network — the same reason `src/ws/protocol.js` is a separate file
 * on the other side. The two are one protocol described twice, in two
 * languages, and the tests either side are what keep the descriptions the
 * same.
 */
sealed interface PositionEvent {

    /** The greeting: the connection is authenticated and usable. */
    data class Ready(val userId: Long) : PositionEvent

    /** A subscription was accepted. */
    data class Subscribed(val tripId: Long) : PositionEvent

    /** One rider's stored fix, the moment the server stored it. */
    data class Position(val tripId: Long, val member: MemberPosition) : PositionEvent

    /**
     * The server refused something.
     *
     * Carried rather than thrown: a socket that is told "not a member of this
     * trip" is still a working socket, and the screen decides what, if
     * anything, to say about it.
     */
    data class Failed(val message: String) : PositionEvent

    /**
     * Something this build has no name for.
     *
     * A server can gain a message type before a phone in somebody's pocket
     * learns about it. Ignoring one is right; crashing on one would make every
     * future addition a breaking change.
     */
    data object Unknown : PositionEvent
}

/**
 * Reads one frame, or returns null if it is not JSON at all.
 *
 * Null rather than an exception: everything arriving on a socket is somebody
 * else's typing, and a frame that cannot be read is one frame to drop, not a
 * reason to tear down a connection that is otherwise carrying a ride.
 */
fun parsePositionEvent(raw: String): PositionEvent? {
    val json = try {
        JSONObject(raw)
    } catch (e: org.json.JSONException) {
        return null
    }

    return when (json.optStringOrNull("type")) {
        "ready" -> PositionEvent.Ready(json.optLong("user_id"))
        "subscribed" -> PositionEvent.Subscribed(json.optLong("trip_id"))
        "position" -> {
            val member = json.optJSONObject("position") ?: return PositionEvent.Unknown
            PositionEvent.Position(json.optLong("trip_id"), member.toMemberPosition())
        }
        "error" -> PositionEvent.Failed(json.optStringOrNull("error") ?: "socket error")
        // `pong` and `unsubscribed` land here on purpose: they are
        // acknowledgements the screen has no use for.
        else -> PositionEvent.Unknown
    }
}
