package app.ptrip.tracktrip.map

import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.TripEndpoint
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * One rider on a trip's podium: who they are, and which place they took.
 *
 * Carries the name and photo rather than a user id so the trip list can draw
 * it without a second read — the same call that answers "who has arrived"
 * already carries both.
 */
data class Arrival(
    val userId: Long,
    val place: Int,
    val label: String,
    val photoUrl: String?,
)

/**
 * Who reached the finish, and in what order.
 *
 * ## This is an estimate, and deliberately so
 *
 * The app is not told when anybody arrived anywhere. What it can read is
 * `GET /trips/:id/positions`, which answers *where is everyone now* — one
 * last-known fix per rider, with the time that fix was recorded. So arriving
 * is answered geometrically, by the fix being within [ARRIVED_WITHIN_KM] of
 * the trip's destination, and the order is the order of those last reports,
 * oldest first.
 *
 * Read plainly, that ranks the riders by *how long they have been at the
 * finish without reporting since* — and on the trips this is actually looked
 * at, that is the same list as who got there first. A rider who arrives stops
 * sharing, or their sharing session lapses on its own; either way their last
 * fix freezes near the moment they got there, and it stays frozen for as long
 * as the trip exists to be looked back on.
 *
 * Where it is knowingly weaker is a trip still under way with everybody parked
 * at the finish and still reporting: their last fixes are all "a few seconds
 * ago" and the order between them means little. It is never *unstable*, which
 * is the failure that would matter — the user id breaks every tie the clock
 * cannot, so the podium does not reshuffle itself under a rider's thumb on
 * each poll.
 *
 * Nothing here is a claim about road distance or about elapsed ride time, and
 * no screen may present it as one.
 *
 * ## Why nothing is shown rather than an empty podium
 *
 * A trip nobody has finished has no leaderboard, not an empty one. Most trips
 * in a rider's archive never had a destination set at all, and a row of grey
 * placeholders on every card would be the app filling space with the fact that
 * it knows nothing.
 */
object ArrivalBoard {

    /**
     * How close to the destination counts as being there, in kilometres.
     *
     * Wide enough to cover a car park, a hotel forecourt, or the far end of a
     * village square — a destination is a pin somebody dropped roughly, not a
     * doorway — and narrow enough that riding past on the bypass does not
     * count. Also comfortably wider than a phone's own error: a bad urban fix
     * is tens of metres, not hundreds.
     */
    const val ARRIVED_WITHIN_KM = 0.3

    /** How many places the podium has. */
    const val PLACES = 3

    /**
     * The first [PLACES] riders to the finish, or an empty list.
     *
     * Empty covers every "there is nothing to say" case together, because they
     * are one thing to the screen: no destination on the trip, nobody near it,
     * and nobody who has reported at all.
     */
    fun podium(destination: TripEndpoint?, members: List<MemberPosition>): List<Arrival> {
        val finish = destination?.let { LatLng(it.lat, it.lng) } ?: return emptyList()

        return members
            .mapNotNull { member ->
                val lat = member.lat ?: return@mapNotNull null
                val lng = member.lng ?: return@mapNotNull null
                val gap = RouteProgress.distanceKm(LatLng(lat, lng), finish)
                if (gap > ARRIVED_WITHIN_KM) return@mapNotNull null
                member to epochMillis(member.recordedAt)
            }
            // Oldest report first. A rider with no readable timestamp sorts
            // last rather than first: "no time" must not outrank a rider who
            // has one, and it is not evidence of having been there longest.
            .sortedWith(
                compareBy<Pair<MemberPosition, Long?>> { it.second ?: Long.MAX_VALUE }
                    .thenBy { it.first.userId }
            )
            .take(PLACES)
            .mapIndexed { index, (member, _) ->
                Arrival(
                    userId = member.userId,
                    place = index + 1,
                    label = member.label,
                    photoUrl = member.photoUrl,
                )
            }
    }

    private fun epochMillis(iso: String?): Long? {
        val text = iso?.takeIf { it.isNotBlank() } ?: return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
