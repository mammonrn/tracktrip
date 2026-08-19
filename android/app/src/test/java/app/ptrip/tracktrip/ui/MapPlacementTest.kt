package app.ptrip.tracktrip.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may put what on the map.
 *
 * These rules are the server's, restated on the phone so that a rider is
 * never offered a button whose only possible answer is 403. That makes the
 * pair of them the thing to keep honest: every case below names the route
 * that enforces it, so a change on one side has somewhere to fail.
 */
class MapPlacementTest {

    @Test
    fun `an owner of a running trip can place all three`() {
        assertEquals(
            listOf(MapPlacement.ORIGIN, MapPlacement.DESTINATION, MapPlacement.WAYPOINT),
            MapPlacementRules.allowed(isOwner = true, isTripActive = true),
        )
    }

    @Test
    fun `a member of a running trip can only drop a stop`() {
        // `PATCH /trips/:id` is behind requireTripOwner. Offering the two ends
        // to a member would be offering them a refusal.
        assertEquals(
            listOf(MapPlacement.WAYPOINT),
            MapPlacementRules.allowed(isOwner = false, isTripActive = true),
        )
    }

    @Test
    fun `the two ends stay editable after the ride is over`() {
        // Deliberate, and it matches `PATCH /trips/:id`, which has no
        // requireActiveTrip on it: writing down where a ride actually
        // finished is something people do when they get home.
        assertEquals(
            listOf(MapPlacement.ORIGIN, MapPlacement.DESTINATION),
            MapPlacementRules.allowed(isOwner = true, isTripActive = false),
        )
    }

    @Test
    fun `a member looking at a finished ride can place nothing`() {
        // And so the screen opens no dialog at all, rather than an empty one.
        assertTrue(MapPlacementRules.allowed(isOwner = false, isTripActive = false).isEmpty())
    }

    @Test
    fun `a stop must be named, an end of the trip need not be`() {
        assertTrue(MapPlacementRules.nameRequired(MapPlacement.WAYPOINT))
        assertFalse(MapPlacementRules.nameRequired(MapPlacement.ORIGIN))
        assertFalse(MapPlacementRules.nameRequired(MapPlacement.DESTINATION))

        assertFalse(MapPlacementRules.isNameValid(MapPlacement.WAYPOINT, "   "))
        assertTrue(MapPlacementRules.isNameValid(MapPlacement.ORIGIN, ""))
    }

    @Test
    fun `names are bounded by whichever endpoint they are going to`() {
        // 60 for a waypoint and 120 for a label, matching validateWaypointInput
        // and validateTripEndpoint. Learning this from a red button beats
        // learning it from a 400 after the dialog has closed.
        val sixtyOne = "x".repeat(61)
        assertFalse(MapPlacementRules.isNameValid(MapPlacement.WAYPOINT, sixtyOne))
        assertTrue(MapPlacementRules.isNameValid(MapPlacement.DESTINATION, sixtyOne))

        assertFalse(MapPlacementRules.isNameValid(MapPlacement.DESTINATION, "x".repeat(121)))
    }

    @Test
    fun `trailing spaces do not make a name long enough or too long`() {
        assertTrue(MapPlacementRules.isNameValid(MapPlacement.WAYPOINT, "  ปั๊มน้ำมัน  "))
        assertTrue(MapPlacementRules.isNameValid(MapPlacement.WAYPOINT, "x".repeat(60) + "   "))
    }

    @Test
    fun `a stop can be removed by its author or by the trip owner`() {
        // The same two the DELETE route allows, and nobody else.
        assertTrue(MapPlacementRules.canRemoveWaypoint(isOwner = true, addedBy = 9L, currentUserId = 1L))
        assertTrue(MapPlacementRules.canRemoveWaypoint(isOwner = false, addedBy = 1L, currentUserId = 1L))
        assertFalse(MapPlacementRules.canRemoveWaypoint(isOwner = false, addedBy = 9L, currentUserId = 1L))
    }

    @Test
    fun `a stop whose author is unknown is only the owner's to remove`() {
        // `added_by` is absent from a point written by a build that did not
        // send it. Guessing "probably mine" would put a button in front of a
        // rider that answers 403.
        assertFalse(MapPlacementRules.canRemoveWaypoint(isOwner = false, addedBy = null, currentUserId = 1L))
        assertFalse(MapPlacementRules.canRemoveWaypoint(isOwner = false, addedBy = 5L, currentUserId = null))
        assertTrue(MapPlacementRules.canRemoveWaypoint(isOwner = true, addedBy = null, currentUserId = null))
    }
}
