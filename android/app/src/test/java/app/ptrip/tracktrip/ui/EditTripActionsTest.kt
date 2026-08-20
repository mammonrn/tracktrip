package app.ptrip.tracktrip.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * That saving a trip's name is a write to the name.
 *
 * ## Why this exists
 *
 * A rider opened Edit trip on a trip with a route on it — both ends, its
 * stops, its distance and its time — pressed Save on the *name*, and the route
 * section underneath went back to "Choose a starting point" with every
 * confirmed stop gone from the screen.
 *
 * Nothing had been lost. `PATCH /trips/:id` is a partial update and a rename
 * sends only `name`, so the server never touched the route. What the press
 * touched was the *draft*: the name's Save and the back arrow shared one
 * `leave()` between them, and `leave()` calls
 * `TripMapViewModel.closeRouteSetup()` — which resets the draft to an empty
 * `RouteDraft`, which is exactly what the route section draws when it says
 * "Choose a starting point".
 *
 * So the two exits are written down separately and asserted apart. The bug was
 * not a wrong line; it was two different intentions sharing one.
 */
class EditTripActionsTest {

    private val done = mutableListOf<String>()

    /** The renaming half, with the server always saying yes. */
    private fun actions(
        renameSucceeds: Boolean = true,
    ): EditTripActions = EditTripActions(
        rename = { name, onRenamed ->
            done += "rename:$name"
            if (renameSucceeds) onRenamed()
        },
        onRenamed = { done += "refreshTrips" },
        discardRouteDraft = { done += "discardRouteDraft" },
        goBack = { done += "goBack" },
    )

    @Test
    fun `saving the name does not touch the route`() {
        actions().save("Sunday run")

        // The regression. `discardRouteDraft` in this list is the route
        // section going blank under a rider who asked about a name — whether
        // the route was confirmed and being looked at, or half-edited on this
        // screen, since the draft is the same one either way.
        assertEquals(listOf("rename:Sunday run", "refreshTrips"), done)
    }

    @Test
    fun `saving the name does not leave the screen`() {
        actions().save("Sunday run")

        // Leaving was the old feedback for a save, and it was also the bug:
        // the way off this screen is the way that discards the draft. A rider
        // fixing a typo stays where they are, looking at what they were
        // looking at.
        assertEquals(false, done.contains("goBack"))
    }

    @Test
    fun `a refused rename changes nothing at all`() {
        actions(renameSucceeds = false).save("")

        // No callback, so nothing downstream runs — and in particular the
        // trip list is not refreshed for a name that was never written.
        assertEquals(listOf("rename:"), done)
    }

    @Test
    fun `leaving is what throws the draft away`() {
        actions().leave()

        // The other half, and the reason `discardRouteDraft` exists at all:
        // the map seeds its route list from the trip, so a draft left sitting
        // on the shared view model is what the map would open on. Discarded
        // before the pop, so nothing composes against a screen already gone.
        assertEquals(listOf("discardRouteDraft", "goBack"), done)
    }
}
