package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route list: one sequence of rows, dragged and crossed off.
 *
 * ## Why the arithmetic is worth a test of its own
 *
 * The list a rider drags rows around in is not what the draft stores. The
 * draft has two named ends and a list of stops; the screen shows one run of
 * rows and lets a finger move any of them past any other. Everything hard
 * about this feature is the translation between those two — a stop dragged to
 * the top *becomes* the start, and the old start becomes the first stop — and
 * every bit of it is arithmetic on indices, which is exactly the sort of thing
 * that looks right and is off by one.
 *
 * The other half is the `order_index` promise: a stop's index is its position
 * in the list and nothing else, so a re-order has to be the whole of the
 * update. That is held here rather than trusted, because the failure is silent
 * — the sheet looks right and the ride is saved in the wrong order.
 */
class RouteListTest {

    private val chiangMai = LatLng(18.7883, 98.9853)
    private val maeRim = LatLng(18.9160, 98.9400)
    private val pai = LatLng(19.3583, 98.4400)
    private val lampang = LatLng(18.2888, 99.4908)

    private fun at(point: LatLng, label: String) = RoutePoint(point, label)

    /** Chiang Mai → (Mae Rim, Lampang) → Pai. Two ends and two stops: four rows. */
    private val route = RouteDraft(
        from = at(chiangMai, "Chiang Mai"),
        to = at(pai, "Pai"),
        stops = listOf(at(maeRim, "Mae Rim"), at(lampang, "Lampang")),
    )

    /** The labels of a draft in riding order, which is what the rows show. */
    private fun order(draft: RouteDraft): List<String> =
        RouteSetupRules.ordered(draft)!!.map { it.label }

    @Test
    fun `the list is the two ends with the stops between them`() {
        assertEquals(4, RouteSetupRules.rowCount(route))

        assertEquals(RouteField.FROM, RouteSetupRules.fieldAtRow(route, 0))
        assertEquals(RouteField.STOP, RouteSetupRules.fieldAtRow(route, 1))
        assertEquals(RouteField.STOP, RouteSetupRules.fieldAtRow(route, 2))
        assertEquals(RouteField.TO, RouteSetupRules.fieldAtRow(route, 3))
        // Off the end is null rather than a crash: saved state and a list that
        // shrank under it can disagree for a frame.
        assertNull(RouteSetupRules.fieldAtRow(route, 4))
        assertNull(RouteSetupRules.fieldAtRow(route, -1))

        assertEquals("Chiang Mai", RouteSetupRules.pointAtRow(route, 0)?.label)
        assertEquals("Mae Rim", RouteSetupRules.pointAtRow(route, 1)?.label)
        assertEquals("Pai", RouteSetupRules.pointAtRow(route, 3)?.label)
        assertNull(RouteSetupRules.pointAtRow(route, 9))

        // The number drawn on a stop row counts from the first stop, not from
        // the top of the list — "1." is the first stop, which is row two.
        assertNull(RouteSetupRules.stopIndexAtRow(route, 0))
        assertEquals(0, RouteSetupRules.stopIndexAtRow(route, 1))
        assertEquals(1, RouteSetupRules.stopIndexAtRow(route, 2))
        assertNull(RouteSetupRules.stopIndexAtRow(route, 3))
    }

    @Test
    fun `an empty end still holds its row`() {
        // The row count must not depend on what is filled in: the row under a
        // rider's finger would move as they picked, and every index in a drag
        // in flight would mean something else halfway through.
        val nothing = RouteDraft()
        assertEquals(2, RouteSetupRules.rowCount(nothing))
        assertEquals(RouteField.FROM, RouteSetupRules.fieldAtRow(nothing, 0))
        assertEquals(RouteField.TO, RouteSetupRules.fieldAtRow(nothing, 1))
        assertNull(RouteSetupRules.pointAtRow(nothing, 0))

        val halfway = RouteDraft(from = at(chiangMai, "Chiang Mai"))
        assertEquals(2, RouteSetupRules.rowCount(halfway))
        assertNull(RouteSetupRules.pointAtRow(halfway, 1))
    }

    @Test
    fun `a stop dragged up one place swaps with the stop above it`() {
        val moved = RouteSetupRules.moved(route, from = 2, to = 1)

        assertEquals(listOf("Chiang Mai", "Lampang", "Mae Rim", "Pai"), order(moved))
        // The ends did not move, because nothing was dragged past them.
        assertEquals("Chiang Mai", moved.from?.label)
        assertEquals("Pai", moved.to?.label)
    }

