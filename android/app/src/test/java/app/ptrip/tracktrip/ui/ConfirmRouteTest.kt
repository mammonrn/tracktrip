package app.ptrip.tracktrip.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.ptrip.tracktrip.data.ApiClient
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.RiderLevel
import app.ptrip.tracktrip.data.TokenStore
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.data.TripWaypoints
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That confirming a route leaves the rider looking at the route they
 * confirmed.
 *
 * ## Why this exists
 *
 * A rider opened Edit trip on a trip that had a route on it, pressed
 * **Confirm route**, and watched the list go back to "Choose a starting point"
 * and "Choose where you're going" — the two hints an empty [RouteDraft] draws.
 * The same report as the name's Save one release earlier, from the same
 * screen, and the same shape of cause: one call doing two jobs for two screens
 * that only agree about the first of them.
 *
 * `confirmRoute` cleared the draft before it wrote, and clearing it is right
 * *over the map*, where the route list is a card that closes on confirm: a
 * draft left behind a closed card outranks the trip's own stops in
 * [TripMapUiState.drawnWaypoints], so the map would fly the rider's
 * abandoned pins for the rest of the ride.
 *
 * On Edit trip the list *is* the screen. Nothing closes, so clearing the draft
 * tidies nothing away — it blanks the rows the rider just pressed Confirm on.
 *
 * Nothing was ever lost on the server: the writes are a PATCH per end and a
 * POST per stop, and the route was there to be read back. The last case here
 * says so out loud — it asks the fake server what it holds after the press.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmRouteTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun releaseDispatcher() = Dispatchers.resetMain()

    private val home = TripEndpoint(18.7883, 98.9853, "Home")
    private val pai = TripEndpoint(19.3583, 98.4400, "Pai")
    private val lampang = LatLng(18.2888, 99.4908)

    private val trip = Trip(
        id = 1L,
        name = "Sunday run",
        ownerId = 7L,
        status = Trip.STATUS_ACTIVE,
        role = Trip.ROLE_OWNER,
        origin = home,
        destination = pai,
    )

    private val fuelStop = Waypoint(
        id = 5L,
        name = "Lampang",
        lat = lampang.lat,
        lng = lampang.lng,
        type = Waypoint.TYPE_PLANNED,
        orderIndex = 0,
        addedBy = 7L,
    )

    /**
     * A server that remembers what it was told.
     *
     * Small on purpose, and only the calls a confirm makes: the point of the
     * test is what a *re-read* answers once a write has landed, so a fake that
     * threw the write away and answered the read from a constant could not
     * tell the fix from the bug.
     */
    private class FakeServer(
        trip: Trip,
        stops: List<Waypoint> = emptyList(),
    ) : TripApi(
        ApiClient(
            baseUrl = "http://127.0.0.1:1/",
            tokenStore = TokenStore(ApplicationProvider.getApplicationContext<Application>()),
        )
    ) {
        var trip: Trip = trip
            private set
        var stops: List<Waypoint> = stops
            private set

        /** Every write, in order, so a case can assert what was *not* sent. */
        val writes = mutableListOf<String>()

        private var nextId = 100L

        override suspend fun listTrips(all: Boolean): List<Trip> = listOf(trip)
        override suspend fun members(tripId: Long): List<MemberPosition> = emptyList()
        override suspend fun memberLevels(tripId: Long): Map<Long, RiderLevel> = emptyMap()
        override suspend fun waypoints(tripId: Long): TripWaypoints = TripWaypoints(planned = stops)

        override suspend fun setOrigin(tripId: Long, endpoint: TripEndpoint?): Trip {
            writes += "origin:${endpoint?.label}"
            trip = trip.copy(origin = endpoint)
            return trip
        }

        override suspend fun setDestination(tripId: Long, endpoint: TripEndpoint?): Trip {
            writes += "destination:${endpoint?.label}"
            trip = trip.copy(destination = endpoint)
            return trip
        }

        override suspend fun addWaypoint(
            tripId: Long,
            name: String,
            lat: Double,
            lng: Double,
            type: String,
            orderIndex: Int?,
        ): Waypoint {
            writes += "stop:$name"
            val added = Waypoint(
                id = nextId++,
                name = name,
                lat = lat,
                lng = lng,
                type = type,
                orderIndex = orderIndex,
            )
            stops = stops + added
            return added
        }
    }

    private fun model(server: FakeServer) = TripMapViewModel(
        tripId = 1L,
        tripApi = server,
        positionSocket = null,
        feedback = FeedbackCenter(),
        onSessionExpired = {},
    )

    @Test
    fun `confirming a route the rider did not change leaves it on screen`() = runTest(dispatcher) {
        val server = FakeServer(trip, listOf(fuelStop))
        val model = model(server)

        model.refresh()
        testScheduler.advanceUntilIdle()
        model.openRouteSetup()
        testScheduler.advanceUntilIdle()

        model.confirmRoute()
        testScheduler.advanceUntilIdle()

        // The report, exactly: press Confirm on a trip that already has a
        // route and watch the two ends and the stop go. Against the old
        // `confirmRoute` this draft is `RouteDraft()`, which is what draws
        // `map_route_from_hint` and `map_route_to_hint`.
        val draft = model.uiState.value.routeDraft
        assertFalse("the route list went blank after Confirm", draft.isEmpty)
        assertEquals("Home", draft.from?.label)
        assertEquals("Pai", draft.to?.label)
        assertEquals(listOf("Lampang"), draft.stops.map { it.label })

        // And nothing was sent, because nothing moved. An end that did not
        // change costs no request — that rule is older than this bug and this
        // is the case that would quietly break it.
        assertEquals(emptyList<String>(), server.writes)
    }

    @Test
    fun `a stop added and confirmed is still there afterwards`() = runTest(dispatcher) {
        val server = FakeServer(trip)
        val model = model(server)

        model.refresh()
        testScheduler.advanceUntilIdle()
        model.openRouteSetup()
        testScheduler.advanceUntilIdle()

        model.pickRoutePoint(RouteField.STOP, RoutePoint(lampang, "Lampang"))
        model.confirmRoute()
        testScheduler.advanceUntilIdle()

        // The write happened...
        assertEquals(listOf("stop:Lampang"), server.writes)
        assertEquals(listOf("Lampang"), server.stops.map { it.name })

        // ...and the rider is left looking at it rather than at two empty
        // rows. This is the re-seed: the row was a draft point with no id
        // before the press and is the server's own stop after it.
        assertEquals(listOf("Lampang"), model.uiState.value.routeDraft.stops.map { it.label })
        assertEquals("Home", model.uiState.value.routeDraft.from?.label)
    }

    @Test
    fun `the map's confirm still throws the draft away`() = runTest(dispatcher) {
        val server = FakeServer(trip, listOf(fuelStop))
        val model = model(server)

        model.refresh()
        testScheduler.advanceUntilIdle()
        model.openRouteSetup()
        testScheduler.advanceUntilIdle()

        model.confirmRouteAndClose()
        testScheduler.advanceUntilIdle()

        // The other half of the split, and the reason it is a split rather
        // than a fix: the card over the map closes on confirm, and a draft
        // left on the view model behind it would go on drawing its own pins
        // over the trip's — see TripMapUiState.drawnWaypoints.
        assertTrue(model.uiState.value.routeDraft.isEmpty)
        assertEquals(
            listOf(fuelStop.id),
            model.uiState.value.drawnWaypoints.map { it.id },
        )
    }

    @Test
    fun `an end moved while the write was out is not overwritten by the re-seed`() =
        runTest(dispatcher) {
            val server = FakeServer(trip)
            val model = model(server)

            model.refresh()
            testScheduler.advanceUntilIdle()
            model.openRouteSetup()
            testScheduler.advanceUntilIdle()

            model.confirmRoute()
            // Before anything the confirm launched has been allowed to run.
            model.pickRoutePoint(RouteField.TO, RoutePoint(lampang, "Lampang"))
            testScheduler.advanceUntilIdle()

            // Theirs, not the server's. The re-seed only wins where the rider
            // has not touched the list since — the same rule `openRouteSetup`
            // applies to its own second seed.
            assertEquals("Lampang", model.uiState.value.routeDraft.to?.label)
        }
}
