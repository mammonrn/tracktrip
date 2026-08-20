package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.Place
import app.ptrip.tracktrip.data.PlaceLookup
import app.ptrip.tracktrip.data.PlaceSearchException
import app.ptrip.tracktrip.data.PlaceSearchProblem
import app.ptrip.tracktrip.data.PlaceSource
import app.ptrip.tracktrip.data.SharedPlaceStore
import app.ptrip.tracktrip.data.placeSearchProblem
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.map.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * When a typed query is worth a request, and when it is not.
 *
 * ## Why a debounce is part of the feature rather than a polish item
 *
 * The search runs on LocationIQ's free tier: 5,000 requests a day for the
 * whole server, shared by every rider on it. "Chiang Mai" is nine keystrokes.
 * Firing on each of them would spend nine requests to answer one question,
 * eight of them for prefixes nobody wanted the answer to — and a group of six
 * planning a ride would empty a day's quota in an afternoon of typing.
 *
 * So the app waits for the typing to stop, and the server caches what it
 * fetched (see `src/geocode/cache.js`). Two halves of the same budget: this
 * one stops one rider's typing costing ten requests, that one stops ten
 * riders' typing costing ten.
 */
object PlaceSearchRules {

    /**
     * How long the typing has to stop before a request goes out.
     *
     * Long enough to swallow the gaps inside a word typed at speed, short
     * enough that a rider who has finished typing does not think the field is
     * broken. Roughly the pause between words rather than between letters.
     */
    const val DEBOUNCE_MS = 450L

    /**
     * The shortest query worth sending. The server refuses anything shorter
     * too — this is what stops it being asked at all.
     */
    const val MIN_QUERY_LENGTH = 2

    /** As long as the server will accept. Past this it is a paste, not a name. */
    const val MAX_QUERY_LENGTH = 120

    /**
     * The query as it will be sent: trimmed, with runs of whitespace collapsed.
     *
     * Matches the server's own normalisation, which is what makes "chiang
     * mai" typed with a stray double space hit the same cache entry rather
     * than costing a second request.
     */
    fun normalize(raw: String): String = raw.trim().replace(WHITESPACE, " ")

