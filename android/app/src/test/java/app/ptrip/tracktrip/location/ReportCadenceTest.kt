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
    fun `a fix that never arrives cannot hold a cycle past the next one`() {
        // The bound that matters, and the only one. It used to be much
        // tighter — 25s inside a 45s interval — on the theory that a slow fix
        // would drag the cadence. It cannot: nextDelayMillis subtracts how
        // long the cycle took, so a slow fix delays its own report and
        // nothing after it. What the tight budget did instead was throw away
        // cold fixes, which are the ones a phone in a tank bag produces, and
        // report nothing at all on those cycles.
        assertTrue(
            "fix timeout ${ReportCadence.FIX_TIMEOUT_MS} must not exceed two intervals",
            ReportCadence.FIX_TIMEOUT_MS < ReportCadence.INTERVAL_MS * 2,
        )
    }

    @Test
    fun `the budget is long enough for a cold receiver`() {
        // A warm receiver answers in a second or two and was never at risk.
        // The fix worth protecting is the first one after the phone has been
        // asleep in a bag, which routinely takes half a minute outdoors.
        assertTrue(
            "a cold fix needs more than ${ReportCadence.FIX_TIMEOUT_MS}ms",
            ReportCadence.FIX_TIMEOUT_MS >= 45_000L,
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
