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
 * The rules behind the two fields.
 *
 * The screen that draws them needs a device; every decision it makes does not.
 * What is worth holding to in a test is the part that costs money or reads as
 * a broken app: which halves of a draft are written when a rider confirms,
 * whether a draft that changed nothing is allowed to spend a routing request,
 * and who is offered a field whose save would come back 403.
 */
class RouteSetupTest {

    private val chiangMai = LatLng(18.7883, 98.9853)
    private val pai = LatLng(19.3583, 98.4400)
    private val lampang = LatLng(18.2888, 99.4908)

    private fun endpoint(point: LatLng, label: String? = null) =
        TripEndpoint(point.lat, point.lng, label)

    private val nan = LatLng(18.7756, 100.7730)

    private fun planned(id: Long, order: Int?, at: LatLng) = Waypoint(
        id = id,
        name = "Stop $id",
        lat = at.lat,
        lng = at.lng,
        type = Waypoint.TYPE_PLANNED,
        orderIndex = order,
    )

    private fun stop(id: Long, order: Int?) = Waypoint(
        id = id,
        name = "Stop $id",
        lat = 18.0,
        lng = 98.0,
        type = Waypoint.TYPE_PLANNED,
        orderIndex = order,
    )

    @Test
    fun `a field takes the point that was picked for it`() {
        var draft = RouteDraft()
        draft = RouteSetupRules.with(draft, RouteField.FROM, RoutePoint(chiangMai, "Chiang Mai"))

        assertEquals(chiangMai, draft.from?.point)
        assertNull(draft.to)
        // Half a route is not a route: the summary sheet must not appear on it.
        assertFalse(draft.isComplete)

        draft = RouteSetupRules.with(draft, RouteField.TO, RoutePoint(pai, "Pai"))
        assertTrue(draft.isComplete)
        assertEquals(
            listOf(chiangMai, pai),
            RouteSetupRules.plan(draft, emptyList())?.points,
        )
    }

    @Test
    fun `picking an end again replaces it, picking a stop appends`() {
        var draft = RouteSetupRules.with(RouteDraft(), RouteField.FROM, RoutePoint(chiangMai))
        draft = RouteSetupRules.with(draft, RouteField.FROM, RoutePoint(lampang))
        // A rider who changes their mind about where they set off from has one
        // start, not two.
        assertEquals(lampang, draft.from?.point)

        draft = RouteSetupRules.with(draft, RouteField.STOP, RoutePoint(pai, "Pai"))
        draft = RouteSetupRules.with(draft, RouteField.STOP, RoutePoint(chiangMai, "Chiang Mai"))
        // In the order they were added, and never re-ordered — that is the
        // whole of what "append" means here.
        assertEquals(listOf("Pai", "Chiang Mai"), draft.stops.map { it.label })
    }

    @Test
    fun `a stop can be taken back off, and a bad index is not a crash`() {
        var draft = RouteDraft(stops = listOf(RoutePoint(pai, "Pai"), RoutePoint(lampang, "Lampang")))

        draft = RouteSetupRules.withoutStop(draft, 0)
        assertEquals(listOf("Lampang"), draft.stops.map { it.label })

        // Saveable state and a list that shrinks under it can disagree for a
        // frame. Dropping the tap is right; throwing is not.
        assertEquals(draft, RouteSetupRules.withoutStop(draft, 7))
        assertEquals(draft, RouteSetupRules.withoutStop(draft, -1))
    }

    @Test
    fun `the card opens on the route the trip already has`() {
        val draft = RouteSetupRules.fromTrip(
            origin = endpoint(chiangMai, "Home"),
            destination = endpoint(pai, "Pai"),
        )

        // This is requirement four in one line: editing after confirming is
        // the same card, seeded, so changing one end is one tap.
        assertEquals(chiangMai, draft.from?.point)
        assertEquals("Pai", draft.to?.label)
        assertTrue(draft.isComplete)
    }

