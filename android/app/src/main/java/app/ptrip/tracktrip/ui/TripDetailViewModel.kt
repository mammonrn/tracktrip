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
    /**
     * The addresses the server accepted on the last press, cleared when the
     * form is edited again.
     *
     * A list rather than the single address it used to be, because one press
     * of Invite can now carry several — chips picked and addresses typed, all
     * sent together. One name is still drawn as the name; several are drawn as
     * a count, because five addresses on one line is not a confirmation
     * anybody reads.
     */
    val inviteSent: List<String> = emptyList(),
    val endPending: Boolean = false,
    val renamePending: Boolean = false,
    /**
     * Kept apart from [error] for the same reason [joinCodeError] is: a rename
     * that failed belongs on the form the rider is looking at, not on the
     * member list they are about to come back to.
     */
    val renameError: String? = null,
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
     * Suggestions in the order the chips are drawn: anyone already on the trip
     * dropped, anyone invited in this sitting sunk to the end.
     *
     * The rules and the reasons are [InviteShortcuts.ordered]'s.
     */
    val orderedSuggestions: List<SuggestedInvitee>
        get() = InviteShortcuts.ordered(
            suggestions = suggestions,
            invited = invitedThisSession,
            memberIds = members.mapTo(mutableSetOf()) { it.userId },
        )

    /** The first five of those, and the rest behind a "+N more" chip. */
    val suggestionWindow: InviteShortcuts.Window
        get() = InviteShortcuts.window(orderedSuggestions)
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

    /**
     * Re-reads the trip and its members.
     *
     * ## Why this is now called by the screen, on a beat
     *
     * It used to be called exactly once, from this view model's `init`, and a
     * view model here is scoped to the activity and keyed by trip id — so
     * "once" meant once per app launch, not once per visit. Everything on the
     * screen was therefore frozen at whatever the server said the first time
     * it was opened, and it stayed frozen while a rider went to the map, came
     * back, and read it again.
     *
     * The loop lives in the screen for the same reason the map's does: a view
     * model outlives the screen, so a poll started here would go on fetching
     * from behind three other screens.
     *
     * That is where the battery readings came apart. Both screens show the
     * same field from the same endpoint — `battery_pct` off
     * `GET /trips/:id/positions` — but the map polls it every twenty seconds
     * and folds live socket frames in on top, while this one had not asked
     * since the app started. Two screens, one number, hours apart: 37% here
     * against 76% on the map, a phone that had spent the morning on a charger
     * on the handlebars, and nothing on either screen to say that one of them
     * was reading a value from before breakfast.
     *
     * [quiet] is for the poll: it skips the loading flag and leaves any error
     * banner alone, so a background refresh cannot make the screen flicker or
     * clear a message the rider has not read yet. The pull the rider asks for
     * — opening the screen — is not quiet.
     */
    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) {
                _uiState.update { it.copy(loading = true, error = null) }
            }
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
                // A failed poll leaves the last known members on screen rather
                // than blanking the list — the same rule the map follows — and
                // says what went wrong.
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

    /**
     * Renames the trip.
     *
     * [onRenamed] runs only on success, so navigation stays out of the view
     * model and a failure leaves the rider on the form with their text intact
     * — the same shape [CreateTripViewModel.create] uses.
     *
     * The trip in state is replaced with what the server sent back rather than
     * with what was typed, and no refresh is asked for: the response to a
     * PATCH *is* the trip, so the member list this screen sits in front of
     * shows the new title the moment the rider lands back on it, without a
     * round trip they would watch happen.
     */
    fun rename(name: String, onRenamed: () -> Unit) {
        val trimmed = name.trim()
        if (_uiState.value.renamePending || !TripNameRules.isValid(trimmed)) return

        viewModelScope.launch {
            _uiState.update { it.copy(renamePending = true, renameError = null) }
            try {
                val trip = tripApi.renameTrip(tripId, trimmed)
                _uiState.update { it.copy(renamePending = false, renameError = null, trip = trip) }
                onRenamed()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(renamePending = false, renameError = e.message) }
            }
        }
    }

    /** Clears a rename failure, so leaving the form does not carry it back. */
    fun clearRenameError() {
        _uiState.update { it.copy(renameError = null) }
    }

    /**
     * Invites one past companion, on one tap of their chip.
     *
     * The chip used to open the email dialog with the address filled in, which
     * made the shortcut a shortcut to a form rather than to the thing the
     * rider wanted. A name they have ridden with before, on their own
     * suggestion list, needs no second confirmation — and the list refills
     * behind the press ([InviteShortcuts]), so working down a group is the
     * same tap repeated rather than four screens each time.
     */
    fun inviteSuggestion(invitee: SuggestedInvitee) {
        sendInvites(listOf(invitee.email))
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
        _uiState.update { it.copy(inviteEmail = value, inviteSent = emptyList(), error = null) }
    }

    /**
     * Invites everybody named in [emails], on one press.
     *
     * ## One press, several riders
     *
     * The dialog used to be one address and one Invite, so adding four people
     * meant opening it, typing, sending, watching it close and opening it
     * again — four times, for what is one intention: these four are coming.
     * Now the chips are multi-select, the field takes a list, and this is what
     * both arrive at.
     *
     * ## Why it is still one request each
     *
     * `POST /trips/:id/invites` takes one address. There is no bulk endpoint,
     * and inventing one on the client — firing them together and hoping — is
     * how a rate limit turns a five-rider invite into two riders and three
     * silences. They go one after another, in the order the rider chose them.
     *
     * ## Partial success is reported as partial
     *
     * A failure does not stop the rest, because the addresses are independent:
     * one already on the trip must not cost the other four their invite. What
     * came back is kept apart from what did not — [TripDetailUiState.inviteSent]
     * lists the accepted ones and the error line names each refusal with the
     * address it belongs to. Anything else would leave a rider re-sending four
     * invitations to find out which one bounced.
     */
    fun sendInvites(emails: List<String>) {
        if (emails.isEmpty() || _uiState.value.invitePending) return

        viewModelScope.launch {
            _uiState.update { it.copy(invitePending = true, error = null, inviteSent = emptyList()) }

            val sent = mutableListOf<String>()
            val refused = mutableListOf<String>()
            for (email in emails) {
                try {
                    sent += tripApi.invite(tripId, email).email
                } catch (e: SessionExpiredException) {
                    // Nothing after this would fare better, and the rider is
                    // about to be sent to the sign-in screen.
                    onSessionExpired()
                    return@launch
                } catch (e: ApiException) {
                    refused += "$email: ${e.message}"
                }
            }

            _uiState.update { state ->
                val invitedIds = state.suggestions
                    .filter { suggestion -> sent.any { it.equals(suggestion.email, true) } }
                    .map { it.userId }
                state.copy(
                    invitePending = false,
                    // Only what landed is cleared from the field. A refused
                    // address stays where the rider can see and fix it.
                    inviteEmail = if (refused.isEmpty()) "" else state.inviteEmail,
                    inviteSent = sent,
                    invitedThisSession = state.invitedThisSession + invitedIds,
                    error = refused.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                )
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
