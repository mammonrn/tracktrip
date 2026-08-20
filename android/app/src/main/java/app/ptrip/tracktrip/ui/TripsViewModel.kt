package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.Invite
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripsUiState(
    val loading: Boolean = true,
    val trips: List<Trip> = emptyList(),
    val invites: List<Invite> = emptyList(),
    val error: String? = null,
    /** Id of the invite currently being accepted, so only its row shows a spinner. */
    val acceptingInviteId: Long? = null,
    /**
     * Whether [trips] is every trip on the server rather than this rider's.
     *
     * Only a super user can turn this on, and only the server decides whether
     * it takes effect — asking for it as anyone else answers with their own
     * trips, not with a refusal.
     */
    val showingAllTrips: Boolean = false,
    /**
     * Whether the trips older than the newest [TripListRules.RECENT] are on
     * screen.
     *
     * Here rather than in the composable because it is not only a question
     * about pixels: it decides which trips are *shown*, and the view model
     * reads leaderboards for the trips that are shown. A `rememberSaveable`
     * on the screen would leave those two able to disagree.
     *
     * Closed every time the screen is built, like [showingAllTrips]: opening
     * the app is the moment "what am I riding" matters most, and a rider who
     * went looking through their history last night has not asked for it to
     * still be open this morning.
     */
    val archiveOpen: Boolean = false,
)

/**
 * Backs the trip list: the rider's trips and any invitations waiting for
 * them, which are two calls but one screen.
 */
class TripsViewModel(
    private val tripApi: TripApi,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripsUiState())
    val uiState: StateFlow<TripsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                // Sequential rather than parallel: two small requests, and
                // running them one after another keeps a single failure
                // attributable to a single call.
                val all = _uiState.value.showingAllTrips
                val trips = tripApi.listTrips(all = all)
                val invites = tripApi.listInvites()
                _uiState.update {
                    it.copy(loading = false, trips = trips, invites = invites, error = null)
                }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /** Accepts an invitation and folds the joined trip into the list. */
    fun acceptInvite(invite: Invite) {
        if (_uiState.value.acceptingInviteId != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(acceptingInviteId = invite.id, error = null) }
            try {
                tripApi.acceptInvite(invite.id)
                // Re-read rather than patch the list locally: accepting also
                // removes the invite, and the server is the authority on both.
                val trips = tripApi.listTrips(all = _uiState.value.showingAllTrips)
                val invites = tripApi.listInvites()
                _uiState.update {
                    it.copy(acceptingInviteId = null, trips = trips, invites = invites)
                }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(acceptingInviteId = null, error = e.message) }
            }
        }
    }

    /**
     * Switches between "my trips" and "every trip", for a super user.
     *
     * The flag is set before the reload rather than after it, so the list that
     * comes back is the one the toggle now claims to be showing — the two
     * cannot disagree even for the moment the request is in flight.
     */
    fun setShowAllTrips(showAll: Boolean) {
        if (_uiState.value.showingAllTrips == showAll) return
        _uiState.update { it.copy(showingAllTrips = showAll) }
        refresh()
    }

    /**
     * Opens or closes the archive — everything older than the newest three.
     *
     * No reload: the list is already here in full, and this only decides how
     * much of it is drawn. What the archive costs is on the screen, not on the
     * wire.
     */
    fun setArchiveOpen(open: Boolean) {
        if (_uiState.value.archiveOpen == open) return
        _uiState.update { it.copy(archiveOpen = open) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
