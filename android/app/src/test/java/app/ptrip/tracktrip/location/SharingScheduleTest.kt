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

    @Test
    fun `the cycle's own duration is subtracted, so the cadence is a period`() {
        // The loop acquires a fix and posts it before sleeping. Sleeping the
        // full interval afterwards made the real spacing "interval plus
        // however long the sky took" — invisible at ten minutes, most of a
        // cycle at forty-five seconds.
        val interval = 45_000L

        assertEquals(45_000L, nextDelayMillis(now(), null, interval, elapsedMillis = 0L))
        assertEquals(40_000L, nextDelayMillis(now(), null, interval, elapsedMillis = 5_000L))
        assertEquals(20_000L, nextDelayMillis(now(), null, interval, elapsedMillis = 25_000L))
    }

    @Test
    fun `a cycle that overran its interval asks to go again at once`() {
        // Zero here means "already late", not "stop" — only hasExpired ends
        // the loop, which is why the service no longer breaks on a zero wait.
        val interval = 45_000L

        assertEquals(0L, nextDelayMillis(now(), null, interval, elapsedMillis = interval))
        assertEquals(0L, nextDelayMillis(now(), null, interval, elapsedMillis = 90_000L))
    }

    @Test
    fun `expiry still wins over the remaining budget`() {
        val interval = 45_000L
        val now = now()

        // Ten seconds left and a cycle that took five: wait the ten, not the
        // forty that the cadence alone would have asked for.
        assertEquals(10_000L, nextDelayMillis(now, now + 10_000L, interval, elapsedMillis = 5_000L))
        // And a budget smaller than what is left still caps the wait.
        assertEquals(5_000L, nextDelayMillis(now, now + 30_000L, interval, elapsedMillis = 40_000L))
    }

    @Test
    fun `omitting the elapsed time behaves exactly as before`() {
        // Every existing caller passed three arguments; the default has to be
        // a no-op or this change would have moved the cadence twice.
        val interval = 60_000L
        val now = now()

        assertEquals(
            nextDelayMillis(now, null, interval),
            nextDelayMillis(now, null, interval, elapsedMillis = 0L),
        )
        assertEquals(
            nextDelayMillis(now, now + 20_000L, interval),
            nextDelayMillis(now, now + 20_000L, interval, elapsedMillis = 0L),
        )
    }
}
