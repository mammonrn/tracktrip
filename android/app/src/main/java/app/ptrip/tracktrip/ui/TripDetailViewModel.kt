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

data class TripDetailUiState(
    val loading: Boolean = true,
    val trip: Trip? = null,
    val members: List<MemberPosition> = emptyList(),
    val error: String? = null,
    val inviteEmail: String = "",
    val invitePending: Boolean = false,
    /** Set after a successful invite, cleared when the field is edited again. */
    val inviteSentTo: String? = null,
    val endPending: Boolean = false,
)

class TripDetailViewModel(
    private val tripId: Long,
    private val tripApi: TripApi,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripDetailUiState())
    val uiState: StateFlow<TripDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                // There is no GET /trips/:id, so the trip itself comes from
                // the list. One extra call, and it keeps `role` — which the
                // owner-only controls depend on — coming from the server
                // rather than being inferred here.
                val trip = tripApi.listTrips().firstOrNull { it.id == tripId }
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

    fun onInviteEmailChange(value: String) {
        _uiState.update { it.copy(inviteEmail = value, inviteSentTo = null, error = null) }
    }

    fun sendInvite() {
        val email = _uiState.value.inviteEmail.trim()
        if (email.isEmpty() || _uiState.value.invitePending) return

        viewModelScope.launch {
            _uiState.update { it.copy(invitePending = true, error = null, inviteSentTo = null) }
            try {
                val invite = tripApi.invite(tripId, email)
                _uiState.update {
                    it.copy(invitePending = false, inviteEmail = "", inviteSentTo = invite.email)
                }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(invitePending = false, error = e.message) }
            }
        }
    }

    fun endTrip() {
        if (_uiState.value.endPending) return

        viewModelScope.launch {
            _uiState.update { it.copy(endPending = true, error = null) }
            try {
                val ended = tripApi.endTrip(tripId)
                _uiState.update { it.copy(endPending = false, trip = ended) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(endPending = false, error = e.message) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
