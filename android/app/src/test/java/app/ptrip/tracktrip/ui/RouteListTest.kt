package app.ptrip.tracktrip.ui

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
            RouteSetupRules.plan(moved, emptyList())?.points,
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
    fun `only a rider who may set the ends may drag one`() {
        // PATCH /trips/:id is owner-only, so a member dragging a stop into the
        // start would be offered a re-order whose save comes back 403 — which
        // reads as a broken app rather than as a rule.
        assertEquals(0..3, RouteSetupRules.movableRows(route, canEditEnds = true))
        assertEquals(1..2, RouteSetupRules.movableRows(route, canEditEnds = false))

        assertTrue(RouteSetupRules.canMoveRow(route, 0, canEditEnds = true))
        assertFalse(RouteSetupRules.canMoveRow(route, 0, canEditEnds = false))
        // A member may still order their own stops.
        assertTrue(RouteSetupRules.canMoveRow(route, 1, canEditEnds = false))
    }

    @Test
    fun `a route with nothing to re-order offers no drag at all`() {
        val ends = RouteDraft(from = at(chiangMai, "Chiang Mai"), to = at(pai, "Pai"))

        // Two rows and no ends to touch is one row that could move, and one
        // row cannot be re-ordered against itself. An empty range is what
        // stops the screen arming a gesture that can only fail.
        assertTrue(RouteSetupRules.movableRows(ends, canEditEnds = false).isEmpty())
        assertFalse(RouteSetupRules.canMoveRow(ends, 1, canEditEnds = false))

        val oneStop = ends.copy(stops = listOf(at(maeRim, "Mae Rim")))
        assertTrue(RouteSetupRules.movableRows(oneStop, canEditEnds = false).isEmpty())
        // The owner can still move it: for them the ends are in the range.
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
}
