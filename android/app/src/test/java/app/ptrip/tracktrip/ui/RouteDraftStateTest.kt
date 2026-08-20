package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.RouteLine
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.data.TripWaypoints
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the map draws while a route is being set up.
 *
 * Four values on the state decide it, and all four exist for the same reason:
 * a point a rider has just chosen has to appear under their finger *before*
 * anything is saved, or picking it looks like the tap did nothing — and the
 * moment they close the card without confirming, every one of them has to fall
 * back to the trip exactly as it was.
 */
class RouteDraftStateTest {

    private val chiangMai = LatLng(18.7883, 98.9853)
    private val pai = LatLng(19.3583, 98.4400)
    private val lampang = LatLng(18.2888, 99.4908)

    private val tripRoad = RouteLine(listOf(chiangMai, pai), distanceKm = 130.0, durationMinutes = 180)
    private val draftRoad = RouteLine(listOf(chiangMai, lampang), distanceKm = 92.0, durationMinutes = 95)

    private fun trip(origin: LatLng? = chiangMai, destination: LatLng? = pai) = Trip(
        id = 1,
        name = "Mae Hong Son loop",
        ownerId = 1,
        status = Trip.STATUS_ACTIVE,
        role = Trip.ROLE_OWNER,
        origin = origin?.let { TripEndpoint(it.lat, it.lng, "Home") },
        destination = destination?.let { TripEndpoint(it.lat, it.lng, "Pai") },
    )

    @Test
    fun `with no draft, the map draws the trip`() {
        val state = TripMapUiState(trip = trip(), route = tripRoad)

        assertEquals(chiangMai.lat, state.drawnOrigin?.lat)
        assertEquals(pai.lat, state.drawnDestination?.lat)
        assertEquals(tripRoad.points, state.drawnRoute)
        assertEquals(tripRoad, state.draftRoute)
    }

    @Test
    fun `a draft that is the trip's own route reads the route already fetched`() {
        val state = TripMapUiState(
            trip = trip(),
            route = tripRoad,
            routeDraft = RouteSetupRules.fromTrip(trip().origin, trip().destination),
        )

        // No preview was fetched and none is needed: the summary sheet reads
        // the trip's own line, which was paid for once, for this exact pair.
        assertNull(state.routePreview)
        assertEquals(tripRoad, state.draftRoute)
        assertEquals(tripRoad.points, state.drawnRoute)
    }

    @Test
    fun `a moved end draws its own preview, not the road it replaced`() {
        val state = TripMapUiState(
            trip = trip(),
            route = tripRoad,
            routeDraft = RouteDraft(from = RoutePoint(chiangMai), to = RoutePoint(lampang)),
            routePreview = draftRoad,
        )

        assertEquals(draftRoad, state.draftRoute)
        assertEquals(draftRoad.points, state.drawnRoute)
        assertEquals(lampang.lat, state.drawnDestination?.lat)
    }

    @Test
    fun `a preview still in flight draws no road at all`() {
        val state = TripMapUiState(
            trip = trip(),
            route = tripRoad,
            routeDraft = RouteDraft(from = RoutePoint(chiangMai), to = RoutePoint(lampang)),
            routePreviewLoading = true,
        )

        // Leaving the trip's road on screen under a finish the rider has just
        // moved would be a road to somewhere they are no longer going.
        assertNull(state.draftRoute)
        assertTrue(state.drawnRoute.isEmpty())
    }

    @Test
    fun `a half-set draft still shows the end that was picked`() {
        val state = TripMapUiState(
            trip = trip(origin = null, destination = null),
            routeDraft = RouteDraft(from = RoutePoint(chiangMai, "Home")),
        )

        assertEquals(chiangMai.lat, state.drawnOrigin?.lat)
        assertNull(state.drawnDestination)
        // Nothing to route between, so nothing to draw.
        assertTrue(state.drawnRoute.isEmpty())
    }

    @Test
    fun `draft stops are drawn alongside the trip's own`() {
        val saved = Waypoint(
            id = 9,
            name = "Fuel",
            lat = 18.5,
            lng = 99.0,
            type = Waypoint.TYPE_PLANNED,
            orderIndex = 0,
        )
        val state = TripMapUiState(
            trip = trip(),
            waypoints = TripWaypoints(planned = listOf(saved)),
            routeDraft = RouteDraft(stops = listOf(RoutePoint(lampang, "Lampang"))),
        )

        assertEquals(listOf("Fuel", "Lampang"), state.drawnWaypoints.map { it.name })
        // The saved one keeps its real id, so tapping it still asks the server.
        assertEquals(listOf(false, true), state.drawnWaypoints.map { RouteSetupRules.isDraftId(it.id) })
    }

    @Test
    fun `closing the card puts everything back`() {
        val open = TripMapUiState(
            trip = trip(),
            route = tripRoad,
            routeDraft = RouteDraft(from = RoutePoint(chiangMai), to = RoutePoint(lampang)),
            routePreview = draftRoad,
        )
        // What closeRouteSetup leaves behind.
        val closed = open.copy(routeDraft = RouteDraft(), routePreview = null)

        assertEquals(pai.lat, closed.drawnDestination?.lat)
        assertEquals(tripRoad.points, closed.drawnRoute)
        assertTrue(closed.drawnWaypoints.isEmpty())
    }
}
