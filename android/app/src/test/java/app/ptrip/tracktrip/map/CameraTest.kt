package app.ptrip.tracktrip.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the map opens on.
 *
 * The camera itself needs a device — osmdroid measures the view before it can
 * turn a bounding box into a zoom — but *which* box, and when a box is the
 * wrong answer at all, is arithmetic, and it is the part that was wrong: the
 * screen used to open zoomed out over the whole province however close
 * together the group actually was.
 */
class CameraTest {

    private val chiangMai = LatLng(18.7883, 98.9853)
    private val lampang = LatLng(18.2888, 99.4908)

    @Test
    fun `a group is framed by a box that contains all of them`() {
        val target = initialCamera(listOf(chiangMai, lampang), myLocation = null)
        val bounds = target.bounds

        assertNotNull(bounds)
        requireNotNull(bounds)
        assertTrue(bounds.north > chiangMai.lat)
        assertTrue(bounds.south < lampang.lat)
        assertTrue(bounds.east > lampang.lng)
        assertTrue(bounds.west < chiangMai.lng)
        // A box means "fit this", which only the measured view can turn into
        // a zoom — so there must not also be one here.
        assertNull(target.zoom)
    }

    @Test
    fun `the box leaves a margin, so nobody is drawn on the screen edge`() {
        val target = initialCamera(listOf(chiangMai, lampang), myLocation = null)
        val bounds = requireNotNull(target.bounds)

        val riderSpan = chiangMai.lat - lampang.lat
        val boxSpan = bounds.north - bounds.south
        assertTrue("the box should be wider than the riders it holds", boxSpan > riderSpan)
        // Generous but not absurd: a box several times the group's own size
        // would put them in a huddle in the middle.
        assertTrue(boxSpan < riderSpan * 2)
    }

    @Test
    fun `one rider gets a street-level zoom, not a bounding box`() {
        // Fitting a box of zero size is what produced the "one building fills
        // the screen" end of the same bug.
        val target = initialCamera(listOf(chiangMai), myLocation = null)

        assertNull(target.bounds)
        assertEquals(SOLO_ZOOM, target.zoom!!, 0.001)
        assertEquals(chiangMai, target.centre)
    }

    @Test
    fun `a group standing together is treated as one place`() {
        // Four riders in a car park, tens of metres apart. Fitting that box
        // would zoom past the road they are about to ride down.
        val together = listOf(
            LatLng(18.7883, 98.9853),
            LatLng(18.7885, 98.9855),
            LatLng(18.7882, 98.9851),
            LatLng(18.7884, 98.9856),
        )

        val target = initialCamera(together, myLocation = null)

        assertNull(target.bounds)
        assertEquals(SOLO_ZOOM, target.zoom!!, 0.001)
        assertEquals(18.78835, target.centre.lat, 0.0001)
    }

    @Test
    fun `two riders a few km apart are still worth fitting`() {
        // The boundary the previous test sits under: far enough that a fixed
        // zoom would leave one of them off screen.
        val target = initialCamera(
            listOf(LatLng(18.7883, 98.9853), LatLng(18.8300, 99.0200)),
            myLocation = null,
        )

        assertNotNull(target.bounds)
    }

    @Test
    fun `with nobody reporting, the camera starts on this phone`() {
        val mine = LatLng(13.7563, 100.5018)

        val target = initialCamera(riders = emptyList(), myLocation = mine)

        assertEquals(mine, target.centre)
        assertEquals(SOLO_ZOOM, target.zoom!!, 0.001)
    }

    @Test
    fun `Chiang Mai is the last resort, not the default`() {
        // It is only right when nothing at all is known — which was the bug:
        // a rider standing in Bangkok was shown Chiang Mai.
        val target = initialCamera(riders = emptyList(), myLocation = null)

        assertEquals(FALLBACK_CENTRE, target.centre)
        assertEquals(FALLBACK_ZOOM, target.zoom!!, 0.001)
    }

    @Test
    fun `a reporting group wins over this phone's own fix`() {
        // A rider watching a trip from home must see the trip, not their sofa.
        val target = initialCamera(
            riders = listOf(chiangMai, lampang),
            myLocation = LatLng(13.7563, 100.5018),
        )
        val bounds = requireNotNull(target.bounds)

        assertTrue("Bangkok must not stretch the box", bounds.south > 18.0)
    }

    // ---- turning a box into a zoom -------------------------------------
    //
    // A phone screen, in pixels. Everything below is expressed against it
    // because a zoom means nothing without knowing what it has to fill.
    private val screenWidth = 1080
    private val screenHeight = 1920