    @Test
    fun `a trip with no route opens an empty card`() {
        val draft = RouteSetupRules.fromTrip(null, null)

        assertTrue(draft.isEmpty)
        assertNull(RouteSetupRules.plan(draft, emptyList()))
    }

    @Test
    fun `an unchanged route is not worth a routing request`() {
        val draft = RouteSetupRules.fromTrip(endpoint(chiangMai), endpoint(pai))

        // The line that keeps the card off the LocationIQ bill: the trip's own
        // route was fetched for exactly this plan, so the summary reads that
        // rather than buying the same answer twice.
        assertTrue(
            RouteSetupRules.matchesTrip(draft, emptyList(), endpoint(chiangMai), endpoint(pai))
        )
    }

    @Test
    fun `a trip's own stops are on both sides, so they change nothing`() {
        val saved = listOf(planned(1, order = 0, at = lampang))
        val draft = RouteSetupRules.fromTrip(endpoint(chiangMai), endpoint(pai))

        // The stop is already on the trip and already on its fetched route.
        // Opening the card to look at it must not re-ask for the same road.
        assertTrue(
            RouteSetupRules.matchesTrip(draft, saved, endpoint(chiangMai), endpoint(pai))
        )
    }

    @Test
    fun `a stop the draft added is a different road`() {
        val draft = RouteSetupRules.fromTrip(endpoint(chiangMai), endpoint(pai))
            .let { RouteSetupRules.with(it, RouteField.STOP, RoutePoint(lampang, "Lampang")) }

        // The bug this whole change is about, at the level it can be tested:
        // same two ends, entirely different ride, and answering it with the
        // trip's line would quote a distance that skips the stop.
        assertFalse(
            RouteSetupRules.matchesTrip(draft, emptyList(), endpoint(chiangMai), endpoint(pai))
        )
    }

    @Test
    fun `renaming an end does not move it`() {
        val draft = RouteSetupRules.fromTrip(endpoint(chiangMai, "Home"), endpoint(pai, "Pai"))
        val renamed = draft.copy(to = RoutePoint(pai, "The good coffee place"))

        // Coordinates decide, labels do not. Re-routing because somebody typed
        // a nicer name would be a request bought with nothing.
        assertTrue(
            RouteSetupRules.matchesTrip(
                renamed,
                emptyList(),
                endpoint(chiangMai, "Home"),
                endpoint(pai, "Pai"),
            )
        )
    }

    @Test
    fun `moving one end is worth a request`() {
        val draft = RouteSetupRules.fromTrip(endpoint(chiangMai), endpoint(pai))
        val moved = draft.copy(to = RoutePoint(lampang))

        assertFalse(
            RouteSetupRules.matchesTrip(moved, emptyList(), endpoint(chiangMai), endpoint(pai))
        )
    }

    @Test
    fun `a half-set draft matches nothing`() {
        val draft = RouteDraft(from = RoutePoint(chiangMai))

        // There is no route to compare, so there is nothing to fetch either.
        assertFalse(
            RouteSetupRules.matchesTrip(draft, emptyList(), endpoint(chiangMai), endpoint(pai))
        )
        assertFalse(RouteSetupRules.matchesTrip(draft, emptyList(), null, null))
    }

    @Test
    fun `the draft's plan is the order confirming will write`() {
        // The trip already has two stops; the rider adds a third on the card.
        // The preview has to thread all three in that order, because that is
        // what nextOrderIndex is about to save.
        val saved = listOf(
            planned(1, order = 0, at = lampang),
            planned(2, order = 1, at = pai),
        )
        val draft = RouteSetupRules
            .fromTrip(endpoint(chiangMai), endpoint(pai))
            .let { RouteSetupRules.with(it, RouteField.STOP, RoutePoint(nan, "Nan")) }

        val plan = RouteSetupRules.plan(draft, saved)

        assertEquals(chiangMai, plan?.from)
        assertEquals(listOf(lampang, pai, nan), plan?.via)
        assertEquals(pai, plan?.to)
        assertEquals(listOf(chiangMai, lampang, pai, nan, pai), plan?.points)
    }

