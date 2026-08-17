package app.ptrip.tracktrip.map

import kotlin.math.abs
import kotlin.math.max

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

/** Only ever used with [FALLBACK_CENTRE], where precision would be a lie. */
const val FALLBACK_ZOOM = 12.0

/**
 * How much of the group's own span to leave as margin around it, so the
 * outermost pins are not drawn on the edge of the screen.
 */
private const val PADDING_FRACTION = 0.18

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
