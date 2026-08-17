package app.ptrip.tracktrip.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Ordering riders by how far along the ride they are.
 *
 * ## This is an estimate, and deliberately so
 *
 * The app has no route, no road network and no map matching: it knows a set
 * of points and, from the previous poll, where those points were before. So
 * "who is in front" is answered geometrically rather than by distance along a
 * road:
 *
 *  1. work out which way the group as a whole is travelling — the mean of
 *     each rider's own movement since their last fix;
 *  2. project every rider onto that direction;
 *  3. the one furthest along it is called the leader.
 *
 * That is right for the case this exists for — a group strung out along one
 * road — and it is knowingly wrong for others. A hairpin puts a rider who has
 * already gone round it *behind* someone still approaching, because they are
 * physically further back along the heading even though they are ahead on the
 * road. Two riders who took different roads to the same town are compared as
 * if they were on one. Nothing here is a claim about road distance, and the
 * screen must not present it as one.
 *
 * It is also stable in the case that matters most: when nobody has moved
 * (which is every poll during a fuel stop), there is no heading to be found
 * and the caller is told so rather than being handed a made-up order that
 * reshuffles the list on every refresh.
 */
object RideOrder {

    /**
     * A rider as this file needs them: where they are, and where they were at
     * the previous *distinct* fix.
     *
     * [previous] is null for a rider who has only ever reported once — they
     * contribute nothing to the heading, but they are still placed in the
     * order once one is known.
     */
    data class Tracked(
        val userId: Long,
        val position: LatLng?,
        val previous: LatLng?,
    )

    /** Metres per degree of latitude. Good to a fraction of a percent anywhere. */
    private const val METRES_PER_DEGREE = 111_320.0

    /**
     * Movement shorter than this is GPS noise, not travel.
     *
     * A phone standing still wanders by a few metres between fixes, and a
     * heading averaged from that noise is a random direction — which would
     * reorder the list on every poll while the group sat at a café.
     */
    private const val MIN_MOVEMENT_METRES = 25.0

    /**
     * Which way the group is going, as a unit vector in local metres, or null
     * when it cannot be told.
     *
     * The mean of the riders' movement vectors, each normalised first so that
     * one fast rider does not outvote three slow ones — this is a question
     * about direction, not speed. Null when nobody has moved far enough to
     * count, and also when the movements cancel out (a group that met head-on
     * has no shared direction, and saying so beats inventing one).
     */
    fun groupHeading(riders: List<Tracked>): Vector? {
        var x = 0.0
        var y = 0.0
        var counted = 0

        riders.forEach { rider ->
            val to = rider.position ?: return@forEach
            val from = rider.previous ?: return@forEach
            val (dx, dy) = offsetMetres(from = from, to = to)
            val distance = hypot(dx, dy)
            if (distance < MIN_MOVEMENT_METRES) return@forEach

            x += dx / distance
            y += dy / distance
            counted += 1
        }

        if (counted == 0) return null

        val length = hypot(x, y)
        // Opposing movements that sum to nothing: no group direction exists.
        if (length < 1e-6) return null

        return Vector(x / length, y / length)
    }

    /**
     * The riders' ids, leader first, or null when there is no heading to
     * order them by.
     *
     * Riders with no position are appended in the order they came in: they are
     * not "last", they are simply unplaced, and the screen says so on the row.
     */
    fun leaderFirst(riders: List<Tracked>): List<Long>? {
        val heading = groupHeading(riders) ?: return null

        val placed = riders.filter { it.position != null }
        if (placed.isEmpty()) return null

        // Distances are measured from an arbitrary shared origin — the first
        // rider — because only the *differences* along the heading matter.
        val origin = placed.first().position!!

        val ordered = placed
            .map { rider ->
                val (dx, dy) = offsetMetres(from = origin, to = rider.position!!)
                // Projection onto the heading: how far along it this rider is.
                rider.userId to (dx * heading.x + dy * heading.y)
            }
            // Furthest along first, then by id so that two riders side by side
            // don't swap places between polls.
            .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
            .map { it.first }

        return ordered + riders.filter { it.position == null }.map { it.userId }
    }

    /** A direction in the local east/north plane. Always unit length. */
    data class Vector(val x: Double, val y: Double) {
        /** Compass bearing in degrees, clockwise from north. For display and tests. */
        val bearingDegrees: Double
            get() = (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }

    /**
     * [to] as metres east and north of [from].
     *
     * A flat-earth approximation, which over the few kilometres between two
     * riders is accurate to centimetres — and this file's whole output is an
     * estimate anyway.
     */
    private fun offsetMetres(from: LatLng, to: LatLng): Pair<Double, Double> {
        val north = (to.lat - from.lat) * METRES_PER_DEGREE
        // Degrees of longitude shrink towards the poles; at Chiang Mai's
        // latitude one is about 5% shorter than a degree of latitude.
        val east = (to.lng - from.lng) * METRES_PER_DEGREE * cos(Math.toRadians((from.lat + to.lat) / 2))
        return east to north
    }

    /** Straight-line distance in metres. Exposed for tests and for callers that need it. */
    fun distanceMetres(from: LatLng, to: LatLng): Double {
        val (east, north) = offsetMetres(from, to)
        return sqrt(east * east + north * north)
    }
}