    /** Whether this query is worth a request at all. */
    fun isSearchable(raw: String): Boolean {
        val query = normalize(raw)
        return query.length in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * What the search box is showing.
 *
 * [searched] is what tells "nothing is called that" apart from "nothing has
 * been asked yet" — the two look identical in the fields above it and read
 * completely differently to a rider.
 */
data class PlaceSearchState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Place> = emptyList(),
    /**
     * Why the last search failed, or null if it did not.
     *
     * The reason rather than a sentence, so the screen can say it in the
     * rider's language and say the right thing — see [PlaceSearchProblem] for
     * what the wrong thing cost.
     */
    val problem: PlaceSearchProblem? = null,
    /**
     * Whether any of [results] came from the riders' own list.
     *
     * Kept so the screen can head that group without walking the list, and so
     * "nothing found" can stay honest: a geocoder failure with shared results
     * behind it is not a failed search.
     */
    val hasShared: Boolean = false,
    /**
     * What the server said, kept only for [PlaceSearchProblem.UNKNOWN], where
     * the server knows something this app does not.
     */
    val serverMessage: String? = null,
    val searched: Boolean = false,
) {
    /** Whether to say "nothing found" rather than leaving the panel blank. */
    val isEmpty: Boolean get() = searched && !searching && problem == null && results.isEmpty()

    /** Whether there is anything at all to draw under the field. */
    val hasPanel: Boolean get() = searching || problem != null || results.isNotEmpty() || isEmpty
}

/**
 * How the two lists become one.
 *
 * ## Why the riders' own places go first
 *
 * They are the answer to the question the geocoder could not answer. Somebody
 * on this server typed "ปตท สวนดอก" because searching for it found nothing;
 * putting that row below eight towns whose names merely resemble it would
 * throw away the whole point of having written it down.
 *
 * There are never many — the server sends at most five — so first costs the
 * geocoder's results nothing but a scroll they mostly did not need.
 *
 * Duplicates are left alone rather than merged. A shared place and an OSM
 * object at the same spot are two different claims about it, and silently
 * collapsing them would hide whichever one a rider was looking for. They read
 * differently on screen, which is enough.
 */
fun mergePlaceResults(shared: List<Place>, geocoded: List<Place>): List<Place> =
    shared + geocoded

/**
 * The typing-to-results loop, kept out of the composable so it can be tested.
 *
 * One request in flight at a time, and the previous one is cancelled the
 * moment another key is pressed: a result for "chian" arriving after the one
 * for "chiang mai" would otherwise overwrite it, which is the classic
 * search-as-you-type bug and looks exactly like the search being wrong.
 *
 * [api] is null on a build with no search configured and in previews — the
 * controller then does nothing at all rather than the screen having to know.
 */
class PlaceSearchController(
    private val scope: CoroutineScope,
    private val api: PlaceLookup?,
    /**
     * The riders' own places.
     *
     * Separate from [api] because the two fail separately, and that is the
     * point of having both: LocationIQ answers 503 with no key on the server,
     * 429 when the day's quota is gone and 502 when it cannot be reached, and
     * on every one of those days this list still answers. Null in previews and
     * in the tests that are only about the debounce.
     */
    private val shared: SharedPlaceStore? = null,
    private val onSessionExpired: () -> Unit = {},
    private val debounceMs: Long = PlaceSearchRules.DEBOUNCE_MS,
) {

    private val _state = MutableStateFlow(PlaceSearchState())
    val state: StateFlow<PlaceSearchState> = _state.asStateFlow()

    private var pending: Job? = null

    /**
     * Where this phone is, for biasing results towards the region the rider is
     * actually in. Kept as a plain property because it changes on the location
     * feed's beat, not on the search's, and a search reads whatever is current
     * when it goes out.
     *
     * Null — no fix, no permission, a preview — searches unbiased, which is
     * what every search did before this existed.
     */
    var near: LatLng? = null

    /**
     * A keystroke.
     *
     * The field's text is updated at once — a field that lags behind the
     * keyboard is unusable — and only the *request* is deferred.
     */
    fun onQueryChanged(raw: String) {
        val typed = raw.take(PlaceSearchRules.MAX_QUERY_LENGTH)
        pending?.cancel()

        if (!PlaceSearchRules.isSearchable(typed)) {
            // Below the threshold there is nothing to show and nothing to
            // wait for, so the panel closes rather than holding the results
            // of a query that is no longer on screen.
            _state.value = PlaceSearchState(query = typed)
            return
        }

        _state.update {
            it.copy(query = typed, searching = true, problem = null, serverMessage = null)
        }

        pending = scope.launch {
            delay(debounceMs)
            run(PlaceSearchRules.normalize(typed))
        }
    }

    /** Empties the box and the panel, without cancelling anything else. */
    fun clear() {
        pending?.cancel()
        pending = null
        _state.value = PlaceSearchState()
    }

    /**
     * Runs the search the debounce has been waiting on.
     *
     * A failure is reported in the panel rather than thrown away: the most
     * likely one, by a distance, is a server whose LOCATIONIQ_API_KEY has not
     * been set, and "nothing found" would be the wrong thing to tell somebody
     * about that.
     */
    private suspend fun run(query: String) = coroutineScope {
        // `coroutineScope`, not `scope.async`: the two lookups have to be
        // children of the job the debounce cancels. Started on the view
        // model's scope they would outlive the keystroke that made them
        // irrelevant, and a search cancelled at the field would still be
        // running against the server.
        var sharedExpired = false
        val sharedDeferred = shared?.let { store ->
            async {
                try {
                    store.search(query, near = near).map { it.asPlace() }
                } catch (e: CancellationException) {
                    // Never swallowed: this one is the cancellation working.
                    throw e
                } catch (e: SessionExpiredException) {
                    // Recorded rather than thrown. Thrown, it would come out
                    // of the await and cancel whatever else is in flight; the
                    // geocoder is almost certainly about to report the same
                    // 401 anyway, and one sign-out is enough.
                    sharedExpired = true
                    emptyList()
                } catch (e: Exception) {
                    // This half failing is not something to report while the
                    // other half is answering — and on a backend older than
                    // this app it is a 404 on a route that does not exist yet,
                    // which must not read as a broken search. Anything at all,
                    // because a malformed body throws something that is not an
                    // ApiException and must not take the search down with it.
                    emptyList()
                }
            }
        }

        var geocoded: List<Place> = emptyList()
        var problem: PlaceSearchProblem? = null
        var serverMessage: String? = null

        // Null on a build with no search configured and in previews. Not a
        // failure and not reported as one: the riders' own list still answers.
        if (api != null) {
            try {
                geocoded = api.search(query, near = near)
            } catch (e: SessionExpiredException) {
                _state.update { it.copy(searching = false) }
                onSessionExpired()
                return@coroutineScope
            } catch (e: ApiException) {
                // A lookup that is not [PlaceSearchApi] — a stub, a preview —
                // throws a plain ApiException, so the reason is derived from
                // the status here as well rather than only inside the API class.
                problem = (e as? PlaceSearchException)?.problem ?: placeSearchProblem(e.status)
                serverMessage = e.message
            }
        }

        val sharedPlaces = sharedDeferred?.await().orEmpty()
        if (sharedExpired && problem == null && geocoded.isEmpty()) {
            // Only reachable with no geocoder configured — otherwise its own
            // 401 got there first and this function has already returned.
            _state.update { it.copy(searching = false) }
            onSessionExpired()
            return@coroutineScope
        }

        val merged = mergePlaceResults(sharedPlaces, geocoded)

        _state.update {
            // Guarded: the field may have moved on while this was in flight,
            // and painting a stale answer under a newer query is the bug this
            // whole class exists to avoid.
            if (PlaceSearchRules.normalize(it.query) != query) it
            else it.copy(
                searching = false,
                searched = true,
                results = merged,
                hasShared = sharedPlaces.isNotEmpty(),
                // A geocoder failure with the riders' own answers behind it is
                // not a failed search, and saying so would send somebody
                // looking for a fault while the row they wanted is on screen.
                problem = problem.takeIf { merged.isEmpty() },
                serverMessage = serverMessage.takeIf { merged.isEmpty() },
            )
        }
    }
}
