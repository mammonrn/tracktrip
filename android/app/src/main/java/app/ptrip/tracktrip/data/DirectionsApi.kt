package app.ptrip.tracktrip.data

import app.ptrip.tracktrip.map.LatLng
import org.json.JSONObject

/**
 * The road between two points: the line to draw, and how long it is.
 *
 * [distanceKm] and [durationMinutes] are the routing service's own figures and
 * are null when it did not give them. Deliberately not re-measured from
 * [points]: the drawn line is thinned on the server (see
 * `src/geocode/directions.js`), so adding its segments up would give a number
 * a little short of the real road — and quietly wrong is worse than absent.
 */
data class RouteLine(
    val points: List<LatLng>,
    val distanceKm: Double?,
    val durationMinutes: Int?,
)

/**
 * Somewhere to ask for the road between two points.
 *
 * An interface rather than the class alone so the map's view model can be
 * tested against a stub — and the thing worth testing here is *how often it
 * asks*, since every ask is a request out of a shared daily quota.
 */
interface RouteLookup {
    suspend fun route(from: LatLng, to: LatLng): RouteLine?
}

/**
 * Road routing, over this app's own backend.
 *
 * Through the backend rather than to LocationIQ directly, for exactly the
 * reasons place search is (see [PlaceSearchApi]): the key is metered, a key
 * inside an APK is a key anybody can read, and the server's cache means eight
 * riders on one trip cost one upstream request rather than eight.
 *
 * Returns null when there is no road between the two points — an island, a
 * coordinate in the sea — which the server answers as a 200 with a null route.
 * Everything that is a genuine failure throws [ApiException], and the caller
 * falls back to the straight line rather than showing anything: a rider on a
 * motorcycle has no use for "routing is rate limited", and the map still works
 * exactly as it did before road routing existed.
 *
 * See `src/routes/directions.js` in this repo.
 */
class DirectionsApi(private val client: ApiClient) : RouteLookup {

    override suspend fun route(from: LatLng, to: LatLng): RouteLine? {
        val path = "/directions?from=${coordinate(from)}&to=${coordinate(to)}"
        return parseRouteLine(client.get(path))
    }

    /**
     * A coordinate as the query string carries it.
     *
     * Five decimal places — about a metre — and locale-fixed, because a Thai
     * phone with a comma decimal separator would otherwise send
     * `18,7883,98,9853` and the server would refuse it as a four-part
     * coordinate. This is the same trap `formatCoordinate` on the map screen
     * exists to avoid, on a path where the failure is silent.
     */
    private fun coordinate(point: LatLng): String =
        String.format(java.util.Locale.US, "%.5f,%.5f", point.lat, point.lng)
}

/**
 * The `{ "route": { ... } }` envelope, as a line.
 *
 * Separated from the call so the parsing is testable without a network. Null
 * for a missing or unusable route, which is the same thing to a map: draw the
 * straight line.
 */
internal fun parseRouteLine(body: String): RouteLine? {
    val json = JSONObject(body)
    val route = json.optJSONObject("route") ?: return null

    val points = route.optJSONArray("points")?.map { it.toLatLng() }?.filterNotNull().orEmpty()
    // One point is not a line, and drawing a line through it would put a stray
    // dot on the map with no road under it.
    if (points.size < 2) return null

    return RouteLine(
        points = points,
        distanceKm = route.optDoubleOrNull("distance_km"),
        durationMinutes = route.optIntOrNull("duration_min"),
    )
}

internal fun JSONObject.toLatLng(): LatLng? {
    val lat = optDoubleOrNull("lat") ?: return null
    val lng = optDoubleOrNull("lng") ?: return null
    return LatLng(lat, lng)
}
