package app.ptrip.tracktrip.map

/**
 * How far along a trip a rider is when there is no road route to measure
 * against.
 *
 * ## Why this is not just [RouteProgress] any more
 *
 * The straight-line fallback measured the gap between the rider and the
 * finish. On a trip with stops on it that is the wrong journey: a ride that
 * goes Chiang Mai → Pai → Mae Hong Son closes very little of the Chiang Mai to
 * Mae Hong Son gap over its first hundred kilometres, and the bar said so —
 * which is a bar about a ride nobody is on.
 *
 * So with stops set, the fallback measures along the legs the rider actually
 * planned: start to first stop, stop to stop, last stop to finish. Still
 * straight lines, still captioned "direct", still honest about not knowing
 * where the roads go — but about the right shape of journey.
 *
 * ## Why a trip with no stops is untouched
 *
 * Because the numbers must not move for anybody who did not ask for this. With
 * an empty `via`, this is [RouteProgress] and nothing else: the same
 * gap-closing measure, the same clamps, the same answers it gave before stops
 * were part of a route.
 */
object DirectProgress {

    /**
     * The fallback measure, or null when there is nothing to measure.
     *
     * Unlike the road-route measure this has no off-route cutoff. Its road
     * counterpart refuses to answer for a rider more than
     * [RouteGeometry.MAX_OFF_ROUTE_KM] from the line, because being far from a
     * *fetched road* usually means the route is not about that rider. This one
     * is the thing that answers when nothing else does, and the measure it
     * replaced never refused either — a bar that vanishes is not a safer
     * answer than a rough one.
     */
    fun of(plan: RoutePlan?, current: LatLng?): RouteGeometry.Progress? {
        if (plan == null || current == null) return null

        if (plan.via.isEmpty()) {
            // Exactly what the bar has always shown on a two-ended trip.
            val fraction = RouteProgress.fraction(plan.from, plan.to, current) ?: return null
            val remaining = RouteProgress.remainingKm(plan.to, current) ?: return null
            return RouteGeometry.Progress(fraction = fraction, remainingKm = remaining)
        }

        val projection = RouteGeometry.nearest(plan.points, current) ?: return null
        if (projection.totalKm <= 0.0) return null
        return RouteGeometry.Progress(
            fraction = (projection.offsetKm / projection.totalKm).coerceIn(0.0, 1.0),
            remainingKm = (projection.totalKm - projection.offsetKm).coerceAtLeast(0.0),
        )
    }
}
