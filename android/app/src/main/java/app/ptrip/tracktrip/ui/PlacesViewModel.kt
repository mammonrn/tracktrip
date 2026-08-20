package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.PersonalPlace
import app.ptrip.tracktrip.data.PersonalPlaceStore
import app.ptrip.tracktrip.data.PlaceLookup
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.SharedPlace
import app.ptrip.tracktrip.data.SharedPlaceStore
import app.ptrip.tracktrip.map.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the map-with-no-trip is showing.
 *
 * Deliberately small. There are no riders here, no positions, no route and no
 * poll — this screen is about places, and everything that made the trip map
 * expensive was about the ride rather than the map.
 */
data class PlacesUiState(
    /** Places on the shared list that *this* rider added, newest first. */
    val mine: List<SharedPlace> = emptyList(),
    /** This rider's private shortcuts. Nobody else can see one. */
    val personal: List<PersonalPlace> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * The map with no trip behind it.
 *
 * ## Why this is its own view model and not the trip map with nulls in it
 *
 * [TripMapViewModel] is mostly a ride: a position feed, a poll, a route
 * preview with its own request budget, a camera that follows a rider. None of
 * that has any meaning without a trip, and threading a null trip through all
 * of it would leave every one of those features one missed branch away from
 * running against nothing.
 *
 * What this screen shares with that one is the parts worth sharing, and they
 * are shared as objects rather than as inheritance: the same
 * [PlaceSearchController], the same two place stores, the same composables for
 * the map and the picker.
 *
 * [currentUserId] is here so the shared list can show which rows are this
 * rider's to remove — the same rule the search results use.
 */
class PlacesViewModel(
    private val sharedPlacesApi: SharedPlaceStore?,
    private val personalPlacesApi: PersonalPlaceStore?,
    placeSearchApi: PlaceLookup?,
    val currentUserId: Long?,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacesUiState())
    val uiState: StateFlow<PlacesUiState> = _uiState.asStateFlow()

    /**
     * The same typing-to-results loop the trip map uses, with the same
     * debounce and the same two sources.
     *
     * Held here rather than in the composable for the same reason it is held
     * there: a debounce that restarts on every recomposition is not a debounce.
     */
    val placeSearch = PlaceSearchController(
        scope = viewModelScope,
        api = placeSearchApi,
        shared = sharedPlacesApi,
        onSessionExpired = onSessionExpired,
    )

    init {
        refresh()
    }

    /** Both lists, which is everything this screen holds. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            loadMine()
            loadPersonal()
            _uiState.update { it.copy(loading = false) }
        }
    }

    /**
     * The shared places this rider added.
     *
     * Read as the whole list and filtered here, because the server has no
     * "mine" filter and should not grow one: the shared list is shared, and an
     * endpoint that answers "just yours" would be a second way to ask a
     * question this list deliberately does not care about. The filter is a
     * screen's convenience, so it lives on the screen's side.
     */
    private suspend fun loadMine() {
        val api = sharedPlacesApi ?: return
        try {
            val all = api.search(query = "", limit = SHARED_PAGE)
            _uiState.update { state ->
                state.copy(
                    mine = all.filter { it.createdBy != null && it.createdBy == currentUserId },
                    error = null,
                )
            }
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: ApiException) {
            _uiState.update { it.copy(error = e.message) }
        }
    }

    private suspend fun loadPersonal() {
        val api = personalPlacesApi ?: return
        try {
            val places = api.list()
            _uiState.update { it.copy(personal = places) }
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: ApiException) {
            // Swallowed: a row of shortcuts that did not load is a smaller
            // thing than an error over a map, and the shared half still works.
        }
    }

    /**
     * Saves a place to the shared list.
     *
     * Returns whether it worked, so the dialog can stay up with the reason on
     * it rather than closing over a failure — the two worth expecting are the
     * daily allowance and a backend older than this app.
     */
    suspend fun addShared(name: String, point: LatLng): Boolean {
        val api = sharedPlacesApi ?: return false
        val trimmed = name.trim()
        if (!RouteSetupRules.isStopNameValid(trimmed)) return false

        return try {
            api.add(trimmed, point)
            _uiState.update { it.copy(error = null) }
            loadMine()
            true
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            false
        } catch (e: ApiException) {
            _uiState.update { it.copy(error = e.message) }
            false
        }
    }

    /**
     * Saves a place to this rider's own list.
     *
     * A twin of [addShared] rather than a branch inside it, for the reason the
     * whole feature turns on: a private place and a shared one never travel
     * through the same code.
     */
    suspend fun addPersonal(label: String, name: String, point: LatLng): Boolean {
        val api = personalPlacesApi ?: return false
        val trimmedName = name.trim()
        val trimmedLabel = label.trim().takeIf { it.isNotEmpty() } ?: trimmedName
        if (!RouteSetupRules.isStopNameValid(trimmedName)) return false

        return try {
            api.add(trimmedLabel, trimmedName, point)
            _uiState.update { it.copy(error = null) }
            loadPersonal()
            true
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            false
        } catch (e: ApiException) {
            _uiState.update { it.copy(error = e.message) }
            false
        }
    }

    /** Removes one of this rider's shared places. The server checks again. */
    fun removeShared(id: Long) {
        val api = sharedPlacesApi ?: return
        viewModelScope.launch {
            try {
                api.remove(id)
                loadMine()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /** Removes one of this rider's private shortcuts. Nobody else is affected. */
    fun removePersonal(id: Long) {
        val api = personalPlacesApi ?: return
        viewModelScope.launch {
            try {
                api.remove(id)
                loadPersonal()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Says why "centre on me" did nothing.
     *
     * On the trip map this lives on the view model for the same reason it does
     * here: the button is pressed on the screen but the answer comes back from
     * the phone, one suspending call later, and a message held in the
     * composable would be gone if the rider had scrolled the list in between.
     */
    fun onNoLocation(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private companion object {
        /**
         * How much of the shared list to read to find this rider's own in it.
         *
         * The server clamps this to its own maximum, so it is a ceiling rather
         * than a promise — a rider who has added more shared places than this
         * sees the most recent of them, which is the half they are likely to
         * be tidying up.
         */
        const val SHARED_PAGE = 25
    }
}
