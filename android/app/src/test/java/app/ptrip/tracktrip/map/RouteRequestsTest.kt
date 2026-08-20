package app.ptrip.tracktrip.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test that guards the routing quota.
 *
 * Routing runs on the same LocationIQ free tier as place search — 5,000
 * requests a day for the whole server — and the map that asks for it polls
 * every twenty seconds. A route asked for on every poll is about 180 requests
 * an hour per rider watching a map; four riders on one trip would empty the
 * day before lunch, on a line between two coordinates that had not moved.
 *
 * The same shape of test as PlaceSearchTest, and for the same reason.
 */
class RouteRequestsTest {

    private val chiangMai = LatLng(18.7883, 98.9853)
    private val pai = LatLng(19.3583, 98.4406)
    private val maeHongSon = LatLng(19.3011, 97.9654)

    private val trip = chiangMai to pai

    @Test
    fun `the first ask for a pair of ends is worth a request`() {
        assertTrue(RouteRequests.shouldFetch(trip, fetchedFor = null, retryAtMs = null, nowMs = 0))
    }

    @Test
    fun `asking again for the same two ends is not`() {
        // This is the line that keeps the poll off the upstream.
        assertFalse(RouteRequests.shouldFetch(trip, fetchedFor = trip, retryAtMs = null, nowMs = 0))
    }

    @Test
    fun `a hundred polls with an unchanged route cost nothing`() {
        var requests = 0
        var fetchedFor: Pair<LatLng, LatLng>? = null
        repeat(100) { poll ->
            if (RouteRequests.shouldFetch(trip, fetchedFor, null, poll * 20_000L)) {
                requests += 1
                fetchedFor = trip
            }
        }
        assertEquals(1L, requests.toLong())
    }

    @Test
    fun `moving the finish is worth a request`() {
        val moved = chiangMai to maeHongSon
        assertTrue(RouteRequests.shouldFetch(moved, fetchedFor = trip, retryAtMs = null, nowMs = 0))
    }

    @Test
    fun `moving the start is worth a request too`() {
        val moved = maeHongSon to pai
        assertTrue(RouteRequests.shouldFetch(moved, fetchedFor = trip, retryAtMs = null, nowMs = 0))
    }

    @Test
    fun `a trip with only one end asks for nothing`() {
        // No destination set yet, or the owner cleared one. There is nothing
        // to route between, and the map draws no line.
        assertFalse(RouteRequests.shouldFetch(null, fetchedFor = null, retryAtMs = null, nowMs = 0))
        assertFalse(RouteRequests.shouldFetch(null, fetchedFor = trip, retryAtMs = null, nowMs = 0))
    }

    @Test
    fun `a failure is not retried on the next poll`() {
        val failedAt = 1_000_000L
        val retryAt = failedAt + RouteRequests.RETRY_AFTER_MS

        // The poll twenty seconds later, and the one a minute later.
        assertFalse(RouteRequests.shouldFetch(trip, trip, retryAt, failedAt + 20_000L))
        assertFalse(RouteRequests.shouldFetch(trip, trip, retryAt, failedAt + 60_000L))
    }

    @Test
    fun `a failure is not permanent either`() {
        // A phone in a valley when the map opened must not go the whole ride
        // without a route.
        val failedAt = 1_000_000L
        val retryAt = failedAt + RouteRequests.RETRY_AFTER_MS

        assertTrue(RouteRequests.shouldFetch(trip, trip, retryAt, retryAt))
        assertTrue(RouteRequests.shouldFetch(trip, trip, retryAt, retryAt + 1))
    }

    @Test
    fun `a server that answers 503 for ever costs twelve requests an hour, not one hundred and eighty`() {
        var requests = 0
        var fetchedFor: Pair<LatLng, LatLng>? = null
        var retryAt: Long? = null

        // An hour of polling every twenty seconds, with every attempt failing.
        for (poll in 0 until 180) {
            val nowMs = poll * 20_000L
            if (RouteRequests.shouldFetch(trip, fetchedFor, retryAt, nowMs)) {
                requests += 1
                fetchedFor = trip
                retryAt = nowMs + RouteRequests.RETRY_AFTER_MS
            }
        }

        assertEquals(12L, requests.toLong())
    }

    @Test
    fun `moving an end after a failure asks at once rather than waiting out the backoff`() {
        // The rider did something. Making them wait five minutes to see the
        // result of setting a destination would read as the feature being
        // broken.
        val retryAt = 1_000_000L + RouteRequests.RETRY_AFTER_MS
        val moved = chiangMai to maeHongSon

        assertTrue(RouteRequests.shouldFetch(moved, trip, retryAt, 1_020_000L))
    }
}
