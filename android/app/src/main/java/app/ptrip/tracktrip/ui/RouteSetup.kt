package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.map.RoutePlan
import app.ptrip.tracktrip.map.RoutePlans

/**
 * Which part of the route a rider is choosing a point for.
 *
 * ## Why this exists at all
 *
 * The old flow asked the question backwards. A rider found a place, and *then*
 * a dialog asked whether it was the start, the finish or a stop — three
 * buttons, every time, for an answer they had already decided before they
 * started typing. Every route needed two trips through it, and the second one
 * asked the same question again.
 *
 * Naming the slot first turns that round: tapping the "To" field *is* saying
 * "this one is the finish", so the picker that opens has nothing left to ask
 * and the point lands where the rider was already pointing.
 *
 * [STOP] is the one that is not a row a rider taps to change. It appends
 * rather than replaces, which is why it is here rather than being a nullable
 * index: a stop goes on the end of the ride, and is dragged from there to
 * wherever in the ride it belongs.
 */
enum class RouteField {
    /** Where the ride starts — the trip's origin. */
    FROM,

    /** Where it is going — the trip's destination. */
    TO,

    /**
     * A stop, appended after the ones already on the draft.
     *
     * Appended and not placed: a rider adding a stop is saying "and this one
     * too", not "and this one third". Where it ends up in the ride is a
     * separate decision, made by dragging it in the route list — see
     * [RouteSetupRules.moved].
     */
    STOP,
}

/**
 * A point a rider has chosen, before it is anything the server knows about.
 *
 * [label] is the place's own name when it came out of the search box, and
 * empty when it came from a long press or from "use my current location" —
 * there is nothing to call a spot on a map that a rider simply pointed at.
 * The two ends are happy with an empty label; a stop is not, because the
 * server refuses an unnamed waypoint.
 */
data class RoutePoint(val point: LatLng, val label: String = "")

/**
 * A route being set up, before anybody has confirmed it.
 *
 * Held rather than written straight through, which is the whole difference
 * between this and what came before. Setting the start used to be a PATCH the
 * moment the dialog closed, so a rider planning a route committed half of it
 * to everybody else's map and then went looking for the other half — and a
 * rider who changed their mind had already published the wrong start.
 *
 * Nothing here is on the server. [RouteSetupRules.nextOrderIndex] and the
 * commit in the view model are what turn it into one PATCH per end that
 * actually moved and one POST per stop.
 */
data class RouteDraft(
    val from: RoutePoint? = null,
    val to: RoutePoint? = null,
    /**
     * Stops, in riding order.
     *
     * The order of this list *is* the `order_index` each stop will be saved
     * with — see [RouteSetupRules.nextOrderIndex] and the commit in the view
     * model, both of which count along it. So dragging a row in the route list
     * re-orders this and nothing else has to be told: the road redraws, the
     * numbers on the map renumber, and the confirm that follows writes the
     * order the rider is looking at.
     */
    val stops: List<RoutePoint> = emptyList(),
) {
    /** Whether there is a route to summarise: both ends chosen. */
    val isComplete: Boolean get() = from != null && to != null

    /** Whether the rider has chosen nothing at all. */
    val isEmpty: Boolean get() = from == null && to == null && stops.isEmpty()
}

/**
 * What a draft may hold, who may commit which half of it, and how it turns
 * into the calls the API already has.
 *
 * The server stays the authority on every rule in here — `requireTripOwner` on
 * `PATCH /trips/:id`, `requireActiveTrip` on posting a waypoint, and
 * `validateWaypointInput` on what a waypoint may contain. This is what stops
 * the screen offering a rider a field whose save comes back 403, which reads
 * as a broken app rather than as a rule.
 */
object RouteSetupRules {

    /**
     * Whether this rider may set the two ends of the route.
     *
     * Owner only, because `PATCH /trips/:id` is owner only. A member who is
     * not the owner still gets the card — seeing where the ride starts and
     * finishes is worth having — but the two fields do not open a picker.
     */
    fun canEditEnds(isOwner: Boolean): Boolean = isOwner

    /**
     * Whether stops may be added.
     *
     * Any member, while the trip is still running: that is exactly what the
     * waypoints route allows. Writes to a finished trip are refused, and a
     * stop on a ride that is over is not information anyone needs.
     */
    fun canAddStops(isTripActive: Boolean): Boolean = isTripActive

    /** Whether the route card is worth showing this rider at all. */
    fun canSetUpRoute(isOwner: Boolean, isTripActive: Boolean): Boolean =
        canEditEnds(isOwner) || canAddStops(isTripActive)

