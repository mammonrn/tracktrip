package app.ptrip.tracktrip.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar down the side of the map: how far is left, and how much is done.
 *
 * Straight-line, and the tests say so where it matters. The app has no routing
 * engine and is not getting one to draw a progress bar; what this measures is
 * the gap closing between a rider and where they said they were going.
 */
class RouteProgressTest {

    private val chiangMai = LatLng(18.7883, 98.9853)
    private val lamphun = LatLng(18.5744, 99.0087)
    private val pai = LatLng(19.3583, 98.4406)

    @Test
    fun `standing at the origin is no progress at all`() {
        assertEquals(0.0, RouteProgress.fraction(chiangMai, pai, chiangMai)!!, 1e-9)
    }

    @Test
    fun `standing at the destination is all of it`() {
        assertEquals(1.0, RouteProgress.fraction(chiangMai, pai, pai)!!, 1e-9)
    }

    @Test
    fun `halfway there reads as about half`() {
        val middle = LatLng(
            (chiangMai.lat + pai.lat) / 2,
            (chiangMai.lng + pai.lng) / 2,
        )

        val fraction = RouteProgress.fraction(chiangMai, pai, middle)!!

        assertTrue("got $fraction", fraction in 0.45..0.55)
    }

    @Test
    fun `a detour is not progress`() {
        // Measured as ground *closed towards the destination*, not ground
        // covered. A rider who has ridden fifty kilometres the wrong way has
        // not got fifty kilometres closer, and the bar must not say they have.
        val wrongWay = LatLng(18.5744, 99.0087) // Lamphun, south — Pai is north-west
        val fraction = RouteProgress.fraction(chiangMai, pai, wrongWay)!!

        assertEquals(0.0, fraction, 1e-9)
    }

    @Test
    fun `arriving just past the destination still reads as arrived`() {
        // A rider stops in the car park, not on the pin. A few hundred metres
        // beyond has to read as done, or the bar sits at 99% while they take
        // their helmet off.
        val carPark = LatLng(pai.lat + 0.003, pai.lng + 0.003)

        assertTrue(RouteProgress.fraction(chiangMai, pai, carPark)!! > 0.99)
    }

    @Test
    fun `riding a long way past the destination reads as being a long way from it`() {
        // A property of measuring the gap rather than the ground covered, and
        // worth stating rather than hiding: somebody 80 km beyond Pai is 80 km
        // from Pai, and the bar says so instead of staying full. That is the
        // right answer for a rider who has overshot and needs to know.
        val wellPast = LatLng(20.0, 98.0)

        val fraction = RouteProgress.fraction(chiangMai, pai, wellPast)!!
        assertTrue("got $fraction", fraction < 0.1)
    }

    @Test
    fun `no destination means no bar, not an empty one`() {
        // A bar at zero claims the rider has not started. "There is nothing to
        // measure" is a different statement, and the screen draws nothing.
        assertNull(RouteProgress.fraction(chiangMai, null, lamphun))
        assertNull(RouteProgress.fraction(null, pai, lamphun))
        assertNull(RouteProgress.fraction(chiangMai, pai, null))
    }

    @Test
    fun `a trip that starts where it ends has no fraction to give`() {
        // Dividing by that distance is dividing by zero; a loop ride needs a
        // different measure than this one, and pretending otherwise would put
        // a NaN on screen.
        assertNull(RouteProgress.fraction(chiangMai, chiangMai, lamphun))
    }

    @Test
    fun `remaining distance is the straight line to the destination`() {
        // Chiang Mai to Lamphun is about 24 km as the crow flies.
        val km = RouteProgress.remainingKm(lamphun, chiangMai)!!

        assertTrue("got $km", km in 23.0..25.0)
    }

    @Test
    fun `remaining distance needs both ends`() {
        assertNull(RouteProgress.remainingKm(null, chiangMai))
        assertNull(RouteProgress.remainingKm(lamphun, null))
    }

    @Test
    fun `the last kilometre is metres, rounded to fifty`() {
        // Rounded because the last few hundred metres would otherwise flicker
        // with GPS noise while the rider sat at a junction.
        assertEquals("850 m", RouteProgress.format(0.849))
        assertEquals("500 m", RouteProgress.format(0.5))
        assertEquals("50 m", RouteProgress.format(0.04))
    }

    @Test
    fun `rounding up from metres does not print a thousand of them`() {
        // "1000 m" after "950 m" is a unit the rounding invented.
        assertEquals("1.0 km", RouteProgress.format(0.999))
    }

    @Test
    fun `a tenth of a kilometre is shown while it still means something`() {
        assertEquals("1.2 km", RouteProgress.format(1.23))
        assertEquals("9.9 km", RouteProgress.format(9.94))
    }

    @Test
    fun `past ten kilometres the tenth is noise`() {
        assertEquals("24 km", RouteProgress.format(23.6))
        assertEquals("137 km", RouteProgress.format(137.2))
    }

    @Test
    fun `nothing to show is shown as nothing`() {
        assertNull(RouteProgress.format(null))
        assertNull(RouteProgress.format(Double.NaN))
        assertNull(RouteProgress.format(-1.0))
    }
}

/**
 * Who is driving the camera.
 *
 * The rule that matters is the last one: a rider who has moved the map by hand
 * keeps it. Everything else on this screen is allowed to be clever; this is
 * not, because a camera that argues with the person holding the phone is the
 * single most irritating thing a map can do.
 */
class CameraRulesTest {

    @Test
    fun `a rider who is sharing opens following themselves`() {
        assertEquals(MapCamera.FOLLOW, CameraRules.initial(isSharingThisTrip = true))
    }

    @Test
    fun `somebody watching a ride they are not on is not dragged anywhere`() {
        // They have no position of their own worth following, and pulling the
        // camera to it would be a fight they did not start.
        assertEquals(MapCamera.FREE, CameraRules.initial(isSharingThisTrip = false))
    }

    @Test
    fun `one drag ends the following, whatever it was doing`() {
        assertEquals(MapCamera.FREE, CameraRules.afterPan())
    }

    @Test
    fun `only following moves the camera when a position arrives`() {
        assertTrue(CameraRules.followsPosition(MapCamera.FOLLOW))
        assertTrue(!CameraRules.followsPosition(MapCamera.FREE))
        // Overview's whole point is a frame bigger than the rider in it.
        assertTrue(!CameraRules.followsPosition(MapCamera.OVERVIEW))
    }
}
