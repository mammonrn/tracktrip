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
}
