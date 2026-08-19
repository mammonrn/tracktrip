package app.ptrip.tracktrip.map

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How stale a rider's position is.
 *
 * The number itself is the whole feature: it is what tells a rider that a pin
 * which has not moved for three minutes is a phone reporting on its ten-minute
 * cadence rather than an app that has stopped working.
 */
class FixAgeTest {

    private val now = Instant.parse("2026-05-01T10:31:00Z").toEpochMilli()

    private fun at(iso: String) = FixAge.minutesAgo(iso, now)

    @Test
    fun `whole minutes since the fix was recorded`() {
        assertEquals(0L, at("2026-05-01T10:31:00Z"))
        assertEquals(3L, at("2026-05-01T10:28:00Z"))
        assertEquals(9L, at("2026-05-01T10:22:00Z"))
    }

    @Test
    fun `seconds round down rather than up`() {
        // 3 minutes 59 seconds is still "3 min ago" — a counter that rounded up
        // would show 4 while the phone's own clock still said 3.
        assertEquals(3L, at("2026-05-01T10:27:01Z"))
        assertEquals(0L, at("2026-05-01T10:30:01Z"))
    }

    @Test
    fun `a rider who has never reported has no age at all`() {
        // Null means "say nothing". Saying "0 min ago" about a rider with no
        // position would claim a freshness that does not exist.
        assertNull(FixAge.minutesAgo(null, now))
        assertNull(FixAge.minutesAgo("", now))
        assertNull(FixAge.minutesAgo("   ", now))
    }

    @Test
    fun `a timestamp this build cannot read is treated as no timestamp`() {
        assertNull(FixAge.minutesAgo("last tuesday", now))
        assertNull(FixAge.minutesAgo("2026-05-01 10:28:00", now))
    }

    @Test
    fun `a fix stamped in the future reads as now, not as negative`() {
        // The device clock and the server's disagree by a second or two often
        // enough; "-1 min ago" would be nonsense on screen.
        assertEquals(0L, at("2026-05-01T10:31:30Z"))
        assertEquals(0L, at("2026-05-01T10:45:00Z"))
    }

    @Test
    fun `an hours-old fix still answers in minutes`() {
        // The row has one unit and keeps it: a trip left open overnight should
        // read as a big number, not silently wrap.
        assertEquals(120L, at("2026-05-01T08:31:00Z"))
    }

    @Test
    fun `offsets are honoured, not ignored`() {
        // The server sends UTC, but a timestamp with an offset must not be
        // read as though the offset were not there.
        assertEquals(1L, FixAge.minutesAgo("2026-05-01T17:30:00+07:00", now))
    }
}
