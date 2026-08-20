package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ActiveTripException
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JoinTripUiState(
    val joining: Boolean = false,
    val error: String? = null,
    /** The trip already running that this join was refused for, if it was. */
    val blockedByTripName: String? = null,
)

/**
 * Redeeming a scanned join code.
 *
 * Separate from the trip list's view model because it is the one place the app
 * acts on a trip it is not yet a member of — there is nothing to refresh, only
 * a code to present and a trip to be sent to.
 */
class JoinTripViewModel(
    private val tripApi: TripApi,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JoinTripUiState())
    val uiState: StateFlow<JoinTripUiState> = _uiState.asStateFlow()

    /**
     * [onJoined] fires for an already-joined trip too. Scanning a code for a
     * trip you are already on is not a failure to report — it is the same
     * outcome the rider wanted, reached sooner.
     */
    fun join(code: String, onJoined: (Trip) -> Unit) {
        if (_uiState.value.joining) return

        viewModelScope.launch {
            _uiState.update { it.copy(joining = true, error = null, blockedByTripName = null) }
            try {
                val result = tripApi.joinByCode(code)
                _uiState.update { it.copy(joining = false) }
                onJoined(result.trip)
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ActiveTripException) {
                // Scanning a friend's code while still out on your own ride.
                _uiState.update {
                    it.copy(joining = false, error = e.message, blockedByTripName = e.tripName)
                }
            } catch (e: ApiException) {
                // The server's wording is better than anything decided here:
                // an expired code and an unknown one are different problems
                // with different next steps.
                _uiState.update { it.copy(joining = false, error = e.message) }
            }
        }
    }

    fun reset() {
        _uiState.value = JoinTripUiState()
    }
}
