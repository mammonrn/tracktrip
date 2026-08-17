package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.RiderLevel
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.data.TripWaypoints
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
     * A level moves after hundreds of kilometres; positions move every ten
     * minutes. Refetching one with the other would double the requests this
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
         * Slower than the ten minutes at which a phone *reports* its own
         * position, because this is only a screen being refreshed — there is
         * nothing newer to fetch most of the time. Fast enough that a rider
         * watching the map sees a friend move within a minute of the server
         * hearing about it.
         */
        const val POLL_INTERVAL_MS = 45_000L
    }
}
