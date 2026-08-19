package app.ptrip.tracktrip.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reporting cadence against the constraints it has to satisfy.
 *
 * These are the checks that cannot be made by reading either side alone: the
 * ceiling lives in `src/routes/positions.js`, in another language, and the
 * cadence lives here. Nothing but this test connects them, so a future change
 * that quietly halves the interval fails here rather than in production as a
 * wall of `429 too many position updates`.
 */
class ReportCadenceTest {

    @Test
    fun `the cadence stays well inside the backend's rate limit`() {
        val posts = ReportCadence.postsPerMinute
        val cap = ReportCadence.BACKEND_MAX_POSTS_PER_MINUTE

        assertEquals(1.33, posts, 0.01)
        assertTrue("cadence must not exceed the backend cap", posts < cap)
        // Not merely under it — comfortably under. The ceiling exists to catch
        // a client stuck in a retry loop, and it cannot do that if the normal
        // cadence is already brushing it.
        assertTrue("want at least 3x headroom, have ${cap / posts}", cap / posts >= 3.0)
    }

    @Test
    fun `the interval is in the band it was chosen from`() {
        assertTrue(ReportCadence.INTERVAL_MS in 30_000L..60_000L)
    }

    @Test
    fun `a fix cannot be waited on for longer than the cycle it belongs to`() {
        // If the timeout could exceed the interval, one fix under a bridge
        // would swallow a whole cycle and every later report would run late.
        assertTrue(
            "fix timeout ${ReportCadence.FIX_TIMEOUT_MS} must leave room inside " +
                "${ReportCadence.INTERVAL_MS}",
            ReportCadence.FIX_TIMEOUT_MS < ReportCadence.INTERVAL_MS,
        )
    }

    @Test
    fun `a rider on two trips at once would still be inside the cap`() {
        // Nothing stops somebody being on a weekend ride during a longer tour.
        // Only one sharing session runs at a time today, but the ceiling should
        // survive that changing.
        assertTrue(ReportCadence.postsPerMinute * 2 < ReportCadence.BACKEND_MAX_POSTS_PER_MINUTE)
    }
}
