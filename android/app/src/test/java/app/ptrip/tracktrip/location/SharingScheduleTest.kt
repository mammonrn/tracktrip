package app.ptrip.tracktrip.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the location service stops.
 *
 * The service itself cannot be exercised here — it needs a real Android
 * runtime and a location provider — but this is the part that decides how long
 * a rider keeps broadcasting, which is the part worth being sure of.
 */
class SharingScheduleTest {

    private val minute = 60_000L
    private val interval = 10 * minute

    @Test
    fun `an open-ended session has no expiry`() {
        assertNull(parseExpiryMillis(null))
        assertNull(parseExpiryMillis(""))
    }

    @Test
    fun `the server's expiry is used verbatim`() {
        assertEquals(
            1_800_000_000_000L,
            parseExpiryMillis("2027-01-15T08:00:00Z"),
        )
    }

    @Test
    fun `an unreadable expiry reads as no expiry, not as unlimited`() {
        // parse returns null, and the caller treats a null from a *present*
        // value as "stop" rather than "run forever" — the safe direction when
        // the subject is broadcasting someone's location.
        assertNull(parseExpiryMillis("not a timestamp"))
        assertNull(parseExpiryMillis("2027-13-45"))
    }

    @Test
    fun `an open-ended session waits the full interval every time`() {
        assertEquals(interval, nextDelayMillis(now(), null, interval))
    }

    @Test
    fun `a session with plenty of time left waits the full interval`() {
        val now = now()
        assertEquals(interval, nextDelayMillis(now, now + 40 * minute, interval))
    }

    @Test
    fun `the last wait is shortened to land on the expiry`() {
        val now = now()
        // Four minutes left: stop in four, not in ten with one report from
        // after the session lapsed.
        assertEquals(4 * minute, nextDelayMillis(now, now + 4 * minute, interval))
    }

    @Test
    fun `a session already past its expiry waits not at all`() {
        val now = now()
        assertEquals(0L, nextDelayMillis(now, now - minute, interval))
        assertEquals(0L, nextDelayMillis(now, now, interval))
    }

    @Test
    fun `expiry is inclusive of the moment it lands on`() {
        val now = now()
        assertFalse(hasExpired(now, null))
        assertFalse(hasExpired(now, now + 1))
        assertTrue(hasExpired(now, now))
        assertTrue(hasExpired(now, now - 1))
    }

    private fun now(): Long = 1_800_000_000_000L
}
