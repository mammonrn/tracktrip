package app.ptrip.tracktrip.data

import org.json.JSONObject
import app.ptrip.tracktrip.map.LatLng
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
 * Why a search did not come back with places.
 *
 * ## Why the reason is a value and not just a sentence
 *
 * Every one of these used to arrive as [ApiException] carrying whatever
 * wording suited the HTTP status in general, and on this screen the general
 * wording was wrong in the worst way: a 404 read as "That's no longer
 * there." — a sentence about a place that has closed down. What it actually
 * meant was that the server had no `/geocode/search` route at all, because
 * the backend running on it predated the feature. Three searches for three
 * real cities all said the place was gone.
 *
 * So the cause is kept as a value the screen can word for itself, in the
 * rider's own language, and the two "the search does not work here" cases
 * stay apart — they have different fixes, and the person reading the message
 * is very often the person who has to apply one.
 */
enum class PlaceSearchProblem {
    /**
     * The server answered 404, which for this route can only mean it is not
     * there: the search never answers 404 itself — no matches is a 200 with
     * an empty list. A backend older than the app, in other words.
     */
    NOT_DEPLOYED,

    /** The route is there; `LOCATIONIQ_API_KEY` is not. The server says 503. */
    NOT_CONFIGURED,

    /** This rider's own minute-long budget, or the server's daily quota. */
    TOO_MANY,

    /** The server was reached; it could not reach LocationIQ. */
    UPSTREAM,

    /** The server was never reached at all — no signal, no route to it. */
    OFFLINE,

    /** Anything else, in which case the server's own words are shown. */
    UNKNOWN,
}

/** A search failure, with the reason kept alongside the fallback wording. */
class PlaceSearchException(
    val problem: PlaceSearchProblem,
    message: String,
    status: Int?,
) : ApiException(message, status)

/**
 * What a failed search actually means, from the status it failed with.
 *
 * Pure, so the mapping is tested rather than trusted — this is the piece that
 * was silently wrong, and the only way it shows on screen is as a confusing
 * sentence that looks like ordinary app copy.
 */
fun placeSearchProblem(status: Int?): PlaceSearchProblem = when (status) {
    // Never produced by the search route itself. See NOT_DEPLOYED.
    404 -> PlaceSearchProblem.NOT_DEPLOYED
    503 -> PlaceSearchProblem.NOT_CONFIGURED
    429 -> PlaceSearchProblem.TOO_MANY
    // No response to take a status from: the request did not arrive.
    null -> PlaceSearchProblem.OFFLINE
    502, 504 -> PlaceSearchProblem.UPSTREAM
    in 500..599 -> PlaceSearchProblem.UPSTREAM
    else -> PlaceSearchProblem.UNKNOWN
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
    /**
     * Places matching [query], preferring ones near [near] when there is one.
     *
     * [near] is a bias and never a filter: somewhere on the other side of the
     * country is still found, just ranked below somewhere down the road. Null
     * — no fix yet, a preview, a test — searches the way this always did.
     */
    suspend fun search(
        query: String,
        limit: Int = PlaceSearchApi.DEFAULT_LIMIT,
        near: LatLng? = null,
    ): List<Place>
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
    override suspend fun search(query: String, limit: Int, near: LatLng?): List<Place> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val bias = near?.let {
            "&near=" + URLEncoder.encode(
                // Locale-fixed, for the same reason the directions call is: a
                // Thai phone writing 18,7883 would send a four-part coordinate
                // and the bias would be dropped, silently and only in Thai.
                String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lng),
                "UTF-8",
            )
        }.orEmpty()
        val body = try {
            client.get("/geocode/search?q=$encoded&limit=$limit$bias")
        } catch (e: SessionExpiredException) {
            // Signing out is about to happen and is not this screen's to
            // report. Passed through untouched.
            throw e
        } catch (e: ApiException) {
            throw PlaceSearchException(
                problem = placeSearchProblem(e.status),
                message = e.message ?: "",
                status = e.status,
            )
        }
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