    @Test
    fun `a half-set draft has no plan to ask for`() {
        assertNull(RouteSetupRules.plan(RouteDraft(from = RoutePoint(chiangMai)), emptyList()))
        assertNull(RouteSetupRules.plan(RouteDraft(), emptyList()))
    }

    @Test
    fun `an end that did not move costs no write`() {
        val current = endpoint(chiangMai, "Home")

        assertNull(RouteSetupRules.endpointToSave(RoutePoint(chiangMai, "Home"), current))
        assertNull(RouteSetupRules.endpointToSave(null, current))
    }

    @Test
    fun `a moved or renamed end is written`() {
        val current = endpoint(chiangMai, "Home")

        assertEquals(
            endpoint(pai, "Pai"),
            RouteSetupRules.endpointToSave(RoutePoint(pai, "Pai"), current),
        )
        // A label is part of what an endpoint is, so renaming is a write even
        // though the flag does not move.
        assertEquals(
            endpoint(chiangMai, "The house"),
            RouteSetupRules.endpointToSave(RoutePoint(chiangMai, "The house"), current),
        )
    }

    @Test
    fun `an end picked with no name is saved with no label`() {
        // A long press has nothing to call the spot, and the server takes a
        // null label happily — an empty string is not the same thing.
        assertEquals(
            endpoint(chiangMai, null),
            RouteSetupRules.endpointToSave(RoutePoint(chiangMai, "   "), null),
        )
    }

    @Test
    fun `stops go on after the ones already there`() {
        assertEquals(0, RouteSetupRules.nextOrderIndex(emptyList()))
        assertEquals(3, RouteSetupRules.nextOrderIndex(listOf(stop(1, 0), stop(2, 2))))
        // The server requires a non-negative integer on a planned waypoint, so
        // a trip whose stops all arrived without one still starts at zero.
        assertEquals(0, RouteSetupRules.nextOrderIndex(listOf(stop(1, null))))
    }

    @Test
    fun `the next stop is numbered from what is already on the route`() {
        // What the naming dialog prefills after a press and hold, so the whole
        // gesture is: hold, confirm. Nothing to type.
        assertEquals(1, RouteSetupRules.nextStopNumber(emptyList(), RouteDraft()))

        val saved = listOf(planned(1, order = 0, at = lampang), planned(2, order = 1, at = pai))
        assertEquals(3, RouteSetupRules.nextStopNumber(saved, RouteDraft()))

        // The trip's stops and the draft's are one sequence on the route, so
        // they are one sequence in the numbering too.
        val withDraft = RouteDraft(stops = listOf(RoutePoint(nan, "Nan")))
        assertEquals(4, RouteSetupRules.nextStopNumber(saved, withDraft))
    }

    @Test
    fun `a live drop is not counted in the stop numbering`() {
        // It is not on the route, so it is not a stop number. Counting it
        // would leave a gap in the sequence a rider can see in the sheet.
        val mixed = listOf(
            planned(1, order = 0, at = lampang),
            Waypoint(2, "Coffee", pai.lat, pai.lng, Waypoint.TYPE_LIVE, orderIndex = null),
        )

        assertEquals(2, RouteSetupRules.nextStopNumber(mixed, RouteDraft()))
    }

    @Test
    fun `a prefilled number is a name the server will take`() {
        // The whole point of prefilling: confirm without typing has to be a
        // save that works, not a disabled button.
        assertTrue(RouteSetupRules.isStopNameValid("Stop 3"))
        assertTrue(RouteSetupRules.isStopNameValid("จุดแวะ 3"))
    }

