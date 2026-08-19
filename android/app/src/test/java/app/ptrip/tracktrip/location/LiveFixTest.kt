package app.ptrip.tracktrip.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which fix drives the speed readout, and how old it is.
 *
 * Both answers used to come from the wall clock, and both were wrong in ways
 * that read on screen as "the speedometer lags behind the bike" — see
 * [LiveFix] for the two clocks and why they disagree.
 */
class LiveFixTest {

    private val millis = 1_000_000L

    @Test
    fun `age is the gap on the monotonic clock`() {
        assertEquals(
            3_000L,
            LiveFix.ageMs(
                nowElapsedRealtimeNanos = 10_000 * millis,
                fixElapsedRealtimeNanos = 7_000 * millis,
            )
        )
    }

    @Test
    fun `a fix taken this instant is nought old, not nearly nought`() {
        assertEquals(0L, LiveFix.ageMs(500 * millis, 500 * millis))
    }

    @Test
    fun `sub-millisecond ages do not round up into staleness`() {
        // 999,999 ns is under a millisecond. Rounding it to 1 would be
        // harmless; rounding a whole feed of them upwards would not.
        assertEquals(0L, LiveFix.ageMs(500 * millis + 999_999L, 500 * millis))
    }

    @Test
    fun `an age can never come out negative`() {
        // Both readings are the same counter, so this only happens if a caller
        // passes them the wrong way round — and a negative age would look like
        // a fix from the future rather than like the mistake it is.
        assertEquals(0L, LiveFix.ageMs(7_000 * millis, 10_000 * millis))
    }

    @Test
    fun `the very first fix is always accepted`() {
        assertTrue(LiveFix.isNewer(shownElapsedRealtimeNanos = null, arrivingElapsedRealtimeNanos = 0L))
        assertTrue(LiveFix.isNewer(null, 42L))
    }

    @Test
    fun `a newer fix replaces the one on screen`() {
        assertTrue(LiveFix.isNewer(1_000 * millis, 1_001 * millis))
    }

    @Test
    fun `a fix from a batch flush is refused rather than shown`() {
        // A GNSS engine that has been batching delivers its queue oldest-first
        // *after* a newer fix has already arrived. Taking whatever came last
        // put a several-second-old speed on the top bar, which is exactly the
        // bug this guard exists for.
        assertFalse(LiveFix.isNewer(5_000 * millis, 2_000 * millis))
    }

    @Test
    fun `the same fix delivered twice does not count as news`() {
        assertFalse(LiveFix.isNewer(5_000 * millis, 5_000 * millis))
    }
}
