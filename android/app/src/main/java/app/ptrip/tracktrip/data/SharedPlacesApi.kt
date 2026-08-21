package app.ptrip.tracktrip.data

import app.ptrip.tracktrip.map.LatLng
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject

/**
 * A place the riders on this server wrote down themselves.
 *
 * ## Why this exists next to [Place] rather than instead of it
 *
 * The place search is LocationIQ over OpenStreetMap, and OSM is thin in
 * exactly the way that matters to this app. "ปตท สวนดอก" — a petrol station
 * every rider in Chiang Mai can name — is not in it: the stations around Suan
 * Dok are tagged `PTT` and nothing else, and "สวนดอก" is not an administrative
 * area there. There is no query that finds it, because the row is not there.
 *
 * So the riders add it. One name and one point, typed by whoever knows the
 * place, found from then on by everybody on the server — see
 * `src/db/migrations/0011_shared_places.sql` for why the list is shared rather
 * than private.
 *
 * [createdBy] is null when the account that typed it has since been deleted.
 * The place stays: the group is still using it and did not ask for it to go.
 */
data class SharedPlace(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** Which rider typed it in, or null once that account is gone. */
    val createdBy: Long?,
    /** What to call that rider on screen. Null for the same reason. */
    val createdByName: String?,
    /**
     * When it was written down, ISO-8601 from the server, or null.
     *
     * `shared_places.created_at` is `NOT NULL`, so null here only means a
     * build talking to a backend that predates the field in the response. The
     * detail card leaves the line out rather than showing a blank date.
     */
    val createdAt: String? = null,
) {
    /** The same shape a search result has, so one list can hold both. */
    fun asPlace(): Place = Place(
        name = name,
        // A rider's own place has no address to show, so the line under the
        // name says who wrote it down instead — which is the one thing worth
        // knowing about a row that did not come from a map company.
        address = createdByName.orEmpty(),
        lat = lat,
        lng = lng,
        kind = null,
        osmId = null,
        source = PlaceSource.SHARED,
        sharedId = id,
        createdBy = createdBy,
    )
}

/**
 * Somewhere to read and write the riders' own places.
 *
 * An interface for the same reason [PlaceLookup] is one: the thing worth
 * testing about the search box is how it behaves when one of its two sources
 * fails, and a test that had to reach a backend to find out would be answering
 * a different question.
 */
interface SharedPlaceStore {
    /** Places matching [query], preferring ones near [near]. Empty query lists all. */
    suspend fun search(
        query: String,
        limit: Int = SharedPlacesApi.DEFAULT_LIMIT,
        near: LatLng? = null,
    ): List<SharedPlace>

    /** Writes a new place for everybody on the server to find. */
    suspend fun add(name: String, point: LatLng): SharedPlace

    /** Removes one. The server allows whoever added it, or a super user. */
    suspend fun remove(id: Long)
}

/**
 * The riders' own places, over this app's own backend.
 *
 * Unlike [PlaceSearchApi] there is no metered key behind this and no upstream
 * to be unreachable — it is a table on the same server that answered the
 * request. That is the reason it is a separate call rather than something
 * folded into `/geocode/search`: this list is at its most useful on precisely
 * the day the geocoder is not working, and putting it behind the geocoder's
 * failures would be backwards.
 *
 * See `src/routes/places.js`.
 */
class SharedPlacesApi(private val client: ApiClient) : SharedPlaceStore {

    override suspend fun search(query: String, limit: Int, near: LatLng?): List<SharedPlace> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val bias = near?.let {
            "&near=" + URLEncoder.encode(
                // Locale-fixed, like every other coordinate this app sends: a
                // Thai phone writing 18,7883 would send a four-part coordinate
                // and the bias would be dropped, silently and only in Thai.
                String.format(Locale.US, "%.5f,%.5f", it.lat, it.lng),
                "UTF-8",
            )
        }.orEmpty()
        return parseSharedPlaces(client.get("/places?q=$encoded&limit=$limit$bias"))
    }

    override suspend fun add(name: String, point: LatLng): SharedPlace {
        val body = JSONObject()
            .put("name", name)
            .put("lat", point.lat)
            .put("lng", point.lng)
        return JSONObject(client.post("/places", body)).toSharedPlace()
            ?: throw ApiException("Couldn't save that place.", null)
    }

    override suspend fun remove(id: Long) {
        client.delete("/places/$id")
    }

    companion object {
        /**
         * How many of the riders' own places to put above the map company's.
         *
         * Smaller than the geocoder's eight on purpose. These are ranked ahead
         * of everything, so a loose match filling the screen would bury the
         * town somebody actually meant — and a group that has written down six
         * matching places for one query has a naming problem, not a search one.
         */
        const val DEFAULT_LIMIT = 5
    }
}

/** The `{ "query": ..., "results": [...] }` envelope, as places. */
internal fun parseSharedPlaces(body: String): List<SharedPlace> {
    val array = JSONObject(body).optJSONArray("results") ?: return emptyList()
    return array.map { it.toSharedPlace() }.filterNotNull()
}

internal fun JSONObject.toSharedPlace(): SharedPlace? {
    val id = if (isNull("id")) return null else optLong("id")
    val name = optStringOrNull("name") ?: return null
    // A row that cannot be placed on a map is dropped rather than shown: the
    // list is a list of things to tap, and a row that goes nowhere is a bug
    // report waiting to be filed.
    val lat = optDoubleOrNull("lat") ?: return null
    val lng = optDoubleOrNull("lng") ?: return null

    return SharedPlace(
        id = id,
        name = name,
        lat = lat,
        lng = lng,
        createdBy = if (isNull("created_by")) null else optLong("created_by"),
        createdByName = optStringOrNull("created_by_name"),
        createdAt = optStringOrNull("created_at"),
    )
}
