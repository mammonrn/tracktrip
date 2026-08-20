package app.ptrip.tracktrip.map

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Progress measured along a road, rather than across the gap to the finish.
 *
 * ## What changed, and what did not
 *
 * [RouteProgress] measures the straight line: how much of the crow-flies gap
 * between start and finish has closed. It is honest, it needs nothing but two
 * coordinates, and it is what the bar showed for the app's whole life — and on
 * a mountain road it reads badly low, because the road is half as direct as
 * the line.
 *
 * When the server has managed to fetch a road route (see `GET /directions`),
 * this measures against that instead: the rider is put on the nearest point of
 * the line, and what is left is the road ahead of that point. Both are still
 * here on purpose. The straight line is the fallback for a server with no key,
 * an exhausted quota, a pair of points with no road between them, and a rider
 * who is nowhere near the route — and the screen says which of the two it is
 * showing rather than letting the rider guess.
 *
 * ## Why a rider far from the route gets nothing
 *
 * Projecting somebody onto the nearest point of a line they are not on
 * produces a number: it is just not a number about them. A rider who set off
 * from a different province, or whose trip's route was drawn between two other
 * places entirely, would be told they were 40% of the way along a road they
 * have never been on. Past [MAX_OFF_ROUTE_KM] this answers null and the screen
 * falls back to the straight line, which at least measures something true.
 */
object RouteGeometry {

    /**
     * How far off the line a rider may be and still be considered on it.
     *
     * Twenty kilometres is deliberately loose. A rider on a parallel road, a
     * detour round a landslide, or a fuel stop off the highway is still on
     * this journey, and a tight threshold would flip the bar between "by road"
     * and "direct" every few minutes — which reads as a bug, not as caution.
     * What it rules out is the case it exists for: a route that has nothing to
     * do with where this rider is.
     */
    const val MAX_OFF_ROUTE_KM = 20.0

    /** Kilometres per degree of latitude. Constant enough at any latitude. */
    private const val KM_PER_DEGREE = 111.195

    /** The whole road, end to end. Zero for anything that is not a line. */
    fun lengthKm(points: List<LatLng>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += RouteProgress.distanceKm(points[i], points[i + 1])
        }
        return total
    }

    /**
     * Where the rider sits on the line, as a distance from its start, together
     * with how far off it they are.
     *
     * Null when there is no line to sit on. The off-route distance is returned
     * rather than judged here so that the one threshold lives in one place —
     * [fraction] and [remainingKm] both apply it, and a caller that wants the
     * raw numbers (a test, mostly) can have them.
     */
    fun nearest(points: List<LatLng>, current: LatLng): Projection? {
        if (points.size < 2) return null

        var travelled = 0.0
        var bestOffset = 0.0
        var bestDistance = Double.MAX_VALUE

        for (i in 0 until points.size - 1) {
            val from = points[i]
            val to = points[i + 1]
            val segmentKm = RouteProgress.distanceKm(from, to)
            if (segmentKm <= 0.0) continue

            // A local flat frame centred on the segment's start. Over a
            // segment of a simplified route — hundreds of metres, at most a
            // few kilometres — the curvature of the earth is far below the
            // precision anything here is quoted to, and the alternative is
            // spherical trigonometry for a value that only picks a segment.
            val scale = cos(Math.toRadians(from.lat))
            val ax = 0.0
            val ay = 0.0
            val bx = (to.lng - from.lng) * scale * KM_PER_DEGREE
            val by = (to.lat - from.lat) * KM_PER_DEGREE
            val px = (current.lng - from.lng) * scale * KM_PER_DEGREE
            val py = (current.lat - from.lat) * KM_PER_DEGREE

            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            // Clamped to the segment: without this the nearest point could
            // land on the segment's *extension*, miles past its end, and the
            // offset would run past the end of the road.
            val t = if (lengthSquared <= 0.0) 0.0 else {
                ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
            }

            val offX = px - t * dx
            val offY = py - t * dy
            val offRoute = sqrt(offX * offX + offY * offY)

            if (offRoute < bestDistance) {
                bestDistance = offRoute
                bestOffset = travelled + t * segmentKm
            }

            travelled += segmentKm
        }

        if (bestDistance == Double.MAX_VALUE) return null
        return Projection(offsetKm = bestOffset, offRouteKm = bestDistance, totalKm = travelled)
    }

    /**
     * How far along the road the rider is and how much of it is left, or null
     * when the road cannot answer for them.
     *
     * Both numbers from one pass, because the screen wants both and walking a
     * six-hundred-point line twice per recomposition to get them separately
     * would be work for nothing.
     */
    fun progress(points: List<LatLng>, current: LatLng): Progress? {
        val projection = onRoute(points, current) ?: return null
        if (projection.totalKm <= 0.0) return null
        return Progress(
            fraction = (projection.offsetKm / projection.totalKm).coerceIn(0.0, 1.0),
            remainingKm = (projection.totalKm - projection.offsetKm).coerceAtLeast(0.0),
        )
    }

    /** How much of the road is behind the rider, from 0 to 1, or null. */
    fun fraction(points: List<LatLng>, current: LatLng): Double? =
        progress(points, current)?.fraction

    /** Kilometres of road left ahead of the rider, or null. */
    fun remainingKm(points: List<LatLng>, current: LatLng): Double? =
        progress(points, current)?.remainingKm

    /** What the bar shows when it is measuring a road rather than a gap. */
    data class Progress(val fraction: Double, val remainingKm: Double)

    private fun onRoute(points: List<LatLng>, current: LatLng): Projection? =
        nearest(points, current)?.takeIf { it.offRouteKm <= MAX_OFF_ROUTE_KM }

    /**
     * A rider placed on a road: how far along it they are, how far off it they
     * are, and how long the whole thing is.
     */
    data class Projection(
        val offsetKm: Double,
        val offRouteKm: Double,
        val totalKm: Double,
    )
}
