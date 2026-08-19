package app.ptrip.tracktrip.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slide a pin makes between two reports.
 *
 * The animation itself needs a frame clock and a map; the curve it follows
 * does not, and the curve is what decides whether the movement reads as a
 * rider travelling or as a glitch.
 */
class MarkerMotionTest {

    private val start = LatLng(18.79, 98.98)
    private val end = LatLng(18.83, 99.02)

    @Test
    fun `the slide begins where the rider was and ends where they are`() {
        assertEquals(start, MarkerMotion.at(start, end, 0f))
        assertEquals(end.lat, MarkerMotion.at(start, end, 1f).lat, 1e-12)
        assertEquals(end.lng, MarkerMotion.at(start, end, 1f).lng, 1e-12)
    }

    @Test
    fun `halfway along is halfway between`() {
        val middle = MarkerMotion.at(start, end, 0.5f)

        assertEquals(18.81, middle.lat, 1e-9)
        assertEquals(99.00, middle.lng, 1e-9)
    }

    @Test
    fun `the curve eases in and out rather than running at a constant speed`() {
        // A pin that started and stopped abruptly would look like two jumps
        // with a slide in the middle.
        assertTrue(MarkerMotion.ease(0.25f) < 0.25f)
        assertTrue(MarkerMotion.ease(0.75f) > 0.75f)
        assertEquals(0.5f, MarkerMotion.ease(0.5f), 1e-6f)
    }

    @Test
    fun `the curve is pinned at both ends`() {
        assertEquals(0f, MarkerMotion.ease(0f), 1e-6f)
        assertEquals(1f, MarkerMotion.ease(1f), 1e-6f)
    }

    @Test
    fun `a frame that arrives late or early cannot push a pin past its target`() {
        // withFrameNanos gives no guarantee about which frame lands when; a
        // fraction outside 0..1 must clamp, not overshoot into the sea.
        assertEquals(end.lat, MarkerMotion.at(start, end, 1.4f).lat, 1e-12)
        assertEquals(start.lat, MarkerMotion.at(start, end, -0.2f).lat, 1e-12)
    }

    @Test
    fun `the slide always moves forwards`() {
        var previous = -1f
        var step = 0f
        while (step <= 1f) {
            val eased = MarkerMotion.ease(step)
            assertTrue("the curve went backwards at $step", eased >= previous)
            previous = eased
            step += 0.05f
        }
    }

    @Test
    fun `the whole slide is over well inside one poll interval`() {
        // A pin still travelling when the next set of positions arrives would
        // be re-aimed mid-flight, and never settle anywhere true. The literal
        // mirrors `LiveCadence.POLL_MS` — the faster of the two cadences, and
        // therefore the one the slide has to finish inside. The literal is
        // here rather than the constant so this file stays free of anything
        // that would need a device.
        assertTrue(MarkerMotion.DURATION_MS < 20_000L)
    }
}
