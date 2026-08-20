package app.ptrip.tracktrip.map

import app.ptrip.tracktrip.data.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a trip's geography comes to as a routing request.
 *
 * The bug this is the fix for: a trip's stops were drawn as pins and left out
 * of the routing call, so the line ran from the start to the finish past
 * everything the ride had been planned around. These are the rules that decide
 * which points go into the call and in what order — the two things that were
 * wrong, and the two things that are silent when they are wrong again.
 */
class RoutePlanTest {

    private val chiangMai = LatLng(18.7883, 98.9853)
    private val pai = LatLng(19.3583, 98.4400)
    private val lampang = LatLng(18.2888, 99.4908)
    private val nan = LatLng(18.7756, 100.7730)

    private fun waypoint(
        id: Long,
        at: LatLng,
        order: Int?,
        type: String = Waypoint.TYPE_PLANNED,
    ) = Waypoint(
        id = id,
        name = "Stop $id",
        lat = at.lat,
        lng = at.lng,
        type = type,
        orderIndex = order,
    )

    @Test
    fun `the plan is start, stops in order, finish`() {
        val plan = RoutePlans.of(
            from = chiangMai,
            to = nan,
            waypoints = listOf(waypoint(1, lampang, order = 0), waypoint(2, pai, order = 1)),
        )

        assertEquals(listOf(lampang, pai), plan?.via)
        assertEquals(listOf(chiangMai, lampang, pai, nan), plan?.points)
    }

    @Test
    fun `stops are sorted by order index, not by the order they arrived in`() {
        // The server sorts them too. This is the guard behind that: the field
        // is nullable in the model, and a list that arrived any other way must
        // not silently reorder somebody's ride.
        val plan = RoutePlans.of(
            from = chiangMai,
            to = nan,
            waypoints = listOf(waypoint(9, pai, order = 1), waypoint(3, lampang, order = 0)),
        )

        assertEquals(listOf(lampang, pai), plan?.via)
    }

    @Test
    fun `a stop with no order index sorts last rather than first`() {
        // Last is the safe end. First would put a point of unknown position at
        // the head of the ride and reroute the whole thing through it.
        val plan = RoutePlans.of(
            from = chiangMai,
            to = nan,
            waypoints = listOf(waypoint(1, pai, order = null), waypoint(2, lampang, order = 0)),
        )

        assertEquals(listOf(lampang, pai), plan?.via)
    }

    @Test
    fun `a live drop is not on the route`() {
        // A live waypoint is somewhere somebody stopped — the viewpoint, the
        // place the group turned round. Routing through them would redraw
        // everyone's route the moment one rider marked where they had a coffee.
        val plan = RoutePlans.of(
            from = chiangMai,
            to = nan,
            waypoints = listOf(
                waypoint(1, lampang, order = 0),
                waypoint(2, pai, order = null, type = Waypoint.TYPE_LIVE),
            ),
        )

        assertEquals(listOf(lampang), plan?.via)
    }

    @Test
    fun `a trip with no stops routes exactly as it always did`() {
        val plan = RoutePlans.of(from = chiangMai, to = nan, waypoints = emptyList())

        assertEquals(emptyList<LatLng>(), plan?.via)
        assertEquals(listOf(chiangMai, nan), plan?.points)
    }

    @Test
    fun `fewer than two ends is no plan, which is most of a trip's life`() {
        assertNull(RoutePlans.of(from = null, to = nan, waypoints = emptyList()))
        assertNull(RoutePlans.of(from = chiangMai, to = null, waypoints = emptyList()))
        assertNull(RoutePlans.of(from = null, to = null, waypoints = emptyList()))
    }

    @Test
    fun `adding a stop is a different route, so it is worth a request`() {
        val without = RoutePlans.of(chiangMai, nan, emptyList())
        val with = RoutePlans.of(chiangMai, nan, listOf(waypoint(1, pai, order = 0)))

        // This is the whole fix, at the level RouteRequests sees it: the plan
        // is the key, so a stop added mid-ride re-asks for the road exactly
        // once and a poll never does.
        assertTrue(RouteRequests.shouldFetch(with, without, retryAtMs = null, nowMs = 0))
        assertFalse(RouteRequests.shouldFetch(with, with, retryAtMs = null, nowMs = 0))
    }

