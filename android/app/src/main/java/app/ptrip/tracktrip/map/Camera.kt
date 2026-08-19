package app.ptrip.tracktrip.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * A point on the map, free of any map library.
 *
 * osmdroid's GeoPoint is an Android class and drags a device into every test
 * that touches it, so the arithmetic on this screen — framing the camera,
 * sliding a marker, ordering riders along a road — is written against this
 * instead and unit-tested on a laptop.
 */
data class LatLng(val lat: Double, val lng: Double)

/**
 * Where the camera should start, and how close in.
 *
 * [zoom] is null when the camera should fit [bounds] instead — the map has to
 * measure itself before it can work out what zoom that takes, which is
 * something only the real view can do.
 */
data class CameraTarget(
    val centre: LatLng,
    val bounds: Bounds?,
    val zoom: Double?,
)

/** A north/south/east/west box, already padded. */
data class Bounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
)

/** Chiang Mai. The last resort, when nobody's position is known at all. */
val FALLBACK_CENTRE = LatLng(18.7883, 98.9853)

/**
 * Close enough to read street names, wide enough to see the next junction.
 * Used when there is one rider, or when the whole group is in one car park —
 * fitting a box a few metres across would put the camera inside a single
 * building.
 */
const val SOLO_ZOOM = 15.5

/**
 * Only ever used with [FALLBACK_CENTRE], where precision would be a lie.
 *
 * Not as close as [SOLO_ZOOM], because this is the camera for "nobody has
 * said where they are" and pretending to street-level knowledge of a city the
 * rider may not be in would be worse than useless. Not as far out as it was
 * either: at 12 a phone screen covers about forty kilometres, which is a
 * province, and a rider opening the map got a view with no roads they could
 * read and a pinch to do before the screen was any use. At 14 it is about ten
 * kilometres — main roads legible, and close enough that the jump to the real
 * camera is a step rather than a leap.
 */
const val FALLBACK_ZOOM = 14.0

/**
 * How much of the group's own span to leave as margin around it, so the
 * outermost pins are not drawn on the edge of the screen.
 *
 * This is the *only* margin. The screen used to add a second one in pixels on
 * the way into osmdroid's `zoomToBoundingBox`, and two margins compounded is
 * a large part of why the map opened wider than anyone wanted.
 */
private const val PADDING_FRACTION = 0.18

/** Web Mercator tile edge, in pixels. Every source this app uses is 256. */
private const val TILE_SIZE_PX = 256.0

/**
 * The closest [fitZoom] will go.
 *
 * Fitting is for showing a group; a box so small that fitting it would put
 * the camera among the roof tiles is [SOLO_ZOOM]'s job, and
 * [TIGHT_CLUSTER_DEGREES] normally catches that first. This is the backstop
 * for a box that is tight in one axis only — two riders on the same street,
 * a kilometre apart — where the latitude fit alone would zoom to a doorway.
 */
private const val MAX_FIT_ZOOM = SOLO_ZOOM

/**
 * The furthest out [fitZoom] will go: about a continent on a phone.
 *
 * A guard against one impossible coordinate, not a framing choice. A single
 * fix at (0, 0) — valid to the server, nonsense on a ride — would otherwise
 * pull the camera out to the whole globe and make the other riders invisible.
 */
private const val MIN_FIT_ZOOM = 4.0

/**
 * Below this, a "group" is really one place: a fuel stop, or one rider.
 *
 * 0.004 degrees of latitude is a little over 400 m. Anything tighter is
 * framed at [SOLO_ZOOM] rather than fitted, because fitting it would zoom to
 * the roof tiles.
 */
private const val TIGHT_CLUSTER_DEGREES = 0.004

/**
 * The camera the map should open on.
 *
 * In order of preference:
 *
 *  1. everyone who has reported, framed together with a margin;
 *  2. this phone's own position, when the trip has no fixes yet — a rider who
 *     just opened a new trip is looking at their own road, not at Chiang Mai;
 *  3. Chiang Mai, only when nothing at all is known.
 *
 * [myLocation] is deliberately not merged into [riders] when the group has
 * already reported: on a trip the rider is a member of, their own fix is
 * already in that list, and forcing it in again would stretch the box for a
 * spectator watching a trip from home.
 */
