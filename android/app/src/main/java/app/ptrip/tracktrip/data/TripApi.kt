package app.ptrip.tracktrip.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One end of a trip: where it starts, or where it is going.
 *
 * [label] is optional — a destination dropped on the map may have no name —
 * but the coordinate never is. The server refuses half a position, so there
 * is no such thing here as a place that cannot be drawn.
 */
data class TripEndpoint(
    val lat: Double,
    val lng: Double,
    val label: String?,
)

/** A trip, as GET /trips returns it — `role` is the caller's own role. */
data class Trip(
    val id: Long,
    val name: String,
    val ownerId: Long,
    val status: String,
    val role: String,
    /** Where the trip starts, or null when nobody has said. */
    val origin: TripEndpoint? = null,
    /** Where the trip is going, or null. Independent of [origin]. */
    val destination: TripEndpoint? = null,
) {
    val isOwner: Boolean get() = role == ROLE_OWNER
    val isActive: Boolean get() = status == STATUS_ACTIVE
    val hasEnded: Boolean get() = !isActive

    /**
     * Whether this trip is only visible because of who the caller is, rather
     * than because they are on it. The server says so with a role of its own
     * — see `GET /trips?all=true`.
     */
    val isSuperuserView: Boolean get() = role == ROLE_SUPERUSER

    companion object {
        const val ROLE_OWNER = "owner"
        const val ROLE_SUPERUSER = "superuser"
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
 * A point on the trip that is not a rider: a planned stop, or one dropped
 * while riding.
 *
 * [orderIndex] is the position along the planned route, and is null for a
 * live drop — ordering a point that was added because somebody stopped at it
 * would be inventing a route they never planned.
 */
data class Waypoint(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val type: String,
    val orderIndex: Int?,
    /**
     * The rider who dropped it.
     *
     * Kept because it decides who may take it away again: the server allows
     * the trip's owner or the member who added it, and a screen that offered
     * "remove" to everyone would be handing out 403s.
     */
    val addedBy: Long? = null,
) {
    val isPlanned: Boolean get() = type == TYPE_PLANNED

    companion object {
        const val TYPE_PLANNED = "planned"
        const val TYPE_LIVE = "live"
    }
}

/**
 * A trip's waypoints, as GET /trips/:id/waypoints returns them: two lists,
 * already sorted by the server — planned in route order, live in the order
 * they were dropped.
 */
data class TripWaypoints(
    val planned: List<Waypoint> = emptyList(),
    val live: List<Waypoint> = emptyList(),
) {
    val all: List<Waypoint> get() = planned + live
    val isEmpty: Boolean get() = planned.isEmpty() && live.isEmpty()
}


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
) {
    /**
     * How far through the current level the rider is, from 0 to 1 — what the
     * progress bar fills to.
     *
     * Measured between the two levels' thresholds rather than from zero: a
     * rider on 1,600 km is a fifth of the way from Wanderer to Voyager, not
     * 1,600/3,500 of the way, and a bar that reset to nearly-full on every
     * promotion would tell them the opposite of what happened.
     *
     * A rider at the top of the table has nothing left to fill towards, so
     * the bar is full — that is a finished ladder, not a stalled one. A
     * malformed table (thresholds equal or inverted) also returns full rather
     * than dividing by zero.
     */
    val fractionThroughLevel: Float
        get() {
            val next = nextLevelMinKm ?: return 1f
            val span = next - levelMinKm
            if (span <= 0) return 1f
            return ((totalKm - levelMinKm) / span).coerceIn(0.0, 1.0).toFloat()
        }
}

/**
 * Every trip-related call the app makes. Thin on purpose: it turns JSON into
 * the types above and nothing more, leaving retries and token refresh to
 * [ApiClient].
 */
/**
 * Open, and four of its reads with it, for one reason: `TripMapViewModel` has
 * timing worth asserting on — what is on screen *while* a fetch is in flight,
 * not only after it lands — and that cannot be tested against a call that
 * always answers instantly. A test subclass overrides the handful of reads a
 * view model makes on the way in and controls when they return; nothing here
 * changes for the app, which uses this class exactly as it did.
 */
open class TripApi(private val client: ApiClient) {

    /**
     * The rider's own trips, or — for a super user asking — every trip on the
     * server.
     *
     * [all] is opt-in on purpose, and the server ignores it for anyone else: a
     * super user is still a rider, and a list that silently grew to hold
     * everybody else's rides would make their own app worse the day they were
     * promoted.
     */
    open suspend fun listTrips(all: Boolean = false): List<Trip> =
        JSONArray(client.get(if (all) "/trips?all=true" else "/trips")).map { it.toTrip() }

    suspend fun createTrip(name: String): Trip =
        JSONObject(client.post("/trips", JSONObject().put("name", name))).toTrip()

    suspend fun endTrip(tripId: Long): Trip =
        JSONObject(client.post("/trips/$tripId/end")).toTrip()

    /**
     * Renames a trip.
     *
     * The same `PATCH /trips/:id` the two ends go through, and for the same
     * reason it is a PATCH: a field left out is left alone, so a rename cannot
     * wipe a destination somebody set from the map five seconds earlier.
     *
     * Owner-only on the server, and allowed on a finished trip — naming a ride
     * afterwards is when people do it. The name itself cannot be cleared: an
     * empty one comes back 400 rather than being stored, so the screen refuses
     * it first.
     */
    suspend fun renameTrip(tripId: Long, name: String): Trip =
        JSONObject(client.patch("/trips/$tripId", JSONObject().put("name", name))).toTrip()

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

    open suspend fun members(tripId: Long): List<MemberPosition> =
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
    open suspend fun memberLevels(tripId: Long): Map<Long, RiderLevel> =
        JSONArray(client.get("/trips/$tripId/member-levels"))
            .map { it.toRiderLevel() }
            .associateBy { it.userId }

    /** The trip's planned stops and live drops. */
    open suspend fun waypoints(tripId: Long): TripWaypoints =
        JSONObject(client.get("/trips/$tripId/waypoints")).toTripWaypoints()

    /**
     * Sets where the trip starts, or clears it when [endpoint] is null.
     *
     * One end per call, deliberately. `PATCH /trips/:id` treats an absent
     * field as "leave it alone" and an explicit null as "clear it", and that
     * distinction is the reason it is a PATCH at all — sending both ends every
     * time would make "name the destination" indistinguishable from "wipe the
     * start". Owner-only on the server; the screen offers it to nobody else.
     */
    suspend fun setOrigin(tripId: Long, endpoint: TripEndpoint?): Trip =
        patchEndpoint(tripId, "origin", endpoint)

    /** Sets where the trip is going, or clears it. See [setOrigin]. */
    suspend fun setDestination(tripId: Long, endpoint: TripEndpoint?): Trip =
        patchEndpoint(tripId, "destination", endpoint)

    private suspend fun patchEndpoint(tripId: Long, field: String, endpoint: TripEndpoint?): Trip {
        val value = endpoint?.let {
            JSONObject()
                .put("lat", it.lat)
                .put("lng", it.lng)
                .put("label", it.label ?: JSONObject.NULL)
        } ?: JSONObject.NULL
        return JSONObject(client.patch("/trips/$tripId", JSONObject().put(field, value))).toTrip()
    }

    /**
     * Drops a point on the trip.
     *
     * [orderIndex] belongs to a planned stop and only to one: the server
     * refuses it on a live drop rather than ignoring it, because a live point
     * has no place in a route nobody planned.
     */
    suspend fun addWaypoint(
        tripId: Long,
        name: String,
        lat: Double,
        lng: Double,
        type: String,
        orderIndex: Int? = null,
    ): Waypoint {
        val body = JSONObject()
            .put("name", name)
            .put("lat", lat)
            .put("lng", lng)
            .put("type", type)
        orderIndex?.let { body.put("order_index", it) }
        return JSONObject(client.post("/trips/$tripId/waypoints", body)).toWaypoint()
    }


    /** Removes a point. The server allows the trip owner or whoever added it. */
    suspend fun deleteWaypoint(tripId: Long, waypointId: Long) {
        client.delete("/trips/$tripId/waypoints/$waypointId")
    }

    /**
     * Changes a stop that is already on the trip — in practice, where it sits
     * in the route.
     *
     * Owner-only on the server, and stricter than delete on purpose: moving
     * one stop renumbers the ones around it, so a rule that let a member move
     * their own would have them rewriting stops that are not theirs. See
     * `src/routes/waypoints.js`.
     *
     * Only the two fields that can change after a stop exists. A stop that
     * moved somewhere else on the map is a different stop — that is a delete
     * and a create, which this class already does.
     */
    suspend fun updateWaypoint(
        tripId: Long,
        waypointId: Long,
        name: String? = null,
        orderIndex: Int? = null,
    ): Waypoint {
        val body = JSONObject()
        name?.let { body.put("name", it) }
        orderIndex?.let { body.put("order_index", it) }
        return JSONObject(client.patch("/trips/$tripId/waypoints/$waypointId", body)).toWaypoint()
    }
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
    origin = optJSONObject("origin")?.toTripEndpoint(),
    destination = optJSONObject("destination")?.toTripEndpoint(),
)

/**
 * One end of a trip, or null if the object is missing its coordinate.
 *
 * The server never sends half a position, so the null here is for a build
 * talking to an older backend that has no such field at all.
 */
internal fun JSONObject.toTripEndpoint(): TripEndpoint? {
    val lat = optDoubleOrNull("lat") ?: return null
    val lng = optDoubleOrNull("lng") ?: return null
    return TripEndpoint(lat = lat, lng = lng, label = optStringOrNull("label"))
}

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

internal fun JSONObject.toWaypoint() = Waypoint(
    id = optLong("id"),
    name = optStringOrNull("name").orEmpty(),
    lat = optDouble("lat"),
    lng = optDouble("lng"),
    type = optStringOrNull("type") ?: Waypoint.TYPE_LIVE,
    orderIndex = optIntOrNull("order_index"),
    addedBy = if (isNull("added_by")) null else optLong("added_by"),
)



internal fun JSONObject.toTripWaypoints() = TripWaypoints(
    planned = optJSONArray("planned")?.map { it.toWaypoint() } ?: emptyList(),
    live = optJSONArray("live")?.map { it.toWaypoint() } ?: emptyList(),
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