    @Test
    fun `a stop dragged to the top becomes the start`() {
        val moved = RouteSetupRules.moved(route, from = 1, to = 0)

        // This is the case the whole row-index scheme exists for. A rider who
        // drags a stop above the start is saying "we set off from there" — not
        // "put it back where it was", which is what a stops-only re-order
        // would have had to do.
        assertEquals("Mae Rim", moved.from?.label)
        assertEquals(listOf("Chiang Mai", "Lampang"), moved.stops.map { it.label })
        assertEquals("Pai", moved.to?.label)
        assertEquals(listOf("Mae Rim", "Chiang Mai", "Lampang", "Pai"), order(moved))
    }

    @Test
    fun `the start dragged to the bottom becomes the finish`() {
        val moved = RouteSetupRules.moved(route, from = 0, to = 3)

        assertEquals("Chiang Mai", moved.to?.label)
        assertEquals("Mae Rim", moved.from?.label)
        assertEquals(listOf("Lampang", "Pai"), moved.stops.map { it.label })
    }

    @Test
    fun `a move that changes nothing leaves the draft alone`() {
        // Worth holding: the view model refuses to spend a routing request on
        // a drag that came back to where it started, and it decides that by
        // comparing the draft to itself.
        assertEquals(route, RouteSetupRules.moved(route, 2, 2))
        assertEquals(route, RouteSetupRules.moved(route, 0, 9))
        assertEquals(route, RouteSetupRules.moved(route, -1, 0))
    }

    @Test
    fun `a half-filled route cannot be re-ordered`() {
        val halfway = RouteDraft(from = at(chiangMai, "Chiang Mai"))

        // Refused rather than half-applied. With no finish there is no full
        // list to re-order, and inventing one would promote a point to a slot
        // the rider never chose.
        assertEquals(halfway, RouteSetupRules.moved(halfway, 0, 1))
        assertNull(RouteSetupRules.ordered(halfway))

        // And no handle is offered on it, which is the half that matters on
        // screen: a row that could be dragged but not moved would slide under
        // the finger and snap back, and read as the drag being broken rather
        // than as the route being unfinished.
        assertTrue(RouteSetupRules.movableRows(halfway, canEditEnds = true).isEmpty())
        assertFalse(RouteSetupRules.canMoveRow(halfway, 0, canEditEnds = true))
    }

    @Test
    fun `the order index follows the row straight away`() {
        // The promise this feature is built on: a stop's order_index is its
        // position in the list and nothing else, so re-ordering the list *is*
        // the index update — there is no second field to keep in step.
        val moved = RouteSetupRules.moved(route, from = 2, to = 1)

        assertEquals(
            listOf("Lampang", "Mae Rim"),
            moved.stops.map { it.label },
        )
        // What the map draws, renumbered from the same list.
        assertEquals(
            listOf(0, 1),
            RouteSetupRules.draftWaypoints(moved).map { it.orderIndex },
        )
        assertEquals(
            listOf("Lampang", "Mae Rim"),
            RouteSetupRules.draftWaypoints(moved).map { it.name },
        )
        // And the road, which is what the sheet quotes over the confirm button.
        assertEquals(
            listOf(chiangMai, lampang, maeRim, pai),
            RouteSetupRules.plan(moved)?.points,
        )
    }

    @Test
    fun `crossing off a stop shortens the list, crossing off an end does not`() {
        val withoutStop = RouteSetupRules.withoutRow(route, 1)
        assertEquals(listOf("Lampang"), withoutStop.stops.map { it.label })
        assertEquals(3, RouteSetupRules.rowCount(withoutStop))

        // An end is emptied, not removed: the row goes back to asking to be
        // filled, and the confirm goes back to being unavailable. A list that
        // lost a row here would have no way of ever getting a start again.
        val withoutStart = RouteSetupRules.withoutRow(route, 0)
        assertNull(withoutStart.from)
        assertEquals(4, RouteSetupRules.rowCount(withoutStart))
        assertFalse(withoutStart.isComplete)

        val withoutFinish = RouteSetupRules.withoutRow(route, 3)
        assertNull(withoutFinish.to)
        assertEquals(listOf("Mae Rim", "Lampang"), withoutFinish.stops.map { it.label })

        // Off the end is a dropped tap, not a crash.
        assertEquals(route, RouteSetupRules.withoutRow(route, 7))
    }

