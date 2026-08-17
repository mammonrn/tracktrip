package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.JoinCode
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.SuggestedInvitee
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
    /** Riders this owner has ridden with before, offered instead of typing. */
    val suggestions: List<SuggestedInvitee> = emptyList(),
    val joinCode: JoinCode? = null,
    val joinCodeLoading: Boolean = false,
    /** Kept apart from [error] so a QR failure doesn't sit on the trip screen. */
    val joinCodeError: String? = null,
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

                // Suggestions are owner-only and only for a running trip, so
                // asking otherwise would spend a request to be told 403.
                if (trip != null && trip.isOwner && trip.isActive) {
                    loadSuggestions()
                }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /**
     * Past companions, fetched quietly.
     *
     * A failure here is swallowed rather than shown: the invite form works
     * perfectly well without suggestions, and an error banner over a
     * convenience nobody asked for would be noise.
     */
    private suspend fun loadSuggestions() {
        try {
            val suggestions = tripApi.suggestedInvitees(tripId)
            _uiState.update { it.copy(suggestions = suggestions) }
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: ApiException) {
            _uiState.update { it.copy(suggestions = emptyList()) }
        }
    }

    /** Fills the invite field from a suggestion instead of typing it out. */
    fun useSuggestion(invitee: SuggestedInvitee) {
        _uiState.update { it.copy(inviteEmail = invitee.email, inviteSentTo = null, error = null) }
    }

    /**
     * Issues a join code for the QR screen.
     *
     * Always a fresh one, never a cached one: the server retires the previous
     * code when it issues another, so showing a remembered code after
     * generating a new one would show a QR that no longer works.
     */
    fun createJoinCode() {
        if (_uiState.value.joinCodeLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(joinCodeLoading = true, joinCodeError = null) }
            try {
                val code = tripApi.createJoinCode(tripId)
                _uiState.update { it.copy(joinCodeLoading = false, joinCode = code) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(joinCodeLoading = false, joinCodeError = e.message) }
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