    @Test
    fun `a group a few kilometres apart is framed at street level`() {
        // Two riders 5 km apart: the answer has to be close enough to read
        // the road they are on. This is the case that was wrong on the phone —
        // it opened on the whole province.
        val a = LatLng(18.7883, 98.9853)
        val b = LatLng(18.8283, 99.0253)
        val bounds = requireNotNull(initialCamera(listOf(a, b), null).bounds)

        val zoom = fitZoom(bounds, screenWidth, screenHeight)

        assertTrue("$zoom is too far out to read a street", zoom > 12.5)
        assertTrue("$zoom is closer than the group's own span", zoom <= 15.5)
    }

    @Test
    fun `the fitted box actually fits on the screen`() {
        // The property that matters, stated directly: at the zoom this
        // returns, the box has to be no wider and no taller than the view.
        val bounds = requireNotNull(initialCamera(listOf(chiangMai, lampang), null).bounds)
        val zoom = fitZoom(bounds, screenWidth, screenHeight)

        val worldPx = 256.0 * Math.pow(2.0, zoom)
        val boxWidthPx = worldPx * (bounds.east - bounds.west) / 360.0
        val boxHeightPx = worldPx * mercatorSpan(bounds.north, bounds.south)

        assertTrue("box is ${boxWidthPx}px wide in a ${screenWidth}px view", boxWidthPx <= screenWidth + 1)
        assertTrue("box is ${boxHeightPx}px tall in a ${screenHeight}px view", boxHeightPx <= screenHeight + 1)
    }

    @Test
    fun `the tighter axis decides`() {
        // A convoy strung north to south fits on longitude easily and not at
        // all on latitude. Taking the looser axis would run the leader off
        // the top of the screen.
        val tallBox = Bounds(north = 19.5, south = 18.0, east = 99.0, west = 98.99)
        val zoom = fitZoom(tallBox, screenWidth, screenHeight)

        val worldPx = 256.0 * Math.pow(2.0, zoom)
        assertTrue(worldPx * mercatorSpan(tallBox.north, tallBox.south) <= screenHeight + 1)
    }

    @Test
    fun `latitude is measured after projection, not in raw degrees`() {
        // A degree of latitude takes more pixels the further from the equator
        // the ride is. Fitting on degrees would frame an Arctic box as though
        // it were an equatorial one and cut riders off the screen.
        val equator = Bounds(north = 1.0, south = -1.0, east = 100.0, west = 99.0)
        val arctic = Bounds(north = 71.0, south = 69.0, east = 100.0, west = 99.0)

        assertTrue(
            fitZoom(arctic, screenWidth, screenHeight) <
                fitZoom(equator, screenWidth, screenHeight)
        )
    }

    @Test
    fun `an unmeasured view gets the fallback rather than an infinity`() {
        // Before layout the view is 0 x 0, and "how many times does zero go
        // into this box" is not a camera.
        val bounds = requireNotNull(initialCamera(listOf(chiangMai, lampang), null).bounds)

        assertEquals(FALLBACK_ZOOM, fitZoom(bounds, 0, 0), 0.0)
        assertEquals(FALLBACK_ZOOM, fitZoom(bounds, 1080, 0), 0.0)
    }

    @Test
    fun `fitting never zooms closer than the solo view`() {
        // A box a few metres across — two phones on the same forecourt whose
        // fixes differ by GPS noise. Without the ceiling this would frame the
        // gap between them, which is a picture of tarmac.
        val hair = Bounds(north = 18.78831, south = 18.78830, east = 98.98531, west = 98.98530)

        assertEquals(15.5, fitZoom(hair, screenWidth, screenHeight), 0.0)
    }

    @Test
    fun `one impossible coordinate cannot pull the camera off the planet`() {
        // (0, 0) is a valid pair and a real bug's signature — a fix that
        // never got a lock. Fitting it together with a rider in Thailand
        // would show a hemisphere; the floor keeps the map a map.
        val bounds = requireNotNull(initialCamera(listOf(chiangMai, LatLng(0.0, 0.0)), null).bounds)

        assertTrue(fitZoom(bounds, screenWidth, screenHeight) >= 4.0)
    }

    @Test
    fun `knowing nothing still opens on roads, not on a province`() {
        // The camera for "nobody has reported and this phone has not said
        // where it is". It cannot be street level — that would claim
        // knowledge the app does not have — but at the old 12 the screen
        // covered forty kilometres and had nothing legible on it.
        val target = initialCamera(riders = emptyList(), myLocation = null)

        assertEquals(FALLBACK_CENTRE, target.centre)
        assertEquals(14.0, requireNotNull(target.zoom), 0.0)
    }

    /** The height of a latitude span as a fraction of the whole projected map. */
    private fun mercatorSpan(north: Double, south: Double): Double {
        fun y(lat: Double): Double {
            val projected = Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(lat) / 2.0))
            return (1.0 - projected / Math.PI) / 2.0
        }
        return Math.abs(y(north) - y(south))
    }
}