    @Test
    fun `a stop has to be called something`() {
        assertFalse(RouteSetupRules.isStopNameValid(""))
        assertFalse(RouteSetupRules.isStopNameValid("   "))
        assertTrue(RouteSetupRules.isStopNameValid("Fuel"))
        // The same bound the server puts on it, so a rider learns while they
        // are typing rather than when the confirm fails.
        assertTrue(RouteSetupRules.isStopNameValid("a".repeat(MapPlacementRules.NAME_MAX_LENGTH)))
        assertFalse(RouteSetupRules.isStopNameValid("a".repeat(MapPlacementRules.NAME_MAX_LENGTH + 1)))
    }

    @Test
    fun `who may set the ends, and who may only add stops`() {
        // Mirrors the server exactly: requireTripOwner on PATCH /trips/:id,
        // requireActiveTrip on posting a waypoint.
        assertTrue(RouteSetupRules.canEditEnds(isOwner = true))
        assertFalse(RouteSetupRules.canEditEnds(isOwner = false))

        assertTrue(RouteSetupRules.canAddStops(isTripActive = true))
        assertFalse(RouteSetupRules.canAddStops(isTripActive = false))

        // A member on a running trip gets the card for its stops; nobody gets
        // it on a trip that has ended and that they do not own.
        assertTrue(RouteSetupRules.canSetUpRoute(isOwner = false, isTripActive = true))
        assertTrue(RouteSetupRules.canSetUpRoute(isOwner = true, isTripActive = false))
        assertFalse(RouteSetupRules.canSetUpRoute(isOwner = false, isTripActive = false))
    }

    @Test
    fun `a draft stop is drawn like a real one and tells itself apart from one`() {
        val draft = RouteDraft(stops = listOf(RoutePoint(pai, "Pai"), RoutePoint(lampang, "Lampang")))
        val drawn = RouteSetupRules.draftWaypoints(draft)

        assertEquals(listOf("Pai", "Lampang"), drawn.map { it.name })
        assertEquals(listOf(0, 1), drawn.map { it.orderIndex })
        // Negative, so a tap can tell "take this off my list" from "ask the
        // server to delete this". Nothing real can collide: ids are rowids.
        assertTrue(drawn.all { RouteSetupRules.isDraftId(it.id) })
        assertEquals(listOf(0, 1), drawn.map { RouteSetupRules.draftIndex(it.id) })
    }

    @Test
    fun `a saved waypoint is never mistaken for a draft one`() {
        assertFalse(RouteSetupRules.isDraftId(1L))
        assertFalse(RouteSetupRules.isDraftId(0L))
        assertNull(RouteSetupRules.draftIndex(42L))
    }

    @Test
    fun `what is in a field, so re-picking opens on it`() {
        val draft = RouteDraft(from = RoutePoint(chiangMai, "Home"), to = RoutePoint(pai, "Pai"))

        assertEquals("Home", RouteSetupRules.at(draft, RouteField.FROM)?.label)
        assertEquals("Pai", RouteSetupRules.at(draft, RouteField.TO)?.label)
        // A stop is always a new one — there is no stop field to re-open.
        assertNull(RouteSetupRules.at(draft, RouteField.STOP))
    }
}

/**
 * How a duration reads.
 *
 * Small arithmetic, and every one of its edges is the sort that only gets
 * noticed in the one language nobody tested: an exact two hours must not read
 * "2 hr 0 min", and a missing figure must produce no sentence at all rather
 * than "0 min", which would be a claim that the ride is instant.
 */
class RouteEtaTest {

    @Test
    fun `under an hour is minutes`() {
        assertEquals(0 to 25, RouteEta.split(25))
        assertEquals(0 to 0, RouteEta.split(0))
    }

    @Test
    fun `over an hour splits`() {
        assertEquals(2 to 5, RouteEta.split(125))
    }

    @Test
    fun `an exact hour has no minutes to say`() {
        assertEquals(2 to 0, RouteEta.split(120))
    }

    @Test
    fun `nothing said produces nothing to say`() {
        assertNull(RouteEta.split(null))
        // Defensive: the figure is the routing service's, and a negative one
        // would draw a time in the past rather than an error.
        assertNull(RouteEta.split(-5))
    }
}
