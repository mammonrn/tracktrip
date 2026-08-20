package app.ptrip.tracktrip.map

import app.ptrip.tracktrip.data.Waypoint

/**
 * Every coordinate one route has to touch, in the order it touches them.
 *
 * ## Why a route is not two points any more
 *
 * It never was, and the bug was that the app said it was. A trip's stops were
 * drawn as pins and left out of the routing call entirely, so the line ran
 * from the start to the finish past everything the rider had planned the ride
 * around — visibly wrong on any trip with a viewpoint on it, and wrong in the
 * distance and the time as well as in the drawing.
 *
 * This is what gets asked for instead. It is also what the request is *keyed*
 * on, which is the other half of the same fix: [RouteRequests] compares two of
 * these to decide whether a fetch is worth spending, and adding a stop has to
 * count as a different route or the answer would never be re-asked for.
 */
data class RoutePlan(
    val from: LatLng,
    /** The stops, in order. Empty is ordinary — most trips have none. */
    val via: List<LatLng>,
    val to: LatLng,
) {
    /** The whole thing as one list: start, stops, finish. */
    val points: List<LatLng> get() = buildList {
        add(from)
        addAll(via)
        add(to)
    }
}

/** What a trip's stored geography comes to as a route request. */
object RoutePlans {

    /**
     * The stops a route is drawn through, in `order_index` order.
     *
     * **Planned stops only.** A `live` waypoint is a point somebody dropped
     * because they stopped there — the viewpoint, the place the group actually
     * turned round — and it has no `order_index` because it has no place in a
     * planned route's ordering. Routing through them would redraw everyone's
     * route the moment one rider marked where they had a coffee.
     *
     * Sorted here as well as by the server. The field is nullable in the
     * model, and a null sorts to the end rather than silently to the front,
     * where it would reorder the ride.
     */
    fun via(waypoints: List<Waypoint>): List<LatLng> = waypoints
        .filter { it.isPlanned }
        .sortedBy { it.orderIndex ?: Int.MAX_VALUE }
        .map { LatLng(it.lat, it.lng) }

    /**
     * The plan for a trip, or null when there are not two ends to route
     * between — which is most of a trip's life, and not an error.
     */
    fun of(from: LatLng?, to: LatLng?, waypoints: List<Waypoint>): RoutePlan? {
        if (from == null || to == null) return null
        return RoutePlan(from = from, via = via(waypoints), to = to)
    }
}