    /**
     * A draft that starts where the trip already does.
     *
     * Seeded rather than blank so that opening the card on a trip whose route
     * is set shows the route, and changing one end is one tap rather than
     * re-entering both. It is also what makes editing after confirming work:
     * the entry point changed, the mechanism did not.
     */
    fun fromTrip(origin: TripEndpoint?, destination: TripEndpoint?): RouteDraft = RouteDraft(
        from = origin?.let { RoutePoint(LatLng(it.lat, it.lng), it.label.orEmpty()) },
        to = destination?.let { RoutePoint(LatLng(it.lat, it.lng), it.label.orEmpty()) },
    )

    /** The draft with [picked] in [field] — replacing an end, appending a stop. */
    fun with(draft: RouteDraft, field: RouteField, picked: RoutePoint): RouteDraft = when (field) {
        RouteField.FROM -> draft.copy(from = picked)
        RouteField.TO -> draft.copy(to = picked)
        RouteField.STOP -> draft.copy(stops = draft.stops + picked)
    }

    /** The draft without the stop at [index]. Out-of-range is a no-op, not a crash. */
    fun withoutStop(draft: RouteDraft, index: Int): RouteDraft {
        if (index !in draft.stops.indices) return draft
        return draft.copy(stops = draft.stops.filterIndexed { at, _ -> at != index })
    }

    /**
     * How many rows the route list has: the two ends, and a stop between them
     * for every stop on the draft.
     *
     * Always at least two, and the two are always the first and the last —
     * whether or not either has been chosen yet. An empty end is a row that
     * says "Choose a starting point", not a row that is missing, because a
     * list whose length changes with what is filled in cannot be dragged
     * against: the row under a rider's finger would move as they picked.
     */
    fun rowCount(draft: RouteDraft): Int = draft.stops.size + 2

    /** The row indices of a draft, first to last. */
    fun rows(draft: RouteDraft): IntRange = 0 until rowCount(draft)

    /** Which part of the route row [index] is, or null when it is off the end. */
    fun fieldAtRow(draft: RouteDraft, index: Int): RouteField? = when (index) {
        0 -> RouteField.FROM
        rowCount(draft) - 1 -> RouteField.TO
        in 1 until rowCount(draft) - 1 -> RouteField.STOP
        else -> null
    }

    /** Which stop row [index] holds, or null when the row is one of the ends. */
    fun stopIndexAtRow(draft: RouteDraft, index: Int): Int? =
        if (fieldAtRow(draft, index) == RouteField.STOP) index - 1 else null

    /** What is in row [index], or null when nothing has been chosen for it yet. */
    fun pointAtRow(draft: RouteDraft, index: Int): RoutePoint? = when (fieldAtRow(draft, index)) {
        RouteField.FROM -> draft.from
        RouteField.TO -> draft.to
        RouteField.STOP -> draft.stops.getOrNull(index - 1)
        null -> null
    }

    /**
     * The whole route as one ordered list, or null while an end is empty.
     *
     * This is the list the rider is actually looking at and dragging rows
     * around in, and the reason a re-order can move a stop past an end at all:
     * on the road there is no difference between the three, only an order.
     */
    fun ordered(draft: RouteDraft): List<RoutePoint>? {
        val from = draft.from ?: return null
        val to = draft.to ?: return null
        return listOf(from) + draft.stops + to
    }

    /**
     * A draft built back out of one ordered list: first is the start, last is
     * the finish, everything between them is a stop.
     *
     * The inverse of [ordered], and the half that makes a drag across the
     * whole list mean something. A stop dragged to the top becomes the start
     * and the old start becomes the first stop — which is what a rider who
     * dragged it there was asking for, and is why this rebuilds the draft from
     * positions rather than trying to keep each point in the slot it began in.
     */
    fun fromOrdered(points: List<RoutePoint>): RouteDraft = when {
        points.isEmpty() -> RouteDraft()
        points.size == 1 -> RouteDraft(from = points.first())
        else -> RouteDraft(
            from = points.first(),
            to = points.last(),
            // Copied, not a view: the caller's list is very often the
            // mutable one a re-order was just done in, and a draft holding a
            // window onto it would change under the screen afterwards.
            stops = points.subList(1, points.size - 1).toList(),
        )
    }