    @Test
    fun `re-ordering the same stops is a different route`() {
        val one = RoutePlans.of(
            chiangMai, nan,
            listOf(waypoint(1, pai, order = 0), waypoint(2, lampang, order = 1)),
        )
        val other = RoutePlans.of(
            chiangMai, nan,
            listOf(waypoint(1, pai, order = 1), waypoint(2, lampang, order = 0)),
        )

        assertTrue(RouteRequests.shouldFetch(other, one, retryAtMs = null, nowMs = 0))
    }

    @Test
    fun `a poll that changes nothing still costs nothing`() {
        val stops = listOf(waypoint(1, pai, order = 0))
        var fetched: RoutePlan? = null
        var fetches = 0

        repeat(180) { poll ->
            val plan = RoutePlans.of(chiangMai, nan, stops)
            if (RouteRequests.shouldFetch(plan, fetched, null, poll * 20_000L)) {
                fetches += 1
                fetched = plan
            }
        }

        // An hour of polling on a trip with a stop on it: one request, same as
        // an hour of polling on a trip without one.
        assertEquals(1, fetches)
    }
}

/**
 * The straight-line fallback, when there is no road route to project onto.
 *
 * Two things have to be true at once here, and they pull in opposite
 * directions: a trip with stops has to be measured as the ride it is, and a
 * trip without them has to produce exactly the numbers it produced before any
 * of this existed.
 */
class DirectProgressTest {

    private val start = LatLng(18.0, 98.0)
    private val stop = LatLng(18.0, 99.0)
    private val finish = LatLng(18.0, 100.0)

    @Test
    fun `with no stops it is the measure the bar has always shown`() {
        val here = LatLng(18.0, 98.5)
        val plan = RoutePlan(from = start, via = emptyList(), to = finish)

        val direct = DirectProgress.of(plan, here)

        assertEquals(
            RouteProgress.fraction(start, finish, here)!!,
            direct!!.fraction,
            1e-9,
        )
        assertEquals(RouteProgress.remainingKm(finish, here)!!, direct.remainingKm, 1e-9)
    }

    @Test
    fun `with a stop it measures along the legs, not the gap to the finish`() {
        // A rider sitting on the stop is exactly half way along a two-leg
        // ride, and the gap-closing measure would say the same here only
        // because the legs happen to be collinear — so the interesting case is
        // the one below.
        val plan = RoutePlan(from = start, via = listOf(stop), to = finish)

        val direct = DirectProgress.of(plan, stop)

        assertEquals(0.5, direct!!.fraction, 0.01)
    }

    @Test
    fun `a detour out and back is not measured as no progress`() {
        // The case the old fallback got wrong, and the reason it is worth
        // measuring the legs at all. The ride goes east to a viewpoint and
        // back west to a finish near the start. A rider standing at the
        // viewpoint has ridden about half of it.
        val viewpoint = LatLng(18.0, 99.0)
        val nearStart = LatLng(18.0, 98.05)
        val plan = RoutePlan(from = start, via = listOf(viewpoint), to = nearStart)

        val direct = DirectProgress.of(plan, viewpoint)!!

        assertTrue("half the ride, not none of it", direct.fraction > 0.4)

        // What the gap-closing measure says about the same rider: the whole
        // journey is the 5 km between the two ends, they are 100 km from the
        // finish, so they have closed none of it and the bar clamps to empty.
        // An hour of riding, and a gauge that has not moved off the stop.
        assertEquals(0.0, RouteProgress.fraction(start, nearStart, viewpoint)!!, 1e-9)
    }

    @Test
    fun `a rider far from the legs still gets an answer`() {
        // Unlike the road measure, which refuses past MAX_OFF_ROUTE_KM: this
        // is the thing that answers when nothing else does, and the measure it
        // replaced never refused either.
        val faraway = LatLng(15.0, 104.0)
        val plan = RoutePlan(from = start, via = listOf(stop), to = finish)

        assertTrue(DirectProgress.of(plan, faraway) != null)
    }

    @Test
    fun `nothing to measure produces nothing`() {
        assertNull(DirectProgress.of(null, start))
        assertNull(DirectProgress.of(RoutePlan(start, emptyList(), finish), null))
    }

    @Test
    fun `a trip that starts where it finishes draws no bar`() {
        // A bar at zero would be a claim the rider has not started, which is a
        // different and false statement from having nothing to measure.
        assertNull(DirectProgress.of(RoutePlan(start, emptyList(), start), start))
    }
}
