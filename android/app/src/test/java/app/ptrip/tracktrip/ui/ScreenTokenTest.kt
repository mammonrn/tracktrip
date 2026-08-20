package app.ptrip.tracktrip.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The back stack's saved form.
 *
 * This is what stands between the rider and the bug they reported as "some
 * screens have no back button". The activity is rebuilt for reasons that have
 * nothing to do with navigating — changing the app's language is one, and it
 * is the one a rider hits deliberately — and until now the history was
 * rebuilt empty each time, dropping them on the trip list.
 */
class ScreenTokenTest {

    private val everyScreen = listOf(
        Screen.Trips,
        Screen.CreateTrip,
        Screen.Settings,
        Screen.Profile,
        Screen.ScanQr,
        Screen.Places,
        Screen.TripDetail(42L),
        Screen.TripQr(42L),
        Screen.TripMap(42L),
    )

    @Test
    fun `every screen survives a round trip`() {
        // Written as a sweep over the list rather than eight assertions so
        // that a screen added later without a token fails here, not on a
        // phone that has just been rotated.
        everyScreen.forEach { screen ->
            assertEquals(screen, screenFromToken(screenToken(screen)))
        }
    }

    @Test
    fun `screens that carry a trip keep the right one`() {
        // The argument is the whole point of saving these: restoring
        // TripMap without its id would open somebody else's ride.
        assertEquals(Screen.TripMap(7L), screenFromToken("tripMap:7"))
        assertEquals(Screen.TripDetail(1234567890123L), screenFromToken("tripDetail:1234567890123"))
    }

    @Test
    fun `a token this build cannot read costs one entry, not the app`() {
        // Saved state outlives the build that wrote it: a rider updates the
        // app while it sits in the background, and the state handed back on
        // resume was written by the version before. An unknown screen has to
        // be droppable.
        assertNull(screenFromToken("someScreenFromNextYear"))
        assertNull(screenFromToken("tripMap:not-a-number"))
        assertNull(screenFromToken("tripMap:"))
        assertNull(screenFromToken(""))
    }

    @Test
    fun `a whole history is restored in order`() {
        val tokens = listOf("trips", "tripDetail:9", "tripMap:9")

        assertEquals(
            listOf(Screen.Trips, Screen.TripDetail(9L), Screen.TripMap(9L)),
            screensFromTokens(tokens),
        )
    }

    @Test
    fun `unreadable entries are dropped, the rest are kept`() {
        assertEquals(
            listOf(Screen.Trips, Screen.TripDetail(9L)),
            screensFromTokens(listOf("trips", "wat", "tripDetail:9")),
        )
    }

    @Test
    fun `restoring nothing still leaves somewhere to stand`() {
        // A back stack with no current screen has no meaning, and every
        // caller reads `current` without asking whether there is one.
        assertEquals(listOf(Screen.Trips), screensFromTokens(emptyList()))
        assertEquals(listOf(Screen.Trips), screensFromTokens(listOf("wat", "also wat")))
    }
}
