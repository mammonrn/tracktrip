package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.map.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When "use my current location" works, and what it says when it does not.
 *
 * Most points a rider places are where they already are — the start of a ride
 * above all — and the phone has that coordinate already. The shortcut spends
 * no search request on it. What needs testing is the *unavailable* half: a
 * greyed row with no reason is the thing that gets tapped over and over.
 */
class CurrentLocationRulesTest {

    private val here = LatLng(19.9105, 99.8406)

    @Test
    fun `a fix and permission means the shortcut is ready`() {
        assertEquals(
            CurrentLocation.READY,
            CurrentLocationRules.state(hasPermission = true, location = here)
        )
        assertTrue(CurrentLocationRules.isUsable(hasPermission = true, location = here))
    }

    @Test
    fun `no permission is reported as no permission, not as no fix`() {
        // Both produce a null location, so the order of the checks is the
        // whole behaviour: telling a rider to wait for GPS when the app was
        // never allowed to ask is telling them to wait for something that
        // cannot arrive.
        assertEquals(
            CurrentLocation.NO_PERMISSION,
            CurrentLocationRules.state(hasPermission = false, location = null)
        )
    }

    @Test
    fun `a stale location without permission is still no permission`() {
        // A fix left over from before permission was revoked must not keep the
        // row alive: the app is no longer allowed to know where the phone is,
        // whatever it happens to still be holding.
        assertEquals(
            CurrentLocation.NO_PERMISSION,
            CurrentLocationRules.state(hasPermission = false, location = here)
        )
        assertFalse(CurrentLocationRules.isUsable(hasPermission = false, location = here))
    }

    @Test
    fun `permission but no fix yet is worth waiting for`() {
        // Indoors, or the first seconds after the screen opened. The live feed
        // fills this in on its own, so the wording says wait rather than
        // sending the rider to the settings.
        assertEquals(
            CurrentLocation.NO_FIX,
            CurrentLocationRules.state(hasPermission = true, location = null)
        )
        assertFalse(CurrentLocationRules.isUsable(hasPermission = true, location = null))
    }

    @Test
    fun `only READY is tappable, in every combination of the two inputs`() {
        // The row is drawn in every state — a missing row explains nothing —
        // so this is what stops the other two doing anything when pressed.
        // Written across the whole input space so the two functions cannot
        // drift apart: the wording comes from one and the click from the other.
        listOf(true, false).forEach { permission ->
            listOf(here, null).forEach { location ->
                val state = CurrentLocationRules.state(permission, location)
                assertEquals(
                    "permission=$permission location=$location",
                    state == CurrentLocation.READY,
                    CurrentLocationRules.isUsable(permission, location),
                )
            }
        }
    }

    @Test
    fun `the shortcut is offered for every placement, not only the start`() {
        // "I am here, let's meet here" is a finish or a stop just as often as
        // it is a start. The shortcut hands the same coordinate to the same
        // dialog, so whatever that dialog offers this rider is what they get.
        val owner = MapPlacementRules.allowed(isOwner = true, isTripActive = true)
        assertTrue(MapPlacement.ORIGIN in owner)
        assertTrue(MapPlacement.DESTINATION in owner)
        assertTrue(MapPlacement.WAYPOINT in owner)

        // And a member on a running trip still gets the stop.
        val member = MapPlacementRules.allowed(isOwner = false, isTripActive = true)
        assertEquals(listOf(MapPlacement.WAYPOINT), member)
    }

    @Test
    fun `a point from the shortcut carries no name, exactly like a long press`() {
        // "My location" as a prefilled label would be a name to delete before
        // typing the real one, and it means nothing on the map an hour later.
        // The two ends take no label; a stop still asks for one.
        val fromShortcut = PendingPlacement(here)

        assertEquals("", fromShortcut.name)
        assertTrue(MapPlacementRules.isNameValid(MapPlacement.ORIGIN, fromShortcut.name))
        assertTrue(MapPlacementRules.isNameValid(MapPlacement.DESTINATION, fromShortcut.name))
        assertFalse(MapPlacementRules.isNameValid(MapPlacement.WAYPOINT, fromShortcut.name))
    }

    @Test
    fun `the shortcut places the exact fix, not a rounded one`() {
        // It exists because pressing and holding on your own dot is imprecise.
        // Handing the dialog anything but the fix itself would give that back.
        val placement = PendingPlacement(here)

        assertEquals(here.lat, placement.point.lat, 0.0)
        assertEquals(here.lng, placement.point.lng, 0.0)
    }
}