    /**
     * The draft with row [from] dragged to row [to].
     *
     * ## Why order is the only thing this writes
     *
     * A stop's `order_index` is its position in [RouteDraft.stops] and nothing
     * else — the commit counts along the list, and [draftWaypoints] numbers
     * the pins from it. So a re-order *is* the index update: there is no
     * second field to keep in step and no way for the two to disagree. It
     * lands on the draft, which is not the server, so nothing is written to
     * the trip until the rider confirms.
     *
     * A move on an incomplete draft is refused rather than half-applied: with
     * an end still empty there is no full list to re-order, and inventing one
     * would silently promote a stop to a start the rider never chose.
     */
    fun moved(draft: RouteDraft, from: Int, to: Int): RouteDraft {
        val points = ordered(draft) ?: return draft
        if (from !in points.indices || to !in points.indices || from == to) return draft
        val reordered = points.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        return fromOrdered(reordered)
    }

    /**
     * Which rows this rider may drag between.
     *
     * The ends are in it only for a rider who may set them: `PATCH /trips/:id`
     * is owner-only, so letting anybody else drag a stop into the start would
     * offer them a re-order whose save comes back 403 — which reads as a
     * broken app rather than as a rule. A member who may only add stops can
     * still order their own stops.
     *
     * Empty when there is nothing to drag, which a caller has to handle: one
     * row cannot be re-ordered against itself, and a route with an end still
     * empty has no order to change — [moved] refuses one, so a handle offered
     * on it would be a handle that moved the row on screen and nothing in the
     * draft.
     */
    fun movableRows(draft: RouteDraft, canEditEnds: Boolean): IntRange {
        if (ordered(draft) == null) return IntRange.EMPTY
        val last = rowCount(draft) - 1
        val range = if (canEditEnds) 0..last else 1 until last
        return if (range.first >= range.last) IntRange.EMPTY else range
    }

    /** Whether row [index] can be dragged at all. */
    fun canMoveRow(draft: RouteDraft, index: Int, canEditEnds: Boolean): Boolean =
        index in movableRows(draft, canEditEnds) && pointAtRow(draft, index) != null

    /**
     * The draft with row [index] taken off it.
     *
     * An end is emptied rather than removed — the list keeps its shape, the
     * row goes back to asking to be filled, and the confirm button goes back
     * to being unavailable. A stop really does come off: nothing was ever
     * sent, so there is nothing to delete anywhere else.
     */
    fun withoutRow(draft: RouteDraft, index: Int): RouteDraft =
        when (fieldAtRow(draft, index)) {
            RouteField.FROM -> draft.copy(from = null)
            RouteField.TO -> draft.copy(to = null)
            RouteField.STOP -> withoutStop(draft, index - 1)
            null -> draft
        }

    /**
     * Whether row [index] may be cleared by this rider.
     *
     * The ends follow `PATCH /trips/:id`, the stops follow the waypoints
     * route. A row that cannot be cleared shows no cross rather than a cross
     * that does nothing.
     */
    fun canRemoveRow(
        draft: RouteDraft,
        index: Int,
        canEditEnds: Boolean,
        canAddStops: Boolean,
    ): Boolean {
        if (pointAtRow(draft, index) == null) return false
        return when (fieldAtRow(draft, index)) {
            RouteField.FROM, RouteField.TO -> canEditEnds
            RouteField.STOP -> canAddStops
            null -> false
        }
    }

    /** What is already in a field, so re-picking it opens on what is there. */
    fun at(draft: RouteDraft, field: RouteField): RoutePoint? = when (field) {
        RouteField.FROM -> draft.from
        RouteField.TO -> draft.to
        RouteField.STOP -> null
    }

    /**
     * The whole route a draft describes, in the order confirming would write it.
     *
     * Its two ends, threading the stops the trip already has and *then* the
     * ones only the draft holds — which is exactly the order
     * [nextOrderIndex] gives them, so the road quoted in the summary sheet is
     * the road the confirm button is about to save.
     *
     * [saved] is the trip's waypoints; only the planned ones end up on the
     * route. See [RoutePlans.via].
     */
    fun plan(draft: RouteDraft, saved: List<Waypoint>): RoutePlan? {
        val from = draft.from?.point ?: return null
        val to = draft.to?.point ?: return null
        return RoutePlan(
            from = from,
            via = RoutePlans.via(saved) + draft.stops.map { it.point },
            to = to,
        )
    }

