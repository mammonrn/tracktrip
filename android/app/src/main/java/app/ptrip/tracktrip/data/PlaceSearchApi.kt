package app.ptrip.tracktrip.data

import org.json.JSONObject
import java.net.URLEncoder

/**
 * A place somebody could be heading for, as the search returns it.
 *
 * [name] is the short form that goes in the label field once it is picked —
 * "Pai" — and [address] is the full line the rider reads to tell two of them
 * apart — "Pai, Mae Hong Son, Thailand". Both are always present; the server
 * drops any result that has neither, and any result it could not place.
 */
data class Place(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    /** What kind of thing it is — `town`, `fuel`, `viewpoint`. Null when unsaid. */
    val kind: String?,
    /**
     * OpenStreetMap's id for it, when there is one.
     *
     * Used as the list key so that two results with the same name — and there
     * are a great many `7-Eleven`s — do not collide in a `LazyColumn`.
     */
    val osmId: String?,
) {
    /** A stable key for a list row, falling back to the coordinate. */
    val key: String get() = osmId ?: "$lat,$lng"
}

/**
 * Somewhere to look up a place name.
 *
 * An interface rather than the class alone so the debounce that drives it can
 * be tested against a stub — the thing worth testing about a search box is
 * *how often it asks*, and a test that had to reach a real backend to find
 * out would answer a different question.
 */
interface PlaceLookup {
    suspend fun search(query: String, limit: Int = PlaceSearchApi.DEFAULT_LIMIT): List<Place>
}

/**
 * Place search, over this app's own backend.
 *
 * Through the backend rather than to LocationIQ directly, and that is not a
 * detour: the API key is metered — a free tier of 5,000 requests a day shared
 * by everyone on the server — and a key shipped inside an APK is one `unzip`
 * away from being read and spent by somebody else. The server holds it,
 * caches the answers, and rate-limits per rider.
 *
 * See `src/routes/geocode.js` and `src/geocode/locationiq.js` in this repo.
 */
class PlaceSearchApi(private val client: ApiClient) : PlaceLookup {

    /**
     * Places matching [query], best first.
     *
     * Throws [ApiException] like every other call here, with the server's own
     * message — including the one that matters most on this screen: a server
     * with no key configured answers 503 and says so, which is a different
     * thing from "nothing is called that" and reads differently to a rider.
     */
    override suspend fun search(query: String, limit: Int): List<Place> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = client.get("/geocode/search?q=$encoded&limit=$limit")
        return parsePlaces(body)
    }

    companion object {
        /**
         * Enough to choose from without becoming a scroll. The server clamps
         * this to its own maximum, so a larger number here is not a way round
         * anything.
         */
        const val DEFAULT_LIMIT = 8
    }
}

/**
 * The `{ "query": ..., "results": [...] }` envelope, as places.
 *
 * Separated from the call so the parsing is testable without a network — the
 * coordinates arrive as JSON numbers here because the *server* has already
 * turned LocationIQ's strings into numbers, and a change to that on either
 * side should fail here rather than on a motorcycle.
 */
internal fun parsePlaces(body: String): List<Place> {
    val json = JSONObject(body)
    val array = json.optJSONArray("results") ?: return emptyList()
    return array.map { it.toPlace() }.filterNotNull()
}

internal fun JSONObject.toPlace(): Place? {
    val lat = optDoubleOrNull("lat") ?: return null
    val lng = optDoubleOrNull("lng") ?: return null
    val address = optStringOrNull("address")
    val name = optStringOrNull("name") ?: address ?: return null

    return Place(
        name = name,
        address = address ?: name,
        lat = lat,
        lng = lng,
        kind = optStringOrNull("kind"),
        osmId = optStringOrNull("osm_id"),
    )
}
