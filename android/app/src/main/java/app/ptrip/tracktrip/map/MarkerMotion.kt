package app.ptrip.tracktrip.map

/**
 * Sliding a pin from where a rider was to where they now are.
 *
 * Positions arrive on a cadence rather than continuously, so a pin that simply
 * repainted at the new coordinates would jump the whole distance covered since
 * the last report with nothing to say which pin went where. The slide is not
 * decoration: it is the only thing tying the old position to the new one.
 *
 * The interpolation is kept here, away from the map view, so it can be tested
 * without a device.
 */
object MarkerMotion {

    /**
     * How long a pin takes to travel to its new position.
     *
     * Long enough to follow with your eye, short enough that the map is
     * settled well before anyone would think to interact with it.
     */
    const val DURATION_MS = 1_500L

    /**
     * Ease-in-out: still at both ends, quickest in the middle.
     *
     * The cubic form of it (`3t² - 2t³`, the smoothstep curve) rather than a
     * cosine, because it is the same shape and costs two multiplications.
     */
    fun ease(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * The point [fraction] of the way from [from] to [to], eased.
     *
     * Straight linear interpolation of latitude and longitude. Over the
     * distance a rider covers between two reports this is indistinguishable
     * from a great-circle path, and it cannot be fooled by the antimeridian
     * anywhere this app is ridden.
     */
    fun at(from: LatLng, to: LatLng, fraction: Float): LatLng {
        val t = ease(fraction).toDouble()
        return LatLng(
            lat = from.lat + (to.lat - from.lat) * t,
            lng = from.lng + (to.lng - from.lng) * t,
        )
    }
}
