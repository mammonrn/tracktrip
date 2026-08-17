package app.ptrip.tracktrip.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Metres per second in, kilometres per hour out.
 *
 * Worth its own tests because the unit is invisible: a speed that is silently
 * 3.6 times wrong still looks like a speed, and the first person to notice
 * would be a rider being told they were doing 20 on a motorway.
 */
class SpeedTest {

    @Test
    fun `metres per second become kilometres per hour`() {
        assertEquals(36, Speed.kmh(10.0))
        assertEquals(100, Speed.kmh(27.7778))
        // A brisk walk, which is also the case that would look plausible if
        // the conversion were missing altogether.
        assertEquals(5, Speed.kmh(1.5))
    }

    @Test
    fun `a stopped rider is nought, not nothing`() {
        // Distinct from a rider whose phone never sent a speed: one is
        // information, the other is its absence.
        assertEquals(0, Speed.kmh(0.0))
        assertNull(Speed.kmh(null))
    }

    @Test
    fun `speeds are rounded to whole kilometres per hour`() {
        assertEquals(51, Speed.kmh(14.1))
        assertEquals(50, Speed.kmh(13.9))
    }

    @Test
    fun `nonsense from the wire is refused rather than displayed`() {
        assertNull(Speed.kmh(-1.0))
        assertNull(Speed.kmh(Double.NaN))
        assertNull(Speed.kmh(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `this phone's own speed comes from a recent fix`() {
        assertEquals(72, Speed.ownKmh(20f, fixAgeMs = 3_000))
        assertEquals(0, Speed.ownKmh(0f, fixAgeMs = 0))
    }

    @Test
    fun `a stale fix reports no speed rather than an old one`() {
        // The top bar reads the fix the device already holds. One from an hour
        // ago carries the speed from an hour ago, and showing that as "now"
        // is worse than showing a dash.
        assertNull(Speed.ownKmh(20f, fixAgeMs = Speed.OWN_SPEED_MAX_AGE_MS + 1))
        assertEquals(20, Speed.ownKmh(5.55f, fixAgeMs = Speed.OWN_SPEED_MAX_AGE_MS))
    }

    @Test
    fun `a fix from the future is not trusted either`() {
        // A device clock adjusted backwards makes the last fix look as if it
        // were taken later than now.
        assertNull(Speed.ownKmh(20f, fixAgeMs = -1))
    }

    @Test
    fun `a fix with no speed shows nothing`() {
        assertNull(Speed.ownKmh(null, fixAgeMs = 0))
    }
}
