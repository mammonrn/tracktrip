package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

class CreateTripViewModel(
    private val tripApi: TripApi,
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
            _uiState.update { it.copy(creating = true, error = null) }
            try {
                val trip = tripApi.createTrip(name)
                _uiState.update { it.copy(creating = false) }
                onCreated(trip)
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(creating = false, error = e.message) }
            }
        }
    }
}
