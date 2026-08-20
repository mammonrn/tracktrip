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

        assertEquals(6.0, posts, 0.01)
        assertTrue("cadence must not exceed the backend cap", posts < cap)
        // Not merely under it — comfortably under. The ceiling exists to catch
        // a client stuck in a retry loop, and it cannot do that if the normal
        // cadence is already brushing it. This is the assertion that forced
        // the backend ceiling up from 10 to 30 when the interval came down
        // from 45s to 10s: at 6 posts a minute against 10, the headroom would
        // have been 1.67x and the first thing the limiter caught would have
        // been a real rider's real report.
        assertTrue("want at least 3x headroom, have ${cap / posts}", cap / posts >= 3.0)
    }

    @Test
    fun `the interval is in the band it was chosen from`() {
        // Ten seconds is about 220 metres at 80 km/h — close enough that a
        // friend's pin is a street behind them rather than a village. Below
        // about five seconds the GPS wakeups stop being worth the metres, and
        // the backend ceiling would need moving again.
        assertTrue(ReportCadence.INTERVAL_MS in 5_000L..30_000L)
    }

    @Test
    fun `a slow fix delays its own report and nothing after it`() {
        // The invariant that replaced "the fix budget must fit inside two
        // intervals". That rule was a proxy for this one, and at a 10-second
        // interval the proxy and the thing it stood for came apart: a budget
        // under 20s would throw away exactly the cold fixes a phone in a tank
        // bag produces (see FIX_TIMEOUT_MS), while the real protection lives
        // in nextDelayMillis, which never lets a cycle be shorter than the
        // interval however long the fix took.
        val interval = ReportCadence.INTERVAL_MS
        for (fixMs in listOf(0L, 500L, interval / 2, interval, interval * 3, ReportCadence.FIX_TIMEOUT_MS)) {
            val period = fixMs + nextDelayMillis(
                nowMillis = 0L,
                expiresAtMillis = null,
                intervalMillis = interval,
                elapsedMillis = fixMs,
            )
            assertTrue(
                "a fix taking ${fixMs}ms produced a ${period}ms period, shorter than the interval",
                period >= interval,
            )
        }
    }

    @Test
    fun `the fix budget survives a cold receiver even though it outlasts the interval`() {
        // Deliberately longer than the interval now, which would have failed
        // the old two-interval rule. See FIX_TIMEOUT_MS for why that is the
        // right way round: a budget is what decides whether a slow fix reports
        // at all, not what paces the ones that are fast.
        assertTrue(ReportCadence.FIX_TIMEOUT_MS > ReportCadence.INTERVAL_MS)
        assertTrue(ReportCadence.FIX_TIMEOUT_MS >= 45_000L)
    }

    @Test
    fun `even a phone that never gets a fix stays inside the cap`() {
        // The worst case for request rate is the fastest possible cycle, which
        // is a fix that returns instantly. The worst case for *reports* is a
        // fix that always times out — one report per 55 seconds. Both have to
        // be under the ceiling.
        val fastest = 60_000.0 / ReportCadence.INTERVAL_MS
        val slowest = 60_000.0 / ReportCadence.FIX_TIMEOUT_MS

        assertTrue(fastest < ReportCadence.BACKEND_MAX_POSTS_PER_MINUTE)
        assertTrue(slowest < ReportCadence.BACKEND_MAX_POSTS_PER_MINUTE)
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
