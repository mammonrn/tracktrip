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
    /** The rider's own handle, when they have set one. Preferred over [displayName]. */
    val username: String?,
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
    /**
     * Ground speed at the last fix, in **metres per second** — the unit
     * Android's Location reports and the one the server stores. Converted for
     * display by `map/Speed.kt`, and nowhere else.
     *
     * Null means no speed was ever sent for this fix, which is not the same
     * as a rider who is stopped: that one reports 0.
     */
    val speedMps: Double?,
    val batteryPct: Int?,
    val recordedAt: String?,
) {
    val hasPosition: Boolean get() = lat != null && lng != null
    val isOwner: Boolean get() = role == Trip.ROLE_OWNER
    val label: String get() = riderLabel(username, displayName, "Rider $userId")

    /** Sharing with no end time — the rider stops it by hand. */
    val isSharingIndefinitely: Boolean get() = isSharing && sharingUntil == null
}

/**
 * Someone the rider has been on a trip with before, offered on the invite
 * screen so an email address doesn't have to be typed from memory.
 */
data class SuggestedInvitee(
    val userId: Long,
    val email: String,
    val displayName: String?,
    val username: String?,
    val photoUrl: String?,
    /** How many trips this rider and the caller have shared. Orders the list. */
    val tripsTogether: Int,
) {
    val label: String get() = riderLabel(username, displayName, email)
}

/** A short-lived code that puts whoever redeems it on the trip. */
data class JoinCode(
    val tripId: Long,
    val code: String,
    /** ISO-8601, from the server. The QR screen counts down to it. */
    val expiresAt: String,
)

/** The outcome of redeeming a join code. */
data class JoinResult(
    val trip: Trip,
    /** True when the rider was already on this trip — a no-op, not a failure. */
    val alreadyMember: Boolean,
)

/**
 * A rider's sharing session on one trip.
 *
 * [expiresAt] null means two different things, which [sharing] tells apart:
 * running until the rider stops it, and no session at all.
 */
data class SharingSession(
    val tripId: Long,
    val sharing: Boolean,
    val expiresAt: String?,
)

/**
 * One rider's level, from GET /trips/:id/member-levels.
 *
 * Only the name and the lifetime total: the map lists a level beside a name,
 * and how far *someone else* is from their next promotion is their business,
 * not something to put on a stranger's screen.
 */
data class RiderLevel(
    val userId: Long,
    val levelName: String,
    val totalKm: Double,
)

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

    suspend fun suggestedInvitees(tripId: Long): List<SuggestedInvitee> =
        JSONArray(client.get("/trips/$tripId/suggested-invitees")).map { it.toSuggestedInvitee() }

    /** Issues a fresh join code, retiring any the trip already had. */
    suspend fun createJoinCode(tripId: Long): JoinCode =
        JSONObject(client.post("/trips/$tripId/join-code")).toJoinCode()

    suspend fun joinByCode(code: String): JoinResult {
        val json = JSONObject(client.post("/trips/join", JSONObject().put("code", code)))
        return JoinResult(
            trip = json.getJSONObject("trip").toTrip(),
            alreadyMember = json.optBoolean("already_member", false),
        )
    }

    /**
     * Starts (or restarts) sharing on a trip.
     *
     * [durationMinutes] must be one of the offered durations, or null for
     * "until I stop it" — the server rejects anything else, and refuses an
     * omitted field outright so a client that forgot to send one can't
     * silently get an unlimited session.
     */
    suspend fun startSharing(tripId: Long, durationMinutes: Int?): SharingSession {
        val body = JSONObject().put("duration_minutes", durationMinutes ?: JSONObject.NULL)
        return JSONObject(client.post("/trips/$tripId/share/start", body)).toSharingSession()
    }

    suspend fun stopSharing(tripId: Long): SharingSession =
        JSONObject(client.post("/trips/$tripId/share/stop")).toSharingSession()

    /**
     * Reports one fix. [speed] is metres per second, straight from the
     * Location that produced the fix — see `map/Speed.kt` for why no
     * conversion happens on this side of the wire.
     */
    suspend fun reportPosition(
        tripId: Long,
        lat: Double,
        lng: Double,
        timestamp: String,
        accuracy: Float?,
        speed: Float?,
        batteryPct: Int?,
    ) {
        val body = JSONObject()
            .put("lat", lat)
            .put("lng", lng)
            .put("timestamp", timestamp)
        accuracy?.let { body.put("accuracy", it.toDouble()) }
        speed?.let { body.put("speed", it.toDouble()) }
        batteryPct?.let { body.put("battery_pct", it) }
        client.post("/trips/$tripId/positions", body)
    }

    /**
     * Every member's level in one call.
     *
     * A batch rather than one request per rider: `/me/level` only ever answers
     * for the caller, and a trip of eight would otherwise be eight requests
     * from a phone that is already polling positions.
     */
    suspend fun memberLevels(tripId: Long): Map<Long, RiderLevel> =
        JSONArray(client.get("/trips/$tripId/member-levels"))
            .map { it.toRiderLevel() }
            .associateBy { it.userId }
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
    username = optStringOrNull("username"),
    photoUrl = optStringOrNull("photo_url"),
    role = optStringOrNull("role") ?: "member",
    // Defaults to false: a member row that arrives without the field is
    // safer read as "not sharing" than as a rider who might be live.
    isSharing = optBoolean("is_sharing", false),
    sharingUntil = optStringOrNull("sharing_until"),
    lat = optDoubleOrNull("lat"),
    lng = optDoubleOrNull("lng"),
    speedMps = optDoubleOrNull("speed"),
    batteryPct = optIntOrNull("battery_pct"),
    recordedAt = optStringOrNull("recorded_at"),
)

internal fun JSONObject.toSuggestedInvitee() = SuggestedInvitee(
    userId = optLong("user_id"),
    email = optStringOrNull("email").orEmpty(),
    displayName = optStringOrNull("display_name"),
    username = optStringOrNull("username"),
    photoUrl = optStringOrNull("photo_url"),
    // A build that predates the count still sorts sensibly: everyone ties at
    // one, and the server's own ordering is preserved.
    tripsTogether = optIntOrNull("trips_together") ?: 1,
)

internal fun JSONObject.toJoinCode() = JoinCode(
    tripId = optLong("trip_id"),
    code = optStringOrNull("code").orEmpty(),
    expiresAt = optStringOrNull("expires_at").orEmpty(),
)

internal fun JSONObject.toSharingSession() = SharingSession(
    tripId = optLong("trip_id"),
    sharing = optBoolean("sharing", false),
    expiresAt = optStringOrNull("expires_at"),
)

internal fun JSONObject.toRiderLevel() = RiderLevel(
    userId = optLong("user_id"),
    // The server always sends a level — the table starts at zero kilometres,
    // so there is no such thing as a rider without one. The fallback is for a
    // response mangled in transit, not for a real state.
    levelName = optJSONObject("level")?.optStringOrNull("name") ?: "Novice",
    totalKm = optDouble("total_km", 0.0),
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
