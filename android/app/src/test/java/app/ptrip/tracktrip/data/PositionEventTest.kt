package app.ptrip.tracktrip.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The socket protocol, read from this side.
 *
 * The same grammar is described in `src/ws/protocol.js` and tested there. One
 * protocol, two languages, and nothing but a pair of test files keeping the
 * descriptions the same — so the frames below are written the way the server
 * writes them, verbatim, rather than built from a helper that could drift with
 * the parser it is testing.
 */
class PositionEventTest {

    @Test
    fun `the greeting names the account the socket belongs to`() {
        val event = parsePositionEvent("""{"type":"ready","user_id":42}""")

        assertEquals(PositionEvent.Ready(42L), event)
    }

    @Test
    fun `a position frame carries a member the map can use directly`() {
        val frame = """
            {"type":"position","trip_id":7,"position":{
              "user_id":42,"display_name":"Poom","username":"poom","role":"owner",
              "lat":18.79,"lng":98.98,"speed":22.2,"recorded_at":"2026-05-01T10:31:00Z",
              "is_sharing":true
            }}
        """.trimIndent()

        val event = parsePositionEvent(frame)

        assertTrue(event is PositionEvent.Position)
        val position = event as PositionEvent.Position
        assertEquals(7L, position.tripId)
        assertEquals(42L, position.member.userId)
        assertEquals(18.79, position.member.lat!!, 1e-9)
        assertEquals("2026-05-01T10:31:00Z", position.member.recordedAt)
    }

    @Test
    fun `a refusal is carried, not thrown`() {
        // A socket told "not a member of this trip" is still a working socket,
        // and the poll behind it is still authorised the ordinary way. The
        // screen decides what, if anything, to say.
        val event = parsePositionEvent("""{"type":"error","error":"not a member of this trip"}""")

        assertEquals(PositionEvent.Failed("not a member of this trip"), event)
    }

    @Test
    fun `acknowledgements the screen has no use for are recognised, not mistaken`() {
        assertEquals(PositionEvent.Subscribed(7L), parsePositionEvent("""{"type":"subscribed","trip_id":7}"""))
        assertEquals(PositionEvent.Unknown, parsePositionEvent("""{"type":"pong"}"""))
        assertEquals(PositionEvent.Unknown, parsePositionEvent("""{"type":"unsubscribed","trip_id":7}"""))
    }

    @Test
    fun `a message type from a newer server is ignored, never fatal`() {
        // A server can gain a frame before a phone in somebody's pocket learns
        // about it. Crashing on one would make every future addition a
        // breaking change.
        assertEquals(PositionEvent.Unknown, parsePositionEvent("""{"type":"weather","fog":true}"""))
        assertEquals(PositionEvent.Unknown, parsePositionEvent("""{"no_type_at_all":1}"""))
    }

    @Test
    fun `a frame that is not JSON is one frame to drop`() {
        // Everything arriving on a socket is somebody else's typing. A frame
        // that cannot be read must not tear down a connection carrying a ride.
        assertNull(parsePositionEvent("not json at all"))
        assertNull(parsePositionEvent(""))
    }

    @Test
    fun `a position frame with no position in it is not a position`() {
        assertEquals(PositionEvent.Unknown, parsePositionEvent("""{"type":"position","trip_id":7}"""))
    }

    @Test
    fun `the socket url is derived from the API's own`() {
        // One configured host, two schemes. Getting this wrong would mean an
        // app that talks to the right server over REST and to nowhere at all
        // over its socket.
        assertEquals("wss://api.ptrip.app/ws", PositionSocket.socketUrl("https://api.ptrip.app"))
        assertEquals("wss://api.ptrip.app/ws", PositionSocket.socketUrl("https://api.ptrip.app/"))
        assertEquals("ws://10.0.2.2:4100/ws", PositionSocket.socketUrl("http://10.0.2.2:4100"))
    }
}
