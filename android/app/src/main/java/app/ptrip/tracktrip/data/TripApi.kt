package app.ptrip.tracktrip.data

import org.json.JSONArray
import org.json.JSONObject

/** A trip, as GET /trips returns it — `role` is the caller's own role. */
data class Trip(
    val id: Long,
    val name: String,
    val ownerId: Long,
    val status: String,
    val role: String,
) {
    val isOwner: Boolean get() = role == ROLE_OWNER
    val isActive: Boolean get() = status == STATUS_ACTIVE
    val hasEnded: Boolean get() = !isActive

    companion object {
        const val ROLE_OWNER = "owner"
        const val STATUS_ACTIVE = "active"
    }
}

/** A pending invitation addressed to the signed-in rider's email. */
data class Invite(
    val id: Long,
    val tripId: Long,
    val email: String,
    val tripName: String?,
)

/**
 * A trip member and their last known position.
 *
 * GET /trips/:id/positions is also the only endpoint that lists a trip's
 * members, so this doubles as the member roster: every member comes back,
 * and the position fields are null for anyone who hasn't reported yet.
 */
data class MemberPosition(
    val userId: Long,
    val displayName: String?,
    val photoUrl: String?,
    val role: String,
    /**
     * Whether this rider may be reporting right now — the same question the
     * backend's write guard answers. On a running trip that is everyone;
     * once the trip has ended it is only those who chose to carry on.
     */
    val isSharing: Boolean,
    /**
     * When this rider's own sharing session lapses, or null.
     *
     * Null covers two different things, which [isSharing] tells apart: no
     * session at all, and a session set to run until the rider stops it. So
     * "sharing, no end time" is `isSharing = true` with this null — not a
     * missing value to fall back on.
     */
    val sharingUntil: String?,
    val lat: Double?,
    val lng: Double?,
    val batteryPct: Int?,
    val recordedAt: String?,
) {
    val hasPosition: Boolean get() = lat != null && lng != null
    val isOwner: Boolean get() = role == Trip.ROLE_OWNER
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: "Rider $userId"

    /** Sharing with no end time — the rider stops it by hand. */
    val isSharingIndefinitely: Boolean get() = isSharing && sharingUntil == null
}

/** The profile screen's challenge widget, from GET /me/level. */
data class LevelProgress(
    val totalKm: Double,
    val levelName: String,
    val nextLevelName: String?,
    val kmToNext: Double?,
    val levelMinKm: Double,
    val nextLevelMinKm: Double?,
)

/**
 * Every trip-related call the app makes. Thin on purpose: it turns JSON into
 * the types above and nothing more, leaving retries and token refresh to
 * [ApiClient].
 */
class TripApi(private val client: ApiClient) {

    suspend fun listTrips(): List<Trip> =
        JSONArray(client.get("/trips")).map { it.toTrip() }

    suspend fun createTrip(name: String): Trip =
        JSONObject(client.post("/trips", JSONObject().put("name", name))).toTrip()

    suspend fun endTrip(tripId: Long): Trip =
        JSONObject(client.post("/trips/$tripId/end")).toTrip()

    suspend fun listInvites(): List<Invite> =
        JSONArray(client.get("/invites")).map { it.toInvite() }

    /** Returns the trip just joined, so the caller can go straight to it. */
    suspend fun acceptInvite(inviteId: Long): Trip =
        JSONObject(client.post("/invites/$inviteId/accept"))
            .getJSONObject("trip")
            .toTrip()

    suspend fun invite(tripId: Long, email: String): Invite =
        JSONObject(client.post("/trips/$tripId/invites", JSONObject().put("email", email)))
            .toInvite()

    suspend fun members(tripId: Long): List<MemberPosition> =
        JSONArray(client.get("/trips/$tripId/positions")).map { it.toMemberPosition() }

    suspend fun reportPosition(
        tripId: Long,
        lat: Double,
        lng: Double,
        timestamp: String,
        accuracy: Float?,
        batteryPct: Int?,
    ) {
        val body = JSONObject()
            .put("lat", lat)
            .put("lng", lng)
            .put("timestamp", timestamp)
        accuracy?.let { body.put("accuracy", it.toDouble()) }
        batteryPct?.let { body.put("battery_pct", it) }
        client.post("/trips/$tripId/positions", body)
    }

    suspend fun levelProgress(): LevelProgress =
        JSONObject(client.get("/me/level")).toLevelProgress()
}

internal inline fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

/** JSON null arrives as JSONObject.NULL, which is not Kotlin's null. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

internal fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

internal fun JSONObject.toTrip() = Trip(
    id = getLong("id"),
    name = optStringOrNull("name") ?: "Untitled trip",
    ownerId = optLong("owner_id"),
    status = optStringOrNull("status") ?: Trip.STATUS_ACTIVE,
    role = optStringOrNull("role") ?: "member",
)

internal fun JSONObject.toInvite() = Invite(
    id = getLong("id"),
    tripId = optLong("trip_id"),
    email = optStringOrNull("email").orEmpty(),
    tripName = optStringOrNull("trip_name"),
)

internal fun JSONObject.toMemberPosition() = MemberPosition(
    userId = getLong("user_id"),
    displayName = optStringOrNull("display_name"),
    photoUrl = optStringOrNull("photo_url"),
    role = optStringOrNull("role") ?: "member",
    // Defaults to false: a member row that arrives without the field is
    // safer read as "not sharing" than as a rider who might be live.
    isSharing = optBoolean("is_sharing", false),
    sharingUntil = optStringOrNull("sharing_until"),
    lat = optDoubleOrNull("lat"),
    lng = optDoubleOrNull("lng"),
    batteryPct = optIntOrNull("battery_pct"),
    recordedAt = optStringOrNull("recorded_at"),
)

internal fun JSONObject.toLevelProgress(): LevelProgress {
    val level = optJSONObject("level")
    val next = if (isNull("next_level")) null else optJSONObject("next_level")
    return LevelProgress(
        totalKm = optDouble("total_km", 0.0),
        levelName = level?.optStringOrNull("name") ?: "Novice",
        nextLevelName = next?.optStringOrNull("name"),
        kmToNext = optDoubleOrNull("km_to_next"),
        levelMinKm = level?.optDouble("min_km", 0.0) ?: 0.0,
        nextLevelMinKm = next?.optDouble("min_km"),
    )
}
