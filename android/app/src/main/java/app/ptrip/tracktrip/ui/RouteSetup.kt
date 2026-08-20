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
 * [STOP] is the one that is not a field on the card. It appends rather than
 * replaces, which is why it is here rather than being a nullable index: stops
 * go on in the order they were added and are never re-ordered.
 */
enum class RouteField {
    /** Where the ride starts — the trip's origin. */
    FROM,

    /** Where it is going — the trip's destination. */
    TO,

    /** A stop, appended after the ones already on the draft. */
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
    /** Stops, in the order they were added. Never re-ordered — see [RouteField.STOP]. */
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
