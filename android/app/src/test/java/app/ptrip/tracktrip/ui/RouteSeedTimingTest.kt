package app.ptrip.tracktrip.ui

import android.app.Application
import app.ptrip.tracktrip.data.ApiClient
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.RiderLevel
import app.ptrip.tracktrip.data.TokenStore
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.data.TripWaypoints
import app.ptrip.tracktrip.data.Waypoint
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the route list shows *while* it is being filled in.
 *
 * ## Why this exists
 *
 * Opening Edit trip on a trip with a route on it flashed "Choose a starting
 * point" and "Choose where you're going" — the two hints an empty draft draws
 * — and held them until the server answered. Nothing was wrong with the trip
 * and nothing had been lost: `openRouteSetup` awaited `loadWaypoints()` before
 * it seeded anything, and every way into the screen arrives just after the
 * draft was cleared, so the empty draft was what a rider looked at for the
 * length of a round trip. On a slow connection it read as the route having
 * disappeared — the same report, from the same screen, one release after the
 * real one.
 *
 * The fix is a seed that does not wait, so this is a test about *when* rather
 * than *what*: it holds the waypoints fetch open and looks at the draft while
 * it is still in flight. `RouteSetupTest` covers what the seeds contain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteSeedTimingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun releaseDispatcher() = Dispatchers.resetMain()

    private val home = TripEndpoint(18.7883, 98.9853, "Home")
    private val pai = TripEndpoint(19.3583, 98.4400, "Pai")

    private val trip = Trip(
        id = 1L,
        name = "Sunday run",
        ownerId = 7L,
        status = Trip.STATUS_ACTIVE,
        role = Trip.ROLE_OWNER,
        origin = home,
        destination = pai,
    )

    private val lampang = Waypoint(
        id = 5L,
        name = "Lampang",
        lat = 18.2888,
        lng = 99.4908,
        type = Waypoint.TYPE_PLANNED,
        orderIndex = 0,
        addedBy = 7L,
    )

    /**
     * A server that answers the trip at once and the waypoints when told to.
     *
     * The reads a view model makes on the way in, and no more: everything else
     * inherits the real implementation, which would fail as an ordinary
     * `ApiException` here and is caught wherever it is called.
     */
    private class HeldWaypoints(
        private val trip: Trip,
        private val held: CompletableDeferred<TripWaypoints>,
    ) : TripApi(
        ApiClient(
            baseUrl = "http://127.0.0.1:1/",
            tokenStore = TokenStore(ApplicationProvider.getApplicationContext<Application>()),
        )
    ) {
        override suspend fun listTrips(all: Boolean): List<Trip> = listOf(trip)
        override suspend fun members(tripId: Long): List<MemberPosition> = emptyList()
        override suspend fun memberLevels(tripId: Long): Map<Long, RiderLevel> = emptyMap()

        override suspend fun waypoints(tripId: Long): TripWaypoints = held.await()
    }

    @Test
    fun `the route is on screen before the waypoints arrive`() = runTest(dispatcher) {
        val held = CompletableDeferred<TripWaypoints>()
        val model = TripMapViewModel(
            tripId = 1L,
            tripApi = HeldWaypoints(trip, held),
            positionSocket = null,
            feedback = FeedbackCenter(),
            onSessionExpired = {},
        )

        // The trip lands; its stops do not, because the fetch is held open.
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(trip, model.uiState.value.trip)

        model.openRouteSetup()

        // No `advanceUntilIdle` — this is the point of the test. Nothing has
        // been allowed to run since the call, and the ends are already there.
        // Before the fix this read `RouteDraft()`, which is what draws "Choose
        // a starting point".
        val whileWaiting = model.uiState.value.routeDraft
        assertFalse("the draft was empty while the fetch was in flight", whileWaiting.isEmpty)
        assertEquals("Home", whileWaiting.from?.label)
        assertEquals("Pai", whileWaiting.to?.label)

        // And the fetch is still out — the seed above did not wait for it.
        assertFalse(held.isCompleted)

        held.complete(TripWaypoints(listOf(lampang)))
        testScheduler.advanceUntilIdle()

        // The stop the first seed could not know about arrives and is added.
        val settled = model.uiState.value.routeDraft
        assertEquals(listOf("Lampang"), settled.stops.map { it.label })
        assertEquals("Home", settled.from?.label)
    }

    @Test
    fun `a rider editing while the fetch is out is not overwritten`() = runTest(dispatcher) {
        val held = CompletableDeferred<TripWaypoints>()
        val model = TripMapViewModel(
            tripId = 1L,
            tripApi = HeldWaypoints(trip, held),
            positionSocket = null,
            feedback = FeedbackCenter(),
            onSessionExpired = {},
        )
        model.refresh()
        testScheduler.advanceUntilIdle()

        model.openRouteSetup()

        // A slow connection is long enough to tap a row and choose somewhere.
        model.pickRoutePoint(
            RouteField.TO,
            RoutePoint(app.ptrip.tracktrip.map.LatLng(18.2888, 99.4908), "Lampang"),
        )
        assertEquals("Lampang", model.uiState.value.routeDraft.to?.label)

        held.complete(TripWaypoints(listOf(lampang)))
        testScheduler.advanceUntilIdle()

        // Theirs, not the server's. Having a destination replaced a moment
        // after choosing it is worse than the stale list the fetch was fixing.
        assertEquals("Lampang", model.uiState.value.routeDraft.to?.label)
    }

    @Test
    fun `opening the list before the trip has loaded still fills in`() {
        runTest(dispatcher) {
            // The cold start the awaited fetch was there to protect, and the
            // one case the map screen shares with this one: the list is opened
            // before anything has been read, so the first seed has nothing to
            // work with and hands back an empty draft — which is correct, and
            // is what the old code showed for the whole round trip anyway.
            //
            // What fills it in is the screen asking again. Both screens seed
            // from `LaunchedEffect(trip?.id)`, so the key changes from null to
            // the id the moment the trip lands and the effect re-runs; the map
            // opens the card from a button a rider cannot press before the
            // screen has drawn. This models the first of those.
            val held = CompletableDeferred<TripWaypoints>()
            val model = TripMapViewModel(
                tripId = 1L,
                tripApi = HeldWaypoints(trip, held),
                positionSocket = null,
                feedback = FeedbackCenter(),
                onSessionExpired = {},
            )

            model.openRouteSetup()
            assertEquals(RouteDraft(), model.uiState.value.routeDraft)

            model.refresh()
            held.complete(TripWaypoints(listOf(lampang)))
            testScheduler.advanceUntilIdle()

            // The trip has arrived, so this is the effect re-running on it.
            model.openRouteSetup()
            testScheduler.advanceUntilIdle()

            val settled = model.uiState.value.routeDraft
            assertEquals("Home", settled.from?.label)
            assertEquals("Pai", settled.to?.label)
            assertEquals(listOf("Lampang"), settled.stops.map { it.label })
        }
    }
}
