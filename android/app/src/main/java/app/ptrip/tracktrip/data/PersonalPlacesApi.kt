package app.ptrip.tracktrip.data

import app.ptrip.tracktrip.map.LatLng
import org.json.JSONObject

/**
 * A place one rider saved for themselves. Nobody else on the server ever sees
 * one of these.
 *
 * ## Why this is a separate type from [SharedPlace]
 *
 * Because they are separate everywhere else, and the one thing that must never
 * happen is a private row being handled by code that thinks it is a shared
 * one. Two types cannot be confused; a boolean on one type can be read wrong
 * once, in one branch, and the symptom is somebody's home address turning up
 * in another rider's search.
 *
 * They are two tables on the server for the same reason — see
 * `src/db/migrations/0012_personal_places.sql`.
 *
 * [label] is what the shortcut chip says — "บ้าน", "ที่ทำงาน" — and [name] is
 * what the place is called on the route once it is picked. A rider whose home
 * is a condo called "The Astra" wants the chip to say "บ้าน" and the route to
 * say something they will recognise a month later.
 */
data class PersonalPlace(
    val id: Long,
    val label: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    /**
     * When this rider saved it, ISO-8601 from the server, or null.
     *
     * Null only against a backend older than the field —
     * `personal_places.created_at` is `NOT NULL`. There is no "added by" to
     * go with it and there never will be: the owner is the caller, which is
     * the one thing this table's whole safety property rests on.
     */
    val createdAt: String? = null,
) {
    /** Where it is, for dropping straight into the row a rider is filling. */
    val point: LatLng get() = LatLng(lat, lng)
}

/**
 * Somewhere to keep a rider's own places.
 *
 * There is no search here, deliberately. A rider has a handful of these, they
 * all arrive at once, and matching them against a typed query happens on the
 * phone — so the query never travels and there is no server-side filter to get
 * wrong. See `src/places/personal.js`.
 */
interface PersonalPlaceStore {
    /** Every place this rider saved. Theirs and only theirs. */
    suspend fun list(): List<PersonalPlace>

    /** Saves one. The owner is the signed-in rider and cannot be asked for. */
    suspend fun add(label: String, name: String, point: LatLng): PersonalPlace

    /** Removes one of the rider's own. */
    suspend fun remove(id: Long)
}

/**
 * A rider's own places, over this app's own backend.
 *
 * Every path here is under `/me`, which is not decoration: there is no id in
 * it naming whose places these are, so there is no parameter for the client to
 * get wrong and none for the server to forget to check. The owner is the
 * session.
 *
 * See `src/routes/places.js`.
 */
class PersonalPlacesApi(private val client: ApiClient) : PersonalPlaceStore {

    override suspend fun list(): List<PersonalPlace> =
        parsePersonalPlaces(client.get("/me/places"))

    override suspend fun add(label: String, name: String, point: LatLng): PersonalPlace {
        val body = JSONObject()
            .put("label", label)
            .put("name", name)
            .put("lat", point.lat)
            .put("lng", point.lng)
        return JSONObject(client.post("/me/places", body)).toPersonalPlace()
            ?: throw ApiException("Couldn't save that place.", null)
    }

    override suspend fun remove(id: Long) {
        client.delete("/me/places/$id")
    }
}

/** The `{ "results": [...] }` envelope, as places. */
internal fun parsePersonalPlaces(body: String): List<PersonalPlace> {
    val array = JSONObject(body).optJSONArray("results") ?: return emptyList()
    return array.map { it.toPersonalPlace() }.filterNotNull()
}

internal fun JSONObject.toPersonalPlace(): PersonalPlace? {
    val id = if (isNull("id")) return null else optLong("id")
    val label = optStringOrNull("label") ?: return null
    val name = optStringOrNull("name") ?: return null
    // A row that cannot be placed on a map is dropped: the chips are things to
    // tap, and one that goes nowhere is a bug report waiting to be filed.
    val lat = optDoubleOrNull("lat") ?: return null
    val lng = optDoubleOrNull("lng") ?: return null

    return PersonalPlace(
        id = id,
        label = label,
        name = name,
        lat = lat,
        lng = lng,
        createdAt = optStringOrNull("created_at"),
    )
}
