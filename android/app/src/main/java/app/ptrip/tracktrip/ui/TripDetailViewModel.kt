package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.AppSettings
import app.ptrip.tracktrip.data.JoinCode
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.SuggestedInvitee
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.location.ActiveSharing
import app.ptrip.tracktrip.location.SharingController
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
    /** Riders invited since this screen opened, so they can sink down the list. */
    val invitedThisSession: Set<Long> = emptySet(),
    /** Set when a code has been issued *to share*, cleared once the sheet opens. */
    val pendingShareCode: JoinCode? = null,
    val sharingPending: Boolean = false,
) {
    /**
     * Suggestions with the ones already invited moved to the end.
     *
     * Moved rather than removed: inviting the wrong Nut is easy, and a chip
     * that vanishes on tap leaves no way to notice, let alone to invite the
     * right one afterwards. The sort is stable, so the server's frequency
     * order survives within each group.
     */
    val orderedSuggestions: List<SuggestedInvitee>
        get() = suggestions.sortedBy { it.userId in invitedThisSession }
}

class TripDetailViewModel(
    private val tripId: Long,
    private val tripApi: TripApi,
    private val sharingController: SharingController,
    private val settings: AppSettings,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {

    /** What this phone is broadcasting, straight from the location service. */
    val activeSharing: StateFlow<ActiveSharing?> = sharingController.active

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
                _uiState.update { state ->
                    val invited = state.suggestions
                        .firstOrNull { it.email.equals(invite.email, ignoreCase = true) }
                    state.copy(
                        invitePending = false,
                        inviteEmail = "",
                        inviteSentTo = invite.email,
                        invitedThisSession = invited
                            ?.let { state.invitedThisSession + it.userId }
                            ?: state.invitedThisSession,
                    )
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
                // The server has just cleared every sharing session on this
                // trip, this phone's included.
                onTripEnded()
                _uiState.update { it.copy(endPending = false, trip = ended) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(endPending = false, error = e.message) }
            }
        }
    }

    /**
     * Starts sharing this rider's location on this trip.
     *
     * The permission check happens on the way in, at the screen — by the time
     * this runs the answer is yes, and the only thing left that can fail is
     * the network.
     */
    fun startSharing(duration: SharingDuration) {
        if (_uiState.value.sharingPending) return
        val trip = _uiState.value.trip ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(sharingPending = true, error = null) }
            try {
                sharingController.start(trip, duration.minutes)
                // Worth remembering: the duration a rider picks here is
                // almost always the one they want next time too.
                settings.defaultSharingMinutes = duration.minutes
                _uiState.update { it.copy(sharingPending = false) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(sharingPending = false, error = e.message) }
            }
        }
    }

    fun stopSharing() {
        if (_uiState.value.sharingPending) return

        viewModelScope.launch {
            _uiState.update { it.copy(sharingPending = true, error = null) }
            try {
                sharingController.stop(tripId)
                _uiState.update { it.copy(sharingPending = false) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(sharingPending = false, error = e.message) }
            }
        }
    }

    /**
     * Issues a code and hands it back for the share sheet.
     *
     * A fresh one every time rather than reusing whatever the QR screen last
     * showed: issuing retires the previous code, so a link sent from a stale
     * one would be dead on arrival.
     */
    fun requestShareLink() {
        if (_uiState.value.joinCodeLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(joinCodeLoading = true, joinCodeError = null) }
            try {
                val code = tripApi.createJoinCode(tripId)
                _uiState.update {
                    it.copy(joinCodeLoading = false, joinCode = code, pendingShareCode = code)
                }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(joinCodeLoading = false, error = e.message) }
            }
        }
    }

    /** Called once the share sheet has been opened, so it opens only once. */
    fun shareLinkConsumed() {
        _uiState.update { it.copy(pendingShareCode = null) }
    }

    /**
     * Ending a trip clears every rider's sharing session on the server, so the
     * phone must stop too rather than carry on reporting into a 409.
     */
    fun onTripEnded() {
        if (sharingController.active.value?.tripId == tripId) {
            sharingController.stopLocally()
        }
    }

    /** A refused location permission, said out loud rather than silently. */
    fun onPermissionDenied(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
