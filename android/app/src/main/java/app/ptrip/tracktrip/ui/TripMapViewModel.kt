package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.RiderLevel
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.data.TripWaypoints
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.map.RideOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripMapUiState(
    val loading: Boolean = true,
    val trip: Trip? = null,
    val members: List<MemberPosition> = emptyList(),
    /** Each member's rider level, keyed by user id. Empty until it loads. */
    val levels: Map<Long, RiderLevel> = emptyMap(),
    /** The trip's planned stops and live drops. Drawn, never listed as members. */
    val waypoints: TripWaypoints = TripWaypoints(),
    /**
     * Whether [members] is ordered leader-first rather than by how recently
     * each rider reported.
     *
     * The screen says so when it is: the order is an estimate (see
     * [RideOrder]) and presenting it silently would read as a fact.
     */
    val orderedByProgress: Boolean = false,
    val error: String? = null,
) {
    /** The riders with a fix — the ones that can be drawn. */
    val placed: List<MemberPosition> get() = members.filter { it.hasPosition }

    /** Everyone else. Listed, never dropped: an absent friend is information. */
    val unplaced: List<MemberPosition> get() = members.filterNot { it.hasPosition }
}

/** A member's fix as plain coordinates, or null if they have not reported. */
internal val MemberPosition.latLng: LatLng?
    get() {
        val lat = lat ?: return null
        val lng = lng ?: return null
        return LatLng(lat, lng)
    }

/**
 * The map's data: everyone on the trip and where they last were.
 *
 * Reads `GET /trips/:id/positions`, which already returns every member with
 * their latest fix — the same call the member list uses, so the map needs no
 * new endpoint. Levels come from `GET /trips/:id/member-levels`, which is a
 * batch for exactly this screen.
 *
 * Polling is driven by the screen rather than started here, so it stops when
 * the map is not being looked at. A view model scoped to the activity would
 * otherwise keep polling from behind three other screens.
 */