    @Test
    fun `re-ordering the route belongs to whoever owns it`() {
        // Stricter than it was, and it had to become stricter the moment a
        // drag started being written. Moving one stop renumbers the ones
        // around it, so a member dragging their own stop up two places
        // rewrites two stops that are not theirs — the server refuses those,
        // and the rider is left with a route half renumbered and no way to
        // tell which half. So the order of the route is the owner's, exactly
        // as its two ends already were.
        assertEquals(0..3, RouteSetupRules.movableRows(route, canEditEnds = true))
        assertTrue(RouteSetupRules.movableRows(route, canEditEnds = false).isEmpty())

        assertTrue(RouteSetupRules.canMoveRow(route, 0, canEditEnds = true))
        assertTrue(RouteSetupRules.canMoveRow(route, 1, canEditEnds = true))
        // Not even their own, and not even a stop in the middle.
        assertFalse(RouteSetupRules.canMoveRow(route, 0, canEditEnds = false))
        assertFalse(RouteSetupRules.canMoveRow(route, 1, canEditEnds = false))
    }

    @Test
    fun `a member still removes their own stop, and not anybody else's`() {
        // The other half of the rule, which did *not* get stricter: DELETE
        // .../waypoints/:id allows the trip's owner or whoever added it.
        val mine = RoutePoint(maeRim, "Mae Rim", savedId = 5L, addedBy = 7L)
        val theirs = RoutePoint(lampang, "Lampang", savedId = 6L, addedBy = 8L)
        val fresh = RoutePoint(pai, "Pai")

        assertTrue(RouteSetupRules.canRemoveStop(mine, isOwner = false, currentUserId = 7L))
        assertFalse(RouteSetupRules.canRemoveStop(theirs, isOwner = false, currentUserId = 7L))
        // The owner may remove any of them.
        assertTrue(RouteSetupRules.canRemoveStop(theirs, isOwner = true, currentUserId = 7L))
        // A stop nothing has been written for is nobody's but the rider's own.
        assertTrue(RouteSetupRules.canRemoveStop(fresh, isOwner = false, currentUserId = 7L))
        assertTrue(RouteSetupRules.canRemoveStop(fresh, isOwner = false, currentUserId = null))

        val draft = RouteDraft(
            from = at(chiangMai, "Chiang Mai"),
            to = at(pai, "Pai"),
            stops = listOf(mine, theirs),
        )
        assertTrue(
            RouteSetupRules.canRemoveRow(draft, 1, canEditEnds = false, canAddStops = true, currentUserId = 7L)
        )
        assertFalse(
            RouteSetupRules.canRemoveRow(draft, 2, canEditEnds = false, canAddStops = true, currentUserId = 7L)
        )
    }

    @Test
    fun `a route with nothing to re-order offers no drag at all`() {
        val ends = RouteDraft(from = at(chiangMai, "Chiang Mai"), to = at(pai, "Pai"))

        // Not a member's, whatever the shape.
        assertTrue(RouteSetupRules.movableRows(ends, canEditEnds = false).isEmpty())
        assertFalse(RouteSetupRules.canMoveRow(ends, 1, canEditEnds = false))

        val oneStop = ends.copy(stops = listOf(at(maeRim, "Mae Rim")))
        assertTrue(RouteSetupRules.movableRows(oneStop, canEditEnds = false).isEmpty())
        // The owner can move it: for them the ends are in the range, so a stop
        // has somewhere to go even when it is the only one.
        assertTrue(RouteSetupRules.canMoveRow(oneStop, 1, canEditEnds = true))
    }

    @Test
    fun `an empty row has neither a handle nor a cross`() {
        val nothing = RouteDraft()

        // There is nothing to drag and nothing to remove — a cross on a row
        // that is already empty gets pressed and does nothing, which reads as
        // the app being broken.
        assertFalse(RouteSetupRules.canMoveRow(nothing, 0, canEditEnds = true))
        assertFalse(
            RouteSetupRules.canRemoveRow(nothing, 0, canEditEnds = true, canAddStops = true)
        )
    }

    @Test
    fun `each row follows the rule the server puts on its half`() {
        // The ends follow PATCH /trips/:id; a stop follows the waypoints
        // route, which refuses a finished trip.
        assertTrue(
            RouteSetupRules.canRemoveRow(route, 0, canEditEnds = true, canAddStops = false)
        )
        assertFalse(
            RouteSetupRules.canRemoveRow(route, 0, canEditEnds = false, canAddStops = true)
        )
        assertTrue(
            RouteSetupRules.canRemoveRow(route, 1, canEditEnds = false, canAddStops = true)
        )
        assertFalse(
            RouteSetupRules.canRemoveRow(route, 1, canEditEnds = true, canAddStops = false)
        )
    }

