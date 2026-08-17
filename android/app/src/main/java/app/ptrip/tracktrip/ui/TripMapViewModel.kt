package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripMapUiState(
    val loading: Boolean = true,
    val trip: Trip? = null,
    val members: List<MemberPosition> = emptyList(),
    val error: String? = null,
) {
    /** The riders with a fix — the ones that can be drawn. */
    val placed: List<MemberPosition> get() = members.filter { it.hasPosition }

    /** Everyone else. Listed, never dropped: an absent friend is information. */
    val unplaced: List<MemberPosition> get() = members.filterNot { it.hasPosition }
}

/**
 * The map's data: everyone on the trip and where they last were.
 *
 * Reads `GET /trips/:id/positions`, which already returns every member with
 * their latest fix — the same call the member list uses, so the map needs no
 * new endpoint.
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
                _uiState.update {
                    it.copy(loading = false, trip = trip, members = members, error = null)
                }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(loading = false, error = e.message) }
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
