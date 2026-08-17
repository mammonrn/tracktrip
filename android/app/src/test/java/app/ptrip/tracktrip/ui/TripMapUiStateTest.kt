package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.riderLabel
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.ui.theme.riderColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets a pin and who does not.
 *
 * The map itself needs a device — tiles, a renderer, a location provider — but
 * the split it draws from is plain data, and it is the part with a rule worth
 * stating: a rider who has never reported is *not* dropped from the screen,
 * only from the map.
 */
class TripMapUiStateTest {

    private fun member(id: Long, lat: Double? = null, lng: Double? = null, sharing: Boolean = true) =
        MemberPosition(
            userId = id,
            displayName = "Rider $id",
            username = null,
            photoUrl = null,
            role = "member",
            isSharing = sharing,
            sharingUntil = null,
            lat = lat,
            lng = lng,
            speedMps = null,
            batteryPct = null,
            recordedAt = null,
        )

    @Test
    fun `riders with a fix are placed, riders without are still listed`() {
        val placed = member(1, 18.79, 98.98)
        val waiting = member(2)
        val state = TripMapUiState(members = listOf(placed, waiting))

        assertEquals(listOf(placed), state.placed)
        assertEquals(listOf(waiting), state.unplaced)
        // Nobody is lost between the two: the list under the map shows all of
        // them, which is the point of having a list under the map.
        assertEquals(state.members.size, state.placed.size + state.unplaced.size)
    }

    @Test
    fun `half a coordinate is not a position`() {
        // Defensive, but the API models both as nullable and a pin drawn at
        // latitude-and-no-longitude would land in the Gulf of Guinea.
        val state = TripMapUiState(
            members = listOf(member(1, lat = 18.79), member(2, lng = 98.98)),
        )

        assertTrue(state.placed.isEmpty())
        assertEquals(2, state.unplaced.size)
    }

    @Test
    fun `a rider who stopped sharing keeps their last position on the map`() {
        // `is_sharing` false means they are no longer reporting, not that
        // where they were is a secret — the map shows the last known point and
        // the row says so.
        val stopped = member(1, 18.79, 98.98, sharing = false)
        val state = TripMapUiState(members = listOf(stopped))

        assertEquals(listOf(stopped), state.placed)
    }

    @Test
    fun `an empty trip places nobody without falling over`() {
        val state = TripMapUiState(members = emptyList())

        assertTrue(state.placed.isEmpty())
        assertTrue(state.unplaced.isEmpty())
    }

    @Test
    fun `the map calls a rider what the rest of the app calls them`() {
        // Both the pin and the row read MemberPosition.label, which is
        // riderLabel — so a rider who chose a handle is that handle on the
        // map, not the legal name Google supplied.
        val rider = member(1, 18.79, 98.98)
            .copy(displayName = "Poom Sukjai", username = "speedy")

        assertEquals("speedy", rider.label)
        assertEquals(riderLabel("speedy", "Poom Sukjai", "Rider 1"), rider.label)
        assertEquals("Poom Sukjai", rider.copy(username = null).label)
        assertEquals("Poom Sukjai", rider.copy(username = "  ").label)
    }

    @Test
    fun `a member's fix converts to a plain point, and a missing one does not`() {
        // What the camera, the marker slide and the ordering all read from.
        assertEquals(LatLng(18.79, 98.98), member(1, 18.79, 98.98).latLng)
        assertNull(member(2).latLng)
        assertNull(member(3, lat = 18.79).latLng)
    }

    @Test
    fun `a rider's colour is stable, so the pin and the row always match`() {
        // The map pin and the list dot both call riderColor with the user id.
        // If that ever stopped being a pure function of the id, the two halves
        // of this screen would disagree about who is who.
        repeat(3) {
            assertEquals(riderColor(7L), riderColor(7L))
        }
        assertEquals(riderColor(7L), riderColor(7L + 0))
    }
}