    /**
     * Whether the draft is simply the route the trip already has.
     *
     * The single line that keeps this feature off the LocationIQ bill. When it
     * is true the trip's own route has already been fetched — by `loadRoute`,
     * once, for exactly this plan — and the summary reads that instead of
     * spending a second request on the same answer. Which is the common case:
     * a rider opening the card to change one end sees the current route first.
     *
     * Compares the whole route, not just the ends. A draft with a new stop on
     * it is a different road even between the same two towns, and answering it
     * with the trip's line was the bug: the sheet would quote a distance that
     * skipped every stop the rider had just added.
     *
     * Coordinates are compared, labels are not. Renaming the finish does not
     * move it, and re-routing because somebody typed a nicer name would be a
     * request bought with nothing.
     */
    fun matchesTrip(
        draft: RouteDraft,
        saved: List<Waypoint>,
        origin: TripEndpoint?,
        destination: TripEndpoint?,
    ): Boolean {
        val wanted = plan(draft, saved) ?: return false
        val trip = RoutePlans.of(
            from = origin?.let { LatLng(it.lat, it.lng) },
            to = destination?.let { LatLng(it.lat, it.lng) },
            waypoints = saved,
        ) ?: return false
        return wanted == trip
    }

    /**
     * The endpoint to PATCH, or null when this end has not moved.
     *
     * Both halves matter. A rider who opened the card to add a stop must not
     * spend two writes re-setting the ends to where they already were; and a
     * rider who only renamed an end must still spend one, because the label is
     * part of what the endpoint is.
     */
    fun endpointToSave(picked: RoutePoint?, current: TripEndpoint?): TripEndpoint? {
        val wanted = picked?.let {
            TripEndpoint(it.point.lat, it.point.lng, it.label.trim().takeIf(String::isNotEmpty))
        } ?: return null
        return wanted.takeIf { it != current }
    }

    /**
     * The `order_index` the next planned stop takes.
     *
     * One past the highest already on the trip, so stops added today land
     * after stops added yesterday. The server requires the field on a planned
     * waypoint and requires it to be a non-negative integer, so an empty trip
     * starts at zero rather than at whatever a max of nothing would be.
     */
    fun nextOrderIndex(planned: List<Waypoint>): Int =
        (planned.mapNotNull { it.orderIndex }.maxOrNull()?.plus(1) ?: 0).coerceAtLeast(0)

    /**
     * Which stop this next one will be, counting from one.
     *
     * What the naming dialog prefills, so a rider dropping a stop on the map
     * can confirm without typing anything and still get a row that is not
     * identical to every other row. Counts the trip's saved stops and the
     * draft's together, because on the route they are one sequence.
     */
    fun nextStopNumber(saved: List<Waypoint>, draft: RouteDraft): Int =
        nextOrderIndex(saved.filter { it.isPlanned }) + draft.stops.size + 1

    /**
     * Whether a stop can be saved under this name.
     *
     * The same bound the server puts on it, checked here so a rider learns
     * while they are still typing rather than by having the confirm fail.
     */
    fun isStopNameValid(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && trimmed.length <= MapPlacementRules.NAME_MAX_LENGTH
    }

    /**
     * The draft's stops as the map can draw them.
     *
     * Real [Waypoint]s with made-up negative ids, so the map's existing
     * waypoint layer draws them with no idea they are not saved yet — and so a
     * tap can tell one apart from a stop that is really on the trip, which is
     * the difference between removing it from a list and asking the server to
     * delete it. Nothing negative can collide: the server's ids are rowids.
     */
    fun draftWaypoints(draft: RouteDraft): List<Waypoint> =
        draft.stops.mapIndexed { index, stop ->
            Waypoint(
                id = draftId(index),
                name = stop.label,
                lat = stop.point.lat,
                lng = stop.point.lng,
                type = Waypoint.TYPE_PLANNED,
                orderIndex = index,
            )
        }

    /** The id a stop held only in the draft is drawn under. */
    fun draftId(index: Int): Long = -(index + 1L)

    /** Whether this id belongs to a stop nobody has saved yet. */
    fun isDraftId(id: Long): Boolean = id < 0

    /** Which draft stop [id] refers to, or null when it is a saved one. */
    fun draftIndex(id: Long): Int? = if (isDraftId(id)) (-id - 1).toInt() else null
}

/**
 * How long a route takes, in the units a rider would say it in.
 *
 * Split out from the screen because the interesting part is not the sentence
 * but the arithmetic behind it: a route of 125 minutes is "2 hr 5 min", and
 * one of exactly 120 is "2 hr" rather than "2 hr 0 min", which is the sort of
 * thing that only ever gets noticed in the one language nobody tested.
 *
 * The minutes come from LocationIQ, whole, so nothing here rounds anything.
 */
object RouteEta {

    /** Hours and the minutes left over, or null when there is no figure to show. */
    fun split(minutes: Int?): Pair<Int, Int>? {
        if (minutes == null || minutes < 0) return null
        return minutes / 60 to minutes % 60
    }
}
