package app.ptrip.tracktrip.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress measured along a road rather than across the gap to the finish.
 *
 * The straight-line measure ([RouteProgress]) is still here and still the
 * fallback, so these are as much about *when the road answer is refused* as
 * about the arithmetic: a rider projected onto a line they are not on gets a
 * confident number about somebody else's journey, which is worse than the
 * honest straight line.
 */
class RouteGeometryTest {

    /**
     * A straight north-south line, ten points, about 11 km long.
     *
     * Deliberately synthetic: real road geometry would make every expected
     * value a magic number nobody could check.
     */
    private val line = (0..10).map { LatLng(18.0 + it * 0.01, 98.0) }

    /** A dog-leg: east for a while, then north. Twice as long as the crow flies. */
    private val dogLeg = listOf(
        LatLng(18.0, 98.0),
        LatLng(18.0, 98.2),
        LatLng(18.2, 98.2),
    )

    @Test
    fun `the length is the sum of the segments, not the gap between the ends`() {
        val direct = RouteProgress.distanceKm(dogLeg.first(), dogLeg.last())
        val along = RouteGeometry.lengthKm(dogLeg)

        assertTrue("a dog-leg must be longer than its own shortcut", along > direct * 1.3)
    }

    @Test
    fun `anything that is not a line has no length and no answer`() {
        assertEquals(0.0, RouteGeometry.lengthKm(emptyList()), 0.0001)
        assertEquals(0.0, RouteGeometry.lengthKm(listOf(LatLng(18.0, 98.0))), 0.0001)
        assertNull(RouteGeometry.progress(emptyList(), LatLng(18.0, 98.0)))
        assertNull(RouteGeometry.progress(listOf(LatLng(18.0, 98.0)), LatLng(18.0, 98.0)))
    }

    @Test
    fun `a rider at the start has done none of it`() {
        val progress = RouteGeometry.progress(line, line.first())

        assertNotNull(progress)
        assertEquals(0.0, progress!!.fraction, 0.001)
        assertEquals(RouteGeometry.lengthKm(line), progress.remainingKm, 0.01)
    }

    @Test
    fun `a rider at the finish has done all of it`() {
        val progress = RouteGeometry.progress(line, line.last())!!

        assertEquals(1.0, progress.fraction, 0.001)
        assertEquals(0.0, progress.remainingKm, 0.01)
    }

    @Test
    fun `a rider halfway along is halfway along`() {
        val progress = RouteGeometry.progress(line, line[5])!!

        assertEquals(0.5, progress.fraction, 0.01)
        assertEquals(RouteGeometry.lengthKm(line) / 2, progress.remainingKm, 0.1)
    }

    @Test
    fun `a rider just off the road is placed on the nearest point of it`() {
        // A hundred metres or so to one side, which is every GPS fix on every
        // road ever: the projection has to land on the road rather than
        // refusing to answer.
        val beside = LatLng(line[5].lat, line[5].lng + 0.001)
        val progress = RouteGeometry.progress(line, beside)!!

        assertEquals(0.5, progress.fraction, 0.02)
    }

    @Test
    fun `progress on a bent road is measured along the bend, not across it`() {
        // At the corner of the dog-leg the rider is half way by road and
        // nowhere near half way as the crow flies. This is the whole reason
        // for fetching a route at all.
        val progress = RouteGeometry.progress(dogLeg, dogLeg[1])!!

        assertEquals(0.5, progress.fraction, 0.05)

        val straight = RouteProgress.fraction(dogLeg.first(), dogLeg.last(), dogLeg[1])!!
        assertTrue(
            "the straight-line measure should disagree here, that is the point",
            kotlin.math.abs(straight - progress.fraction) > 0.15,
        )
    }

    @Test
    fun `a rider past the finish clamps to done rather than running over`() {
        val past = LatLng(line.last().lat + 0.05, line.last().lng)
        val progress = RouteGeometry.progress(line, past)!!

        assertEquals(1.0, progress.fraction, 0.001)
        assertEquals(0.0, progress.remainingKm, 0.001)
    }

    @Test
    fun `a rider before the start clamps to nothing done`() {
        val before = LatLng(line.first().lat - 0.05, line.first().lng)
        val progress = RouteGeometry.progress(line, before)!!

        assertEquals(0.0, progress.fraction, 0.001)
    }

    @Test
    fun `a rider nowhere near the route gets no answer at all`() {
        // Bangkok against a route in the north: projecting them onto it would
        // report a percentage of a road they have never been on. The screen
        // falls back to the straight line, which at least measures something
        // true — and says so.
        val elsewhere = LatLng(13.75, 100.5)

        assertNull(RouteGeometry.progress(line, elsewhere))
        assertNull(RouteGeometry.fraction(line, elsewhere))
        assertNull(RouteGeometry.remainingKm(line, elsewhere))
    }

    @Test
    fun `the off-route threshold is loose enough for a detour`() {
        // A parallel road, a diversion round a landslide, a fuel stop off the
        // highway: still this journey. About 10 km east of the line.
        val detour = LatLng(line[5].lat, line[5].lng + 0.095)
        val nearest = RouteGeometry.nearest(line, detour)!!

        assertTrue(nearest.offRouteKm < RouteGeometry.MAX_OFF_ROUTE_KM)
        assertNotNull(RouteGeometry.progress(line, detour))
    }

    @Test
    fun `a repeated point in the geometry does not break the measure`() {
        // Upstream geometry does contain duplicate vertices, and a zero-length
        // segment is a division by zero waiting to happen.
        val withDuplicates = listOf(
            LatLng(18.0, 98.0),
            LatLng(18.0, 98.0),
            LatLng(18.05, 98.0),
            LatLng(18.1, 98.0),
        )
        val progress = RouteGeometry.progress(withDuplicates, LatLng(18.05, 98.0))!!

        assertEquals(0.5, progress.fraction, 0.01)
    }

    @Test
    fun `fraction and remaining always agree with each other`() {
        for (step in 0..10) {
            val here = LatLng(18.0 + step * 0.01, 98.0)
            val progress = RouteGeometry.progress(line, here)!!
            val total = RouteGeometry.lengthKm(line)

            assertEquals(
                progress.remainingKm,
                total * (1 - progress.fraction),
                0.05,
            )
        }
    }
}