    @Test
    fun `a list taken apart and put back together is the same route`() {
        // ordered and fromOrdered are inverses, which is what makes a drag
        // safe: every one of them is a round trip through both.
        assertEquals(route, RouteSetupRules.fromOrdered(RouteSetupRules.ordered(route)!!))

        // And the degenerate ends of it, which a drag never produces but a
        // caller could.
        assertEquals(RouteDraft(), RouteSetupRules.fromOrdered(emptyList()))
        assertEquals(
            RouteDraft(from = at(chiangMai, "Chiang Mai")),
            RouteSetupRules.fromOrdered(listOf(at(chiangMai, "Chiang Mai"))),
        )
    }
    // --- the route survives leaving the screen ------------------------------

    private fun planned(id: Long, order: Int, at: LatLng, name: String, by: Long? = 7L) =
        Waypoint(
            id = id,
            name = name,
            lat = at.lat,
            lng = at.lng,
            type = Waypoint.TYPE_PLANNED,
            orderIndex = order,
            addedBy = by,
        )

    @Test
    fun `reopening a trip shows the stops it actually has`() {
        // The bug, at the level it can be tested. A draft seeded from the two
        // ends alone had nothing to load saved stops into, so a rider who
        // added two, left the trip and came back saw From and To with nothing
        // between them — while the pins were still on the map.
        val saved = listOf(
            planned(11, order = 0, at = maeRim, name = "Mae Rim"),
            planned(12, order = 1, at = lampang, name = "Lampang"),
        )

        val draft = RouteSetupRules.fromTrip(
            origin = TripEndpoint(chiangMai.lat, chiangMai.lng, "Chiang Mai"),
            destination = TripEndpoint(pai.lat, pai.lng, "Pai"),
            saved = saved,
        )

        assertEquals(listOf("Mae Rim", "Lampang"), draft.stops.map { it.label })
        assertEquals(listOf(11L, 12L), draft.stops.map { it.savedId })
        assertEquals(4, RouteSetupRules.rowCount(draft))
        assertEquals(
            listOf("Chiang Mai", "Mae Rim", "Lampang", "Pai"),
            RouteSetupRules.ordered(draft)!!.map { it.label },
        )
    }

    @Test
    fun `a live drop never lands on the route list`() {
        // It is not part of the road anybody planned, and putting it on the
        // list would let a re-order give it an order_index the server refuses.
        val mixed = listOf(
            planned(11, order = 0, at = maeRim, name = "Mae Rim"),
            Waypoint(12, "Coffee", pai.lat, pai.lng, Waypoint.TYPE_LIVE, orderIndex = null),
        )

        val draft = RouteSetupRules.fromTrip(null, null, mixed)
        assertEquals(listOf("Mae Rim"), draft.stops.map { it.label })
    }

    @Test
    fun `a list nobody touched is worth no writes at all`() {
        val saved = listOf(
            planned(11, order = 0, at = maeRim, name = "Mae Rim"),
            planned(12, order = 1, at = lampang, name = "Lampang"),
        )
        val draft = RouteSetupRules.fromTrip(null, null, saved)

        // The common case: open the list, change one end, confirm. Nothing
        // about the stops moved, so nothing about them is sent.
        assertEquals(emptyList<WaypointEdit>(), RouteSetupRules.waypointEdits(saved, draft.stops))
    }

    @Test
    fun `a drag becomes one patch per stop that actually moved`() {
        val saved = listOf(
            planned(11, order = 0, at = maeRim, name = "Mae Rim"),
            planned(12, order = 1, at = lampang, name = "Lampang"),
        )
        val draft = RouteSetupRules.fromTrip(
            TripEndpoint(chiangMai.lat, chiangMai.lng, null),
            TripEndpoint(pai.lat, pai.lng, null),
            saved,
        )
        val swapped = RouteSetupRules.moved(draft, from = 2, to = 1)

        assertEquals(
            listOf(
                WaypointEdit.Move(12L, 0, "Lampang"),
                WaypointEdit.Move(11L, 1, "Mae Rim"),
            ),
            RouteSetupRules.waypointEdits(saved, swapped.stops),
        )
    }

    @Test
    fun `a reopened list posts only what is new`() {
        // The other half of the bug: a seeded list confirmed as it was would,
        // under the old one-POST-per-stop commit, have duplicated every stop
        // that was already there. Every visit to the screen would double the
        // route.
        val saved = listOf(planned(11, order = 0, at = maeRim, name = "Mae Rim"))
        val draft = RouteSetupRules.fromTrip(null, null, saved)
        val withNew = RouteSetupRules.with(draft, RouteField.STOP, RoutePoint(lampang, "Lampang"))

        val edits = RouteSetupRules.waypointEdits(saved, withNew.stops)
        assertEquals(1, edits.size)
        val add = edits.single() as WaypointEdit.Add
        assertEquals("Lampang", add.stop.label)
        assertEquals(1, add.orderIndex)
    }