class TripMapViewModel(
    private val tripId: Long,
    private val tripApi: TripApi,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripMapUiState())
    val uiState: StateFlow<TripMapUiState> = _uiState.asStateFlow()

    /**
     * The last two *distinct* positions seen for each rider.
     *
     * Two maps rather than a list of fixes because only the pair matters: the
     * difference between them is what says which way the group is travelling.
     * A poll that brings the same position again leaves both alone — a rider
     * parked at a viewpoint must not have their movement vector quietly
     * shrink to nothing while the group's order stays meaningful.
     */
    private val lastFix = mutableMapOf<Long, LatLng>()
    private val previousFix = mutableMapOf<Long, LatLng>()

    /**
     * Fetches positions once.
     *
     * A failed poll leaves the last known positions on screen and says so,
     * rather than blanking the map — a rider who has lost signal for a moment
     * still wants to see where everyone was.
     */
    fun refresh() {
        viewModelScope.launch {
            try {
                val trip = _uiState.value.trip
                    ?: tripApi.listTrips().firstOrNull { it.id == tripId }
                val members = tripApi.members(tripId)

                val tracked = members.map { member ->
                    RideOrder.Tracked(
                        userId = member.userId,
                        position = member.latLng,
                        previous = previousFix[member.userId],
                    )
                }
                // Read the movement before recording it, or every rider would
                // appear to have just arrived where they already were.
                val order = RideOrder.leaderFirst(tracked)
                rememberFixes(members)

                val ordered = order?.let { ids ->
                    val rank = ids.withIndex().associate { (index, id) -> id to index }
                    members.sortedBy { rank[it.userId] ?: Int.MAX_VALUE }
                } ?: members

                _uiState.update {
                    it.copy(
                        loading = false,
                        trip = trip,
                        members = ordered,
                        orderedByProgress = order != null,
                        error = null,
                    )
                }

                loadLevels(members)
                loadWaypoints()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /**
     * Levels, fetched when the roster changes rather than on every poll.
     *
     * A level moves after hundreds of kilometres; positions move every 45
     * seconds. Refetching one with the other would multiply the requests this
     * screen makes for a badge that is the same all afternoon.
     *
     * Its failure is swallowed on purpose: a missing badge is a row without a
     * badge, not an error message over a working map.
     */
    private suspend fun loadLevels(members: List<MemberPosition>) {
        val known = _uiState.value.levels
        val rosterChanged = members.any { it.userId !in known }
        if (known.isNotEmpty() && !rosterChanged) return

        val levels = try {
            tripApi.memberLevels(tripId)
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            return
        } catch (e: ApiException) {
            return
        }
        _uiState.update { it.copy(levels = levels) }
    }

    /**
     * The trip's waypoints, re-read on every poll.
     *
     * Unlike levels, these do change during a ride: a rider dropping a live
     * point at a viewpoint expects it on everyone's map within a poll or two,
     * not on the next app restart. The payload is a handful of rows.
     *
     * Its failure is swallowed for the same reason levels' is — a map that
     * still shows every rider is not broken because the stops did not load.
     */
    private suspend fun loadWaypoints() {
        val waypoints = try {
            tripApi.waypoints(tripId)
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            return
        } catch (e: ApiException) {
            return
        }
        _uiState.update { it.copy(waypoints = waypoints) }
    }

    /** Rolls each rider's fix forward, keeping the one before it. */
    private fun rememberFixes(members: List<MemberPosition>) {
        members.forEach { member ->
            val position = member.latLng ?: return@forEach
            val last = lastFix[member.userId]
            if (last == null) {
                lastFix[member.userId] = position
                return@forEach
            }
            if (last != position) {
                previousFix[member.userId] = last
                lastFix[member.userId] = position
            }
        }
    }

    /**
     * Puts a point the rider pressed and held on the map.
     *
     * One entry point for all three kinds because from the rider's side it is
     * one gesture with three answers; underneath, the two ends of a trip are
     * a PATCH on the trip itself and a waypoint is a row of its own. See
     * [MapPlacement] for why they are not the same thing.
     *
     * A failure is reported the way every other failure on this screen is —
     * as the line above the map — rather than by holding the dialog open. The
     * point is already on the rider's screen as a coordinate they chose; what
     * they need to know is whether the group can see it.
     */
    fun place(placement: MapPlacement, lat: Double, lng: Double, name: String) {
        val label = name.trim().takeIf { it.isNotEmpty() }
        // A waypoint with no name is refused by the server, so it is refused
        // here rather than sent: the dialog's button is already disabled in
        // that state, and this is the guard behind it.
        if (label == null && MapPlacementRules.nameRequired(placement)) return

        _uiState.update { it.copy(error = null) }

        viewModelScope.launch {
            try {
                when (placement) {
                    MapPlacement.ORIGIN ->
                        tripApi.setOrigin(tripId, TripEndpoint(lat, lng, label))
                    MapPlacement.DESTINATION ->
                        tripApi.setDestination(tripId, TripEndpoint(lat, lng, label))
                    MapPlacement.WAYPOINT -> tripApi.addWaypoint(
                        tripId = tripId,
                        name = label.orEmpty(),
                        lat = lat,
                        lng = lng,
                        type = Waypoint.TYPE_LIVE,
                    )
                }
                // The trip is re-read rather than patched in place: the server
                // is what decides what a trip's ends are, and one round trip
                // on an action a rider takes by hand costs nothing.
                refreshTrip()
                refresh()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Removes a dropped point.
     *
     * The server allows the trip's owner or whoever added it, and answers 403
     * otherwise — the screen only offers the control to those two, so a
     * refusal here means the rules changed underneath and is worth showing.
     */
    fun removeWaypoint(waypointId: Long) {
        viewModelScope.launch {
            try {
                tripApi.deleteWaypoint(tripId, waypointId)
                refresh()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Re-reads the trip itself.
     *
     * [refresh] only reaches for it when it has none, because a trip's name
     * and status do not change under a rider watching a map. Its ends do, the
     * moment they set one.
     */
    private suspend fun refreshTrip() {
        val trip = try {
            tripApi.listTrips().firstOrNull { it.id == tripId }
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            return
        } catch (e: ApiException) {
            return
        }
        trip?.let { fresh -> _uiState.update { it.copy(trip = fresh) } }
    }

    /** A location the phone could not produce — said out loud, not swallowed. */
    fun onNoLocation(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        /**
         * How often the map re-reads positions.
         *
         * Faster than the 45s at which a phone *reports*, so a new position
         * is on screen within about twenty seconds of the server having it
         * rather than waiting out a full reporting cycle on top of it. Reads
         * are not rate limited (see `POSITION_RATE_LIMIT` — the ceiling is on
         * posts), and the payload is one row per member.
         *
         * Deliberately not equal to the reporting cadence: two timers of the
         * same period drift into lockstep and the screen would spend most of
         * its life showing a fix it is about to replace.
         */
        const val POLL_INTERVAL_MS = 20_000L
    }
}
