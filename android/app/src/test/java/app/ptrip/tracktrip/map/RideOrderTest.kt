package app.ptrip.tracktrip.map

import app.ptrip.tracktrip.map.RideOrder.Tracked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is in front.
 *
 * This is the estimate described in [RideOrder] — a heading and a projection,
 * not a road distance — so these tests pin the two things that matter: that it
 * is right for a group strung out along one road, and that it says "I don't
 * know" rather than inventing an order when there is nothing to go on.
 */
class RideOrderTest {

    /** Roughly 1 km, in degrees, at the scale this operates on. */
    private val KM = 0.009

    private fun rider(id: Long, lat: Double, lng: Double, fromLat: Double? = null, fromLng: Double? = null) =
        Tracked(
            userId = id,
            position = LatLng(lat, lng),
            previous = if (fromLat == null || fromLng == null) null else LatLng(fromLat, fromLng),
        )

    @Test
    fun `a convoy heading north is ordered with the northernmost first`() {
        val riders = listOf(
            rider(1, 18.80, 99.0, 18.79, 99.0),  // middle
            rider(2, 18.81, 99.0, 18.80, 99.0),  // leader
            rider(3, 18.79, 99.0, 18.78, 99.0),  // tail
        )

        assertEquals(listOf(2L, 1L, 3L), RideOrder.leaderFirst(riders))
    }

    @Test
    fun `heading south flips the order`() {
        // The same three points; only the direction of travel changed. The
        // rider furthest south is now the one in front, which is the whole
        // reason the order is computed from movement rather than from
        // latitude.
        val riders = listOf(
            rider(1, 18.80, 99.0, 18.81, 99.0),
            rider(2, 18.81, 99.0, 18.82, 99.0),
            rider(3, 18.79, 99.0, 18.80, 99.0),
        )

        assertEquals(listOf(3L, 1L, 2L), RideOrder.leaderFirst(riders))
    }

    @Test
    fun `a group heading east is ordered by longitude`() {
        val riders = listOf(
            rider(1, 18.0, 99.00, 18.0, 98.99),
            rider(2, 18.0, 99.02, 18.0, 99.01),
        )

        assertEquals(listOf(2L, 1L), RideOrder.leaderFirst(riders))
    }

    @Test
    fun `a rider who has only reported once is still placed`() {
        // They contribute no heading — there is nothing to compare their fix
        // to — but the group's heading orders them all the same.
        val riders = listOf(
            rider(1, 18.79, 99.0, 18.78, 99.0),
            rider(2, 18.81, 99.0),
        )

        assertEquals(listOf(2L, 1L), RideOrder.leaderFirst(riders))
    }

    @Test
    fun `a parked group has no order at all`() {
        // Every poll during a coffee stop lands here. Returning null is what
        // stops the list reshuffling itself out of GPS noise.
        val riders = listOf(
            rider(1, 18.7900, 99.0, 18.79001, 99.00001),
            rider(2, 18.7910, 99.0, 18.79101, 99.00001),
        )

        assertNull(RideOrder.leaderFirst(riders))
    }

    @Test
    fun `riders meeting head-on have no shared direction`() {
        val riders = listOf(
            rider(1, 18.80, 99.0, 18.79, 99.0),
            rider(2, 18.81, 99.0, 18.82, 99.0),
        )

        assertNull(RideOrder.groupHeading(riders))
        assertNull(RideOrder.leaderFirst(riders))
    }

    @Test
    fun `a first poll, with nothing to compare against, has no order`() {
        val riders = listOf(rider(1, 18.79, 99.0), rider(2, 18.81, 99.0))

        assertNull(RideOrder.leaderFirst(riders))
    }

    @Test
    fun `riders with no position are listed after the ones on the map`() {
        val riders = listOf(
            Tracked(userId = 9, position = null, previous = null),
            rider(1, 18.79, 99.0, 18.78, 99.0),
            rider(2, 18.81, 99.0, 18.80, 99.0),
        )

        assertEquals(listOf(2L, 1L, 9L), RideOrder.leaderFirst(riders))
    }

    @Test
    fun `one fast rider does not decide the group's direction on their own`() {
        // Three riders creeping north and one bolting east: the heading is
        // the majority's, because each movement is normalised before being
        // averaged. Speed is not a vote.
        val riders = listOf(
            rider(1, 18.79 + KM, 99.0, 18.79, 99.0),
            rider(2, 18.80 + KM, 99.0, 18.80, 99.0),
            rider(3, 18.81 + KM, 99.0, 18.81, 99.0),
            rider(4, 18.80, 99.0 + KM * 10, 18.80, 99.0),
        )

        val heading = requireNotNull(RideOrder.groupHeading(riders))
        assertTrue(
            "expected a mostly-northward heading, got ${heading.bearingDegrees}",
            heading.bearingDegrees < 45.0 || heading.bearingDegrees > 315.0,
        )
    }

    @Test
    fun `the heading is a compass bearing`() {
        val north = RideOrder.groupHeading(listOf(rider(1, 18.80, 99.0, 18.79, 99.0)))
        assertEquals(0.0, requireNotNull(north).bearingDegrees, 0.5)

        val east = RideOrder.groupHeading(listOf(rider(1, 18.0, 99.01, 18.0, 99.0)))
        assertEquals(90.0, requireNotNull(east).bearingDegrees, 0.5)

        // Equal steps of latitude and longitude are *not* 225°: a degree of
        // longitude is about 5% shorter than one of latitude at this latitude,
        // and the projection corrects for that. 223.6° is the right answer,
        // and getting a flat 225 here would mean the correction was missing.
        val southWest = RideOrder.groupHeading(listOf(rider(1, 17.99, 98.99, 18.0, 99.0)))
        assertEquals(223.6, requireNotNull(southWest).bearingDegrees, 0.2)
    }

    @Test
    fun `riders side by side keep a stable order between polls`() {
        // Two riders abreast project onto the heading identically. Without a
        // tiebreak they would swap places on every refresh, which reads as
        // overtaking that never happened.
        val riders = listOf(
            rider(7, 18.80, 99.010, 18.79, 99.010),
            rider(3, 18.80, 99.000, 18.79, 99.000),
        )

        assertEquals(listOf(3L, 7L), RideOrder.leaderFirst(riders))
        assertEquals(listOf(3L, 7L), RideOrder.leaderFirst(riders.reversed()))
    }

    @Test
    fun `distance is measured in metres`() {
        // A sanity check on the flat-earth approximation the projection uses:
        // one hundredth of a degree of latitude is about 1.11 km anywhere.
        val metres = RideOrder.distanceMetres(LatLng(18.0, 99.0), LatLng(18.01, 99.0))

        assertEquals(1113.0, metres, 5.0)
    }

    @Test
    fun `an empty group has no order`() {
        assertNull(RideOrder.leaderFirst(emptyList()))
        assertNull(RideOrder.groupHeading(emptyList()))
    }

    @Test
    fun `a heading is a unit vector`() {
        val heading = requireNotNull(RideOrder.groupHeading(listOf(rider(1, 18.9, 99.1, 18.8, 99.0))))

        assertNotNull(heading)
        assertEquals(1.0, kotlin.math.hypot(heading.x, heading.y), 1e-9)
    }
}
