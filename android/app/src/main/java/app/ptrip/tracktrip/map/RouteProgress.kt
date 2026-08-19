package app.ptrip.tracktrip.map

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How far there is left to go, and how much of the way is done.
 *
 * ## What this is honest about
 *
 * Straight-line distance, not road distance. The app has no routing engine and
 * is not getting one to draw a progress bar — a bar that claimed to know the
 * road would be wrong by however much the road bends, and worse, it would be
 * wrong *confidently*. What it measures is the gap closing between the rider
 * and the place they said they were going, which is the question the bar is
 * actually asked: am I getting there, and roughly how much is left.
 *
 * On a mountain road the remaining kilometres will read low. That is a
 * property of the measure, and the screen says "straight line" rather than
 * pretending otherwise.
 */
object RouteProgress {

    private const val EARTH_RADIUS_KM = 6371.0

    /** Great-circle distance in kilometres. The same formula the server uses. */
    fun distanceKm(from: LatLng, to: LatLng): Double {
        val deltaLat = Math.toRadians(to.lat - from.lat)
        val deltaLng = Math.toRadians(to.lng - from.lng)
        val fromLat = Math.toRadians(from.lat)
        val toLat = Math.toRadians(to.lat)

        val a = sin(deltaLat / 2).let { it * it } +
            cos(fromLat) * cos(toLat) * sin(deltaLng / 2).let { it * it }

        return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(a)))
    }

    /**
     * How much of the journey is behind the rider, from 0 to 1.
     *
     * Measured as ground closed towards the destination rather than ground
     * covered, so a detour does not read as progress and doubling back reads
     * as losing it — which is what a rider looking at the bar wants to know.
     *
     * Null when there is nothing to measure against: no destination set, no
     * origin to measure from, or an origin and destination in the same place.
     * Null means "draw no bar" — a bar at zero would be a claim that the rider
     * has not started, which is a different and false statement.
     *
     * A rider further from the destination than the origin was — they set off
     * the wrong way, or the origin was set at a fuel stop along the route —
     * clamps to 0 rather than going negative. One who has passed it clamps
     * to 1.
     */
    fun fraction(origin: LatLng?, destination: LatLng?, current: LatLng?): Double? {
        if (origin == null || destination == null || current == null) return null

        val total = distanceKm(origin, destination)
        if (total <= 0.0) return null

        val remaining = distanceKm(current, destination)
        return ((total - remaining) / total).coerceIn(0.0, 1.0)
    }

    /** Kilometres left in a straight line, or null with no destination set. */
    fun remainingKm(destination: LatLng?, current: LatLng?): Double? {
        if (destination == null || current == null) return null
        return distanceKm(current, destination)
    }

    /**
     * A distance, in the unit a rider would say it in.
     *
     * Metres below one kilometre, rounded to fifty so the last hundred metres
     * do not flicker with GPS noise; one decimal place up to ten kilometres,
     * where the tenth still means something; whole kilometres above that,
     * where it does not.
     */
    fun format(km: Double?): String? {
        if (km == null || !km.isFinite() || km < 0) return null

        if (km < 1.0) {
            val metres = (km * 1000).roundToInt()
            val rounded = ((metres + 25) / 50) * 50
            // 1000 m is a kilometre, and reading "1000 m" after "950 m" is a
            // unit change the rounding caused rather than the rider moving.
            return if (rounded >= 1000) "1.0 km" else "$rounded m"
        }

        if (km < 10.0) {
            val tenths = (km * 10).roundToInt()
            return "${tenths / 10}.${tenths % 10} km"
        }

        return "${km.roundToInt()} km"
    }
}

/**
 * Which way the camera is being driven.
 *
 * A single value rather than two booleans, because the states are exclusive
 * and the bugs in this area are all "both at once": a camera that is following
 * a rider *and* holding an overview snaps between the two on every fix.
 */
enum class MapCamera {
    /**
     * Following this phone's own position, the way a navigation app does.
     *
     * Only ever entered while this phone is sharing on this trip: a rider
     * watching a ride they are not on has no position of their own worth
     * following, and dragging them back to it would be a fight.
     */
    FOLLOW,

    /**
     * The rider has dragged the map and it stays where they put it.
     *
     * The default for anyone not sharing, and where a single drag lands
     * anybody. Nothing moves the camera in this state except an explicit tap.
     */
    FREE,

    /** Zoomed out to take in the whole route. Entered only by tapping for it. */
    OVERVIEW,
}

/**
 * The rules the camera follows, kept apart from the map view so they can be
 * tested without a device.
 */
object CameraRules {

    /**
     * Where the camera starts on this screen.
     *
     * Following, but only for a rider who is actually sharing on this trip —
     * they are the one going somewhere. Everyone else opens on the framing the
     * map has always used, and stays there until they ask for something.
     */
    fun initial(isSharingThisTrip: Boolean): MapCamera =
        if (isSharingThisTrip) MapCamera.FOLLOW else MapCamera.FREE

    /**
     * A drag always wins.
     *
     * Whatever the camera was doing, a rider who moved the map by hand has
     * said where they want to look. Nothing takes it back on its own; the way
     * back is the button, which is why there is one.
     */
    fun afterPan(): MapCamera = MapCamera.FREE

    /**
     * Whether a new position should move the camera.
     *
     * Only in [MapCamera.FOLLOW]. In overview the whole point is that the
     * frame is bigger than the rider, and in free the rider is holding it.
     */
    fun followsPosition(camera: MapCamera): Boolean = camera == MapCamera.FOLLOW
}
