package app.ptrip.tracktrip.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.ptrip.tracktrip.location.LIVE_INTERVAL_MS

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
        // A fix carries the speed the rider was doing when it was taken, and
        // showing that as "now" is worse than showing a dash.
        assertNull(Speed.ownKmh(20f, fixAgeMs = Speed.OWN_SPEED_MAX_AGE_MS + 1))
        assertEquals(20, Speed.ownKmh(5.55f, fixAgeMs = Speed.OWN_SPEED_MAX_AGE_MS))
    }

    @Test
    fun `the staleness limit is a handful of missed fixes, not a couple of minutes`() {
        // The readout is on a live 1 Hz feed. It used to read whatever fix the
        // device happened to be holding, refreshed once a reporting cycle, and
        // the two-minute limit that suited that is a licence to display a
        // two-minute-old speed against this one — which is the "reads behind
        // the bike" complaint, arriving by the slowest possible route.
        assertTrue(
            "the limit should be a small multiple of the live feed's interval",
            Speed.OWN_SPEED_MAX_AGE_MS <= 20 * LIVE_INTERVAL_MS
        )
        assertTrue(
            "and not so tight that a fix or two lost in traffic blanks it",
            Speed.OWN_SPEED_MAX_AGE_MS >= 5 * LIVE_INTERVAL_MS
        )
        // A minute-old reading is refused outright.
        assertNull(Speed.ownKmh(20f, fixAgeMs = 60_000))
    }

    @Test
    fun `nothing between the wire and the screen averages the speed`() {
        // The bug report was "60 km/h on the clocks, about 10 short on the
        // screen", and the first suspicion was smoothing in here. There is
        // none, and this is what says so: one fix in, one number out, no
        // history, no window. Two consecutive readings that differ by 20 km/h
        // are shown as two readings that differ by 20 km/h.
        assertEquals(36, Speed.ownKmh(10f, fixAgeMs = 0))
        assertEquals(72, Speed.ownKmh(20f, fixAgeMs = 0))
        assertEquals(36, Speed.ownKmh(10f, fixAgeMs = 0))
    }

    @Test
    fun `sixty on the clocks is sixty on the screen`() {
        // 16.6667 m/s is exactly 60 km/h. The conversion is not where the ten
        // units went.
        assertEquals(60, Speed.ownKmh(16.6667f, fixAgeMs = 0))
        assertEquals(60, Speed.kmh(16.6667))
    }

    @Test
    fun `a fix stamped in the future is read as a fix from now`() {
        // This is the ordinary case, not the exotic one. A GPS fix carries the
        // satellites' clock, and the phone's own clock — set by the network —
        // routinely runs a second or several behind it, so the age comes out
        // negative on every fix until the phone catches up. Answering that
        // with null was a permanent dash on the top bar of a phone that was
        // reporting a real speed to everybody else's map.
        assertEquals(72, Speed.ownKmh(20f, fixAgeMs = -1))
        assertEquals(72, Speed.ownKmh(20f, fixAgeMs = -8_000))
    }

    @Test
    fun `an old fix is still refused however the clocks disagree`() {
        // The guard that matters is unchanged: a reading from half an hour ago
        // presented as "now" is worse than showing nothing.
        assertNull(Speed.ownKmh(20f, fixAgeMs = 30 * 60 * 1000L))
    }

    @Test
    fun `a fix with no speed shows nothing`() {
        assertNull(Speed.ownKmh(null, fixAgeMs = 0))
    }
}