fun initialCamera(riders: List<LatLng>, myLocation: LatLng?): CameraTarget {
    if (riders.isEmpty()) {
        val centre = myLocation ?: FALLBACK_CENTRE
        val zoom = if (myLocation != null) SOLO_ZOOM else FALLBACK_ZOOM
        return CameraTarget(centre = centre, bounds = null, zoom = zoom)
    }

    val north = riders.maxOf { it.lat }
    val south = riders.minOf { it.lat }
    val east = riders.maxOf { it.lng }
    val west = riders.minOf { it.lng }
    val centre = LatLng((north + south) / 2, (east + west) / 2)

    // One rider, or a group standing together: there is no box worth fitting.
    if (abs(north - south) < TIGHT_CLUSTER_DEGREES && abs(east - west) < TIGHT_CLUSTER_DEGREES) {
        return CameraTarget(centre = centre, bounds = null, zoom = SOLO_ZOOM)
    }

    // The padding is a share of the group's own span rather than a fixed
    // number of degrees, so a convoy strung over 50 km and one spread across
    // a town both get a margin that looks the same on screen.
    val padLat = max(north - south, TIGHT_CLUSTER_DEGREES) * PADDING_FRACTION
    val padLng = max(east - west, TIGHT_CLUSTER_DEGREES) * PADDING_FRACTION

    return CameraTarget(
        centre = centre,
        bounds = Bounds(
            north = north + padLat,
            south = south - padLat,
            east = east + padLng,
            west = west - padLng,
        ),
        zoom = null,
    )
}

/**
 * The zoom at which [bounds] just fits a view of [widthPx] by [heightPx].
 *
 * Worked out here rather than handed to osmdroid's `zoomToBoundingBox` for
 * two reasons. The first is that this can be tested: the map view cannot be
 * built without a device, so anything left inside it is checked by opening
 * the app and squinting. The second is that `zoomToBoundingBox` applies its
 * own padding on top of whatever it is given and then rounds the result
 * down — with the fractional margin [initialCamera] already adds, the camera
 * ended up two steps wider than the group actually needed.
 *
 * Both axes are measured and the tighter one wins, so the box fits whichever
 * way round the group is strung out. Latitude is measured in Web Mercator's
 * own projected space, not in degrees: a degree of latitude occupies more
 * pixels near the equator than near the poles, and fitting on the raw degrees
 * would cut riders off the top of the screen the further north the ride goes.
 *
 * Returns [FALLBACK_ZOOM] for a view that has not been measured yet — a zoom
 * derived from a zero-pixel screen is an infinity, not a camera.
 */
fun fitZoom(bounds: Bounds, widthPx: Int, heightPx: Int): Double {
    if (widthPx <= 0 || heightPx <= 0) return FALLBACK_ZOOM

    val lngSpan = abs(bounds.east - bounds.west)
    val latSpan = abs(mercatorY(bounds.north) - mercatorY(bounds.south))

    val zoomForLng = if (lngSpan <= 0.0) {
        Double.MAX_VALUE
    } else {
        log2(widthPx * 360.0 / (TILE_SIZE_PX * lngSpan))
    }
    val zoomForLat = if (latSpan <= 0.0) {
        Double.MAX_VALUE
    } else {
        log2(heightPx / (TILE_SIZE_PX * latSpan))
    }

    val fitted = min(zoomForLng, zoomForLat)
    if (fitted == Double.MAX_VALUE) return SOLO_ZOOM
    return fitted.coerceIn(MIN_FIT_ZOOM, MAX_FIT_ZOOM)
}

/**
 * A latitude as a fraction of the whole Web Mercator map, 0 at the north edge
 * and 1 at the south.
 *
 * Clamped to the latitudes Mercator can express: the projection sends the
 * poles to infinity, and a fix at 90° would otherwise make every zoom
 * calculation that touched it a NaN.
 */
private fun mercatorY(latDegrees: Double): Double {
    val lat = latDegrees.coerceIn(-MERCATOR_LIMIT_DEGREES, MERCATOR_LIMIT_DEGREES)
    val radians = lat * PI / 180.0
    val projected = ln(tan(PI / 4.0 + radians / 2.0))
    return (1.0 - projected / PI) / 2.0
}

/** Where Web Mercator stops, and where every slippy map cuts its tiles. */
private const val MERCATOR_LIMIT_DEGREES = 85.05112878