    @Test
    fun `crossing a saved stop off deletes it, and deletes come first`() {
        val saved = listOf(
            planned(11, order = 0, at = maeRim, name = "Mae Rim"),
            planned(12, order = 1, at = lampang, name = "Lampang"),
        )
        val draft = RouteSetupRules.fromTrip(null, null, saved)
        // Cross off the first, and add a new one after the second.
        val edited = RouteSetupRules
            .withoutStop(draft, 0)
            .let { RouteSetupRules.with(it, RouteField.STOP, RoutePoint(pai, "Pai")) }

        val edits = RouteSetupRules.waypointEdits(saved, edited.stops)

        // Removal first, so a route that shrank never briefly holds more stops
        // than the rider is looking at.
        assertEquals(WaypointEdit.Remove(11L), edits.first())
        // Lampang slid up into index 0, so it moved.
        assertTrue(edits.contains(WaypointEdit.Move(12L, 0, "Lampang")))
        assertTrue(edits.any { it is WaypointEdit.Add && it.orderIndex == 1 })
    }

    @Test
    fun `arbitrary order indexes on the server come out renumbered`() {
        // The server does not require them to be contiguous, and a route that
        // has been edited for a while will not be. The rows are the truth, so
        // the write is 0..n-1 with no gaps.
        val saved = listOf(
            planned(11, order = 4, at = maeRim, name = "Mae Rim"),
            planned(12, order = 9, at = lampang, name = "Lampang"),
        )
        val draft = RouteSetupRules.fromTrip(null, null, saved)

        assertEquals(
            listOf(
                WaypointEdit.Move(11L, 0, "Mae Rim"),
                WaypointEdit.Move(12L, 1, "Lampang"),
            ),
            RouteSetupRules.waypointEdits(saved, draft.stops),
        )
    }

    @Test
    fun `a stop somebody else deleted while the list was open is not resurrected`() {
        // The list is minutes old by the time confirm is pressed, and the trip
        // is shared. Re-adding a stop that has since been deleted would put it
        // back behind the other rider's back.
        val saved = listOf(planned(11, order = 0, at = maeRim, name = "Mae Rim"))
        val stale = listOf(
            RoutePoint(maeRim, "Mae Rim", savedId = 11L),
            RoutePoint(lampang, "Lampang", savedId = 99L),
        )

        val edits = RouteSetupRules.waypointEdits(saved, stale)
        assertTrue(edits.none { it is WaypointEdit.Add })
        assertTrue(edits.none { it is WaypointEdit.Remove })
        // 99 is simply dropped; 11 did not move.
        assertEquals(emptyList<WaypointEdit>(), edits)
    }

    @Test
    fun `an unnamed stop is never sent`() {
        // The server refuses an unnamed waypoint, so one is never offered to
        // it. The picker will not produce one; this is the guard behind that.
        val edits = RouteSetupRules.waypointEdits(
            emptyList(),
            listOf(RoutePoint(maeRim, "   "), RoutePoint(lampang, "Lampang")),
        )

        assertEquals(1, edits.size)
        assertEquals("Lampang", (edits.single() as WaypointEdit.Add).stop.label)
    }

    @Test
    fun `renaming a saved stop is a patch, not a delete and a create`() {
        val saved = listOf(planned(11, order = 0, at = maeRim, name = "Mae Rim"))
        val renamed = listOf(RoutePoint(maeRim, "Mae Rim fuel", savedId = 11L))

        assertEquals(
            listOf(WaypointEdit.Move(11L, 0, "Mae Rim fuel")),
            RouteSetupRules.waypointEdits(saved, renamed),
        )
    }

    @Test
    fun `a live waypoint is never touched by the sync`() {
        // It is not on the list, so "not in the list" must not read as
        // "the rider crossed it off".
        val saved = listOf(
            planned(11, order = 0, at = maeRim, name = "Mae Rim"),
            Waypoint(12, "Coffee", pai.lat, pai.lng, Waypoint.TYPE_LIVE, orderIndex = null),
        )
        val draft = RouteSetupRules.fromTrip(null, null, saved)

        val edits = RouteSetupRules.waypointEdits(saved, draft.stops)
        assertTrue(edits.none { it is WaypointEdit.Remove && it.id == 12L })
        assertEquals(emptyList<WaypointEdit>(), edits)
    }

}
