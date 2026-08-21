package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.ActiveTripException
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.Invite
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.map.Arrival
import app.ptrip.tracktrip.map.ArrivalBoard
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
    /**
     * The trip already running that an invite could not be accepted onto.
     *
     * Kept apart from [error] because the screen can say this one better than
     * the server can — see [ActiveTripException]. Null for every other
     * failure, which is when [error] is the whole answer.
     */
    val blockedByTripName: String? = null,
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
    /**
     * Who reached the finish first on each trip, by trip id.
     *
     * A trip with nobody on the podium is simply not in here — see
     * [ArrivalBoard] for why nothing is drawn rather than an empty one, and
     * for how honest the ordering is.
     */
    val podiums: Map<Long, List<Arrival>> = emptyMap(),
)

/**
 * Backs the trip list: the rider's trips and any invitations waiting for
 * them, which are two calls but one screen.
 */
class TripsViewModel(
    private val tripApi: TripApi,
    private val feedback: FeedbackCenter,
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
                    it.copy(
                        loading = false,
                        trips = trips,
                        invites = invites,
                        error = null,
                        // Dropped rather than kept: this is the rider asking
                        // for the current truth, and a podium read minutes ago
                        // is exactly what they pressed the button about.
                        podiums = emptyMap(),
                    )
                }
                loadPodiums()
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
            _uiState.update {
                it.copy(acceptingInviteId = invite.id, error = null, blockedByTripName = null)
            }
            try {
                tripApi.acceptInvite(invite.id)
                // Re-read rather than patch the list locally: accepting also
                // removes the invite, and the server is the authority on both.
                val trips = tripApi.listTrips(all = _uiState.value.showingAllTrips)
                val invites = tripApi.listInvites()
                _uiState.update {
                    it.copy(acceptingInviteId = null, trips = trips, invites = invites)
                }
                feedback.succeeded(
                    R.string.feedback_trip_joined,
                    invite.tripName ?: trips.firstOrNull { it.id == invite.tripId }?.name.orEmpty(),
                )
                // The trip just joined is new to the list and has no podium
                // yet; every other trip's is already here and is left alone.
                loadPodiums()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ActiveTripException) {
                // Invited to a ride while still out on one. The invite stays
                // pending on the server, so the list below is unchanged and
                // the rider can take it up once they have ended theirs.
                _uiState.update {
                    it.copy(
                        acceptingInviteId = null,
                        error = e.message,
                        blockedByTripName = e.tripName,
                    )
                }
                // No Retry: the answer will be the same until the rider has
                // ended the ride they are on, and a button that repeats a
                // refusal teaches nothing. The line on the screen names the
                // trip that is in the way, which is the actual next step.
                feedback.failed(e.message)
            } catch (e: ApiException) {
                _uiState.update { it.copy(acceptingInviteId = null, error = e.message) }
                feedback.failed(e.message) { acceptInvite(invite) }
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
        // The archive brings more cards on screen, and a card with a podium
        // next to cards without one would read as "nobody finished those".
        viewModelScope.launch { loadPodiums() }
    }

    /**
     * Reads the podium for every trip currently on screen that could have one.
     *
     * ## Why this is a request per trip, and why that is affordable
     *
     * `GET /trips` says nothing about who is where — positions are a separate
     * table behind a separate route, which is right, because a trip list has
     * no business paying for eight riders' coordinates on every trip a rider
     * has ever been on. So the podium is one `members` call per trip, and the
     * whole design is about keeping the number of those small:
     *
     *  - only the trips [TripListRules.shown] is drawing. Three, until a rider
     *    opens the archive;
     *  - only trips with a destination set. Without one there is no finish to
     *    have reached, and most trips in an old archive never had one;
     *  - only trips not already answered this session, so scrolling, rotating
     *    and toggling the archive back and forth cost nothing;
     *  - and never more than [MAX_PODIUMS] in one pass, so a rider with forty
     *    archived trips opening the archive is a handful of small reads rather
     *    than forty.
     *
     * Sequential rather than parallel, like the two reads above it: this is
     * decoration on a list that is already drawn, and it must never be the
     * reason the screen feels busy.
     *
     * Failures are swallowed whole. A podium that did not load is a card
     * without one, which is a card exactly as it looked last release — a
     * smaller thing by far than an error line over a rider's trips.
     */
    private suspend fun loadPodiums() {
        val state = _uiState.value
        val wanted = TripListRules.shown(state.trips, state.archiveOpen)
            .filter { it.destination != null && it.id !in state.podiums }
            .take(MAX_PODIUMS)
        if (wanted.isEmpty()) return

        val found = mutableMapOf<Long, List<Arrival>>()
        for (trip in wanted) {
            val members = try {
                tripApi.members(trip.id)
            } catch (e: SessionExpiredException) {
                // Not survivable and not this loop's to soften: every read
                // after it would fail the same way.
                onSessionExpired()
                return
            } catch (e: ApiException) {
                continue
            }
            // Recorded even when empty, so a trip nobody has finished is asked
            // about once rather than on every refresh.
            found[trip.id] = ArrivalBoard.podium(trip.destination, members)
        }

        _uiState.update { it.copy(podiums = it.podiums + found) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private companion object {
        /**
         * The most podiums to read in one pass.
         *
         * A ceiling on the burst rather than on the feature: a rider with
         * forty archived trips who opens the archive gets podiums on the ones
         * nearest the top and none further down, which is a card that looks
         * like last release rather than forty requests from a screen that was
         * already drawn.
         *
         * Eight rather than three because the cap only ever bites when the
         * archive is open — the list itself shows [TripListRules.RECENT] — so
         * it is sized for that case rather than the ordinary one.
         */
        const val MAX_PODIUMS = 8
    }
}
