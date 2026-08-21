package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.R
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

data class CreateTripUiState(
    val creating: Boolean = false,
    val error: String? = null,
    /**
     * The trip already running that this create was refused for, if it was.
     *
     * Kept apart from [error] because the screen can say this one better than
     * the server can — see [ActiveTripException]. Null for every other
     * failure, which is when [error] is the whole answer.
     */
    val blockedByTripName: String? = null,
)

class CreateTripViewModel(
    private val tripApi: TripApi,
    private val feedback: FeedbackCenter,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateTripUiState())
    val uiState: StateFlow<CreateTripUiState> = _uiState.asStateFlow()

    /**
     * [onCreated] runs only on success, so navigation stays out of the view
     * model and a failure leaves the user on the form with their text intact.
     */
    fun create(name: String, onCreated: (Trip) -> Unit) {
        if (_uiState.value.creating) return

        viewModelScope.launch {
            _uiState.update { it.copy(creating = true, error = null, blockedByTripName = null) }
            try {
                val trip = tripApi.createTrip(name)
                _uiState.update { it.copy(creating = false) }
                // Raised before [onCreated] navigates to the new trip, and
                // read on the screen it lands on — the bar lives above the
                // navigation stack.
                feedback.succeeded(R.string.feedback_trip_created, trip.name)
                onCreated(trip)
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ActiveTripException) {
                // One active trip per rider. Before the caught branch below,
                // because it is an ApiException too and the order is what
                // decides which of the two wordings the rider reads.
                _uiState.update {
                    it.copy(creating = false, error = e.message, blockedByTripName = e.tripName)
                }
                // No Retry: creating again would be refused again until the
                // running trip is ended. The form says which one that is.
                feedback.failed(e.message)
            } catch (e: ApiException) {
                _uiState.update { it.copy(creating = false, error = e.message) }
                feedback.failed(e.message) { create(name, onCreated) }
            }
        }
    }
}
