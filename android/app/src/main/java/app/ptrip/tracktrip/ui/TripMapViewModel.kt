package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.PlaceLookup
import app.ptrip.tracktrip.data.PositionSocket
import app.ptrip.tracktrip.data.RiderLevel
import app.ptrip.tracktrip.data.RouteLine
import app.ptrip.tracktrip.data.RouteLookup
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.TrailPoint
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.data.TripWaypoints
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.Breadcrumbs
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.map.RideOrder
import app.ptrip.tracktrip.map.RouteRequests
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripMapUiState(
    val loading: Boolean = true,
    val trip: Trip? = null,
    val members: List<MemberPosition> = emptyList(),
    /** Each member's rider level, keyed by user id. Empty until it loads. */
    val levels: Map<Long, RiderLevel> = emptyMap(),
    /** The trip's planned stops and live drops. Drawn, never listed as members. */
    val waypoints: TripWaypoints = TripWaypoints(),
    /**
     * The road between the trip's two ends, when the server managed to work
     * one out.
     *
     * Null is the ordinary state and not an error: no destination set yet, a
     * server with no LocationIQ key, an exhausted quota, or a pair of points
     * with no road between them. Everything that reads it falls back to the
     * straight line the map drew before road routing existed, and the label on
     * the progress bar says which of the two is on screen.
     */
    val route: RouteLine? = null,
    /**
     * Where each rider has just been, oldest point first, keyed by rider.
     *
     * Empty when the trail is switched off, when nobody has moved far enough
     * to have one, or when the fetch failed — all three draw nothing, which is
     * the same thing the map did before trails existed.
     */
    val trails: Map<Long, List<LatLng>> = emptyMap(),
    /** Whether the rider wants trails drawn. Remembered between rides. */
    val trailsVisible: Boolean = true,
    /**
     * Whether the trail has been asked for and come back with nothing.
     *
     * Its own flag rather than `trails.isEmpty()`, because those are two
     * different states with the same shape and only one of them is worth
     * saying out loud: "not fetched yet" is a moment, and "fetched, and this
     * trip has no history in the window" is a fact a rider needs, because it
     * is the state where switching the trail on looks exactly like a broken
     * button. Nothing is recorded until somebody actually shares their
     * position, and a rider testing the map has usually not.
     */
    val trailsEmpty: Boolean = false,
    /**
     * Whether [members] is ordered leader-first rather than by how recently
     * each rider reported.
     *
     * The screen says so when it is: the order is an estimate (see
     * [RideOrder]) and presenting it silently would read as a fact.
     */
    val orderedByProgress: Boolean = false,
    /**
     * Whether the live feed is currently carrying updates.
     *
     * Read by the screen to choose its poll cadence, and by nothing else — it
     * is deliberately not shown to a rider. A socket that has dropped means
     * the map is a poll behind instead of instant, which is what the app did
     * for its whole life until now; telling somebody on a motorcycle about it
     * would be an alarm about a thing they cannot act on.
     */
    val live: Boolean = false,
    val error: String? = null,
) {
    /** The riders with a fix — the ones that can be drawn. */
    val placed: List<MemberPosition> get() = members.filter { it.hasPosition }

    /** Everyone else. Listed, never dropped: an absent friend is information. */
    val unplaced: List<MemberPosition> get() = members.filterNot { it.hasPosition }
}

/** A member's fix as plain coordinates, or null if they have not reported. */
internal val MemberPosition.latLng: LatLng?
    get() {
        val lat = lat ?: return null
        val lng = lng ?: return null
        return LatLng(lat, lng)
    }

/**
 * The map's data: everyone on the trip and where they last were.
 *
 * Reads `GET /trips/:id/positions`, which already returns every member with
 * their latest fix — the same call the member list uses, so the map needs no
 * new endpoint. Levels come from `GET /trips/:id/member-levels`, which is a
 * batch for exactly this screen.
 *
 * Polling is driven by the screen rather than started here, so it stops when
 * the map is not being looked at. A view model scoped to the activity would
 * otherwise keep polling from behind three other screens.
 */
class TripMapViewModel(
    private val tripId: Long,
    private val tripApi: TripApi,
    private val positionSocket: PositionSocket?,
    private val onSessionExpired: () -> Unit,
    /**
     * Place search, or null in a preview or a test that does not exercise it.
     * The controller below does nothing at all when it is null, so nothing on
     * the screen has to check.
     */
    placeSearchApi: PlaceLookup? = null,
    /**
     * Road routing, or null in a preview or a test. Null behaves exactly like
     * a server with no key: no route, and the straight line stays.
     */
    private val routeApi: RouteLookup? = null,
    /**
     * Whether trails start switched on, and where the answer is written back
     * to. Defaulted so a preview or a test needs neither.
     */
    trailsVisible: Boolean = true,
    private val onTrailsVisibleChanged: (Boolean) -> Unit = {},
    /** The clock, injected so the trail window and the routing backoff can be tested. */
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /**
     * Typing a place name instead of hunting for it on the map.
     *
     * Scoped to this view model so the debounce survives a recomposition —
     * held in the composable it would restart on every keystroke, which is
     * precisely the thing a debounce exists to prevent.
     */
    val placeSearch = PlaceSearchController(
        scope = viewModelScope,
        api = placeSearchApi,
        onSessionExpired = onSessionExpired,
    )

    /**
     * Folds live positions into the list as the server stores them.
     *
     * The socket is a shortcut, never the record: everything it carries is
     * already stored and already readable by [refresh], so this loop can fail,
     * stall, or never connect at all and the screen still works — a poll
     * behind, which is exactly what it was before. That is why nothing here
     * touches [TripMapUiState.error]: there is no failure worth reporting.
     *
     * Null when no socket is available — a preview, or a test.
     */
    private fun listenForPositions() {
        val socket = positionSocket ?: return
        viewModelScope.launch {
            socket
                .positions(tripId) { connected -> _uiState.update { it.copy(live = connected) } }
                .collect { position -> applyLivePosition(position) }
        }
    }

    /**
     * Replaces one rider's row with a fresher fix.
     *
     * Only ever a replacement. A position for somebody who is not in the list
     * is dropped rather than appended: the roster comes from the poll, which
     * knows each member's name, photo and level, and a row built from a
     * position frame alone would appear as a nameless pin until the next
     * fetch. They join the map a poll later, which is the same moment they
     * would have before.
     *
     * The order is left alone. Re-sorting the list under a rider's finger
     * every time somebody moves would make it unusable; [refresh] re-orders on
     * its own beat, which is slow enough to read.
     */
    private fun applyLivePosition(position: MemberPosition) {
        _uiState.update { state ->
            val index = state.members.indexOfFirst { it.userId == position.userId }
            if (index < 0) return@update state

            val existing = state.members[index]
            // A socket can deliver out of order after a reconnection, and a
            // phone flushing a backlog can produce two fixes a second apart.
            // The newer one wins; an older one is not news.
            if (existing.recordedAt != null &&
                position.recordedAt != null &&
                position.recordedAt < existing.recordedAt
            ) {
                return@update state
            }

            val members = state.members.toMutableList()
            members[index] = position
            state.copy(members = members)
        }
        rememberFixes(listOf(position))
    }

    private val _uiState = MutableStateFlow(TripMapUiState(trailsVisible = trailsVisible))
    val uiState: StateFlow<TripMapUiState> = _uiState.asStateFlow()

    // After the state it writes to, not before it: an initialiser block runs
    // in declaration order, and one placed above `_uiState` would be starting
    // a listener that writes to a field that does not exist yet.
    init {
        listenForPositions()
    }

    /**
     * The last two *distinct* positions seen for each rider.
     *
     * Two maps rather than a list of fixes because only the pair matters: the
     * difference between them is what says which way the group is travelling.
     * A poll that brings the same position again leaves both alone — a rider
     * parked at a viewpoint must not have their movement vector quietly
     * shrink to nothing while the group's order stays meaningful.
     */
    private val lastFix = mutableMapOf<Long, LatLng>()
    private val previousFix = mutableMapOf<Long, LatLng>()

    /**
     * Fetches positions once.
     *
     * A failed poll leaves the last known positions on screen and says so,
     * rather than blanking the map — a rider who has lost signal for a moment
     * still wants to see where everyone was.
     */
    fun refresh() {
        viewModelScope.launch {
            try {
                val trip = _uiState.value.trip
                    ?: tripApi.listTrips().firstOrNull { it.id == tripId }
                val members = tripApi.members(tripId)

                val tracked = members.map { member ->
                    RideOrder.Tracked(
                        userId = member.userId,
                        position = member.latLng,
                        previous = previousFix[member.userId],
                    )
                }
                // Read the movement before recording it, or every rider would
                // appear to have just arrived where they already were.
                val order = RideOrder.leaderFirst(tracked)
                rememberFixes(members)

                val ordered = order?.let { ids ->
                    val rank = ids.withIndex().associate { (index, id) -> id to index }
                    members.sortedBy { rank[it.userId] ?: Int.MAX_VALUE }
                } ?: members

                _uiState.update {
                    it.copy(
                        loading = false,
                        trip = trip,
                        members = ordered,
                        orderedByProgress = order != null,
                        error = null,
                    )
                }

                loadLevels(members)
                loadWaypoints()
                // Not a fetch unless the trip's two ends have actually
                // changed — see loadRoute for why that matters here more than
                // anywhere else in the app.
                loadRoute(trip)
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /**
     * Levels, fetched when the roster changes rather than on every poll.
     *
     * A level moves after hundreds of kilometres; positions move every 45
     * seconds. Refetching one with the other would multiply the requests this
     * screen makes for a badge that is the same all afternoon.
     *
     * Its failure is swallowed on purpose: a missing badge is a row without a
     * badge, not an error message over a working map.
     */
    private suspend fun loadLevels(members: List<MemberPosition>) {
        val known = _uiState.value.levels
        val rosterChanged = members.any { it.userId !in known }
        if (known.isNotEmpty() && !rosterChanged) return

        val levels = try {
            tripApi.memberLevels(tripId)
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            return
        } catch (e: ApiException) {
            return
        }
        _uiState.update { it.copy(levels = levels) }
    }

    /**
     * The trip's waypoints, re-read on every poll.
     *
     * Unlike levels, these do change during a ride: a rider dropping a live
     * point at a viewpoint expects it on everyone's map within a poll or two,
     * not on the next app restart. The payload is a handful of rows.
     *
     * Its failure is swallowed for the same reason levels' is — a map that
     * still shows every rider is not broken because the stops did not load.
     */
    private suspend fun loadWaypoints() {
        val waypoints = try {
            tripApi.waypoints(tripId)
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            return
        } catch (e: ApiException) {
            return
        }
        _uiState.update { it.copy(waypoints = waypoints) }
    }

    /**
     * What [route] was fetched for, so a poll does not fetch it again.
     *
     * The single most important line in this file for the LocationIQ bill.
     * [refresh] runs every twenty seconds; the road between a trip's start and
     * its finish changes when the owner moves one of them, which is a handful
     * of times a ride. Keyed on the pair of endpoints rather than on a boolean
     * so that moving the finish re-fetches and moving nothing does not.
     */
    private var routeFor: Pair<LatLng, LatLng>? = null

    /**
     * When a failed routing attempt may be retried, or null when none has
     * failed.
     *
     * See [RouteRequests] for why a failure is neither permanent nor retried
     * on the next poll.
     */
    private var routeRetryAtMs: Long? = null

    /**
     * The road between the trip's two ends, fetched once per pair of ends.
     *
     * ## Why this is not folded into the poll
     *
     * Everything else on this screen is re-read every twenty seconds because
     * it changes every twenty seconds. A route does not: it is a function of
     * two coordinates the trip owner set by hand, and the quota behind it is
     * 5,000 requests a day shared by every rider on the server. Asking on
     * every poll would be roughly 180 requests an hour per rider watching a
     * map, which would empty the day's budget before lunch.
     *
     * So the pair of endpoints is the key. Same pair, no request. A cleared
     * destination drops the route rather than leaving the old one drawn, which
     * would be a road to somewhere the trip is no longer going.
     *
     * Its failure is swallowed, like levels' and waypoints': the map falls
     * back to the straight line it drew before routing existed, and the
     * progress bar's caption already says which of the two it is showing.
     */
    private suspend fun loadRoute(trip: Trip?) {
        val origin = trip?.origin?.let { LatLng(it.lat, it.lng) }
        val destination = trip?.destination?.let { LatLng(it.lat, it.lng) }

        if (origin == null || destination == null) {
            routeFor = null
            routeRetryAtMs = null
            if (_uiState.value.route != null) _uiState.update { it.copy(route = null) }
            return
        }

        val pair = origin to destination
        // The rule that keeps this off the poll's beat, written down where it
        // can be tested: see RouteRequests.
        if (!RouteRequests.shouldFetch(pair, routeFor, routeRetryAtMs, now())) return

        routeFor = pair
        routeRetryAtMs = null

        val api = routeApi ?: return
        val line = try {
            api.route(origin, destination)
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            return
        } catch (e: ApiException) {
            routeRetryAtMs = now() + RouteRequests.RETRY_AFTER_MS
            _uiState.update { it.copy(route = null) }
            return
        }
        _uiState.update { it.copy(route = line) }
    }

    /**
     * Every rider's breadcrumbs, held here rather than in the state.
     *
     * The state carries the drawn lines; this carries the points they are made
     * of, with their ids and timestamps, because that is what the next top-up
     * needs to ask for only what it has not already got. See [Breadcrumbs].
     */
    private var trailPoints: List<TrailPoint> = emptyList()

    /**
     * Tops the trail up.
     *
     * Driven by the screen on its own slow beat, not by [refresh]: a trail is
     * a line that has already happened, and a rider cannot see that its near
     * end is half a minute short. The first call asks for the whole window;
     * every one after it asks only for what has arrived since the newest point
     * already held, so the usual answer is a row or two per rider.
     *
     * Silent on failure, like everything else that is not a position: a map
     * with pins on it is not broken because the history did not load.
     */
    fun refreshTrail() {
        if (!_uiState.value.trailsVisible) return
        viewModelScope.launch {
            val nowMs = now()
            // The loop that chases a truncated answer lives in Breadcrumbs so
            // it can be tested against a stub — see Breadcrumbs.collect.
            trailPoints = try {
                Breadcrumbs.collect(trailPoints, nowMs) { since, limit ->
                    tripApi.trail(tripId, sinceIso = since, limit = limit)
                }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
                return@launch
            } catch (e: ApiException) {
                return@launch
            }

            // Recomputed even when nothing arrived: the window is a moving
            // one, so the tail of every line ages out whether or not the head
            // grew.
            val lines = Breadcrumbs.byRider(trailPoints, nowMs)
            _uiState.update { it.copy(trails = lines, trailsEmpty = lines.isEmpty()) }
        }
    }

    /**
     * Turns the trail on or off, and remembers the answer.
     *
     * Switching off throws the points away rather than hiding them: they age
     * out of the window anyway, and keeping a stale set to show instantly on
     * the way back would draw a line that stops where the rider was when they
     * pressed the button.
     */
    fun toggleTrails() {
        val visible = !_uiState.value.trailsVisible
        if (!visible) trailPoints = emptyList()
        _uiState.update {
            it.copy(
                trailsVisible = visible,
                trails = if (visible) it.trails else emptyMap(),
                // Switching on says nothing yet — the fetch has not run. Only
                // the fetch coming back empty earns the message.
                trailsEmpty = false,
            )
        }
        onTrailsVisibleChanged(visible)
        // Not refetched here: the screen's own loop is keyed on this flag and
        // fires the moment it flips, so doing it here as well would be two
        // requests for one button press.
    }

    /** Rolls each rider's fix forward, keeping the one before it. */
    private fun rememberFixes(members: List<MemberPosition>) {
        members.forEach { member ->
            val position = member.latLng ?: return@forEach
            val last = lastFix[member.userId]
            if (last == null) {
                lastFix[member.userId] = position
                return@forEach
            }
            if (last != position) {
                previousFix[member.userId] = last
                lastFix[member.userId] = position
            }
        }
    }

    /**
     * Puts a point the rider pressed and held on the map.
     *
     * One entry point for all three kinds because from the rider's side it is
     * one gesture with three answers; underneath, the two ends of a trip are
     * a PATCH on the trip itself and a waypoint is a row of its own. See
     * [MapPlacement] for why they are not the same thing.
     *
     * A failure is reported the way every other failure on this screen is —
     * as the line above the map — rather than by holding the dialog open. The
     * point is already on the rider's screen as a coordinate they chose; what
     * they need to know is whether the group can see it.
     */
    fun place(placement: MapPlacement, lat: Double, lng: Double, name: String) {
        val label = name.trim().takeIf { it.isNotEmpty() }
        // A waypoint with no name is refused by the server, so it is refused
        // here rather than sent: the dialog's button is already disabled in
        // that state, and this is the guard behind it.
        if (label == null && MapPlacementRules.nameRequired(placement)) return

        _uiState.update { it.copy(error = null) }

        viewModelScope.launch {
            try {
                when (placement) {
                    MapPlacement.ORIGIN ->
                        tripApi.setOrigin(tripId, TripEndpoint(lat, lng, label))
                    MapPlacement.DESTINATION ->
                        tripApi.setDestination(tripId, TripEndpoint(lat, lng, label))
                    MapPlacement.WAYPOINT -> tripApi.addWaypoint(
                        tripId = tripId,
                        name = label.orEmpty(),
                        lat = lat,
                        lng = lng,
                        type = Waypoint.TYPE_LIVE,
                    )
                }
                // The trip is re-read rather than patched in place: the server
                // is what decides what a trip's ends are, and one round trip
                // on an action a rider takes by hand costs nothing.
                refreshTrip()
                refresh()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Removes a dropped point.
     *
     * The server allows the trip's owner or whoever added it, and answers 403
     * otherwise — the screen only offers the control to those two, so a
     * refusal here means the rules changed underneath and is worth showing.
     */
    fun removeWaypoint(waypointId: Long) {
        viewModelScope.launch {
            try {
                tripApi.deleteWaypoint(tripId, waypointId)
                refresh()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Re-reads the trip itself.
     *
     * [refresh] only reaches for it when it has none, because a trip's name
     * and status do not change under a rider watching a map. Its ends do, the
     * moment they set one.
     */
    private suspend fun refreshTrip() {
        val trip = try {
            tripApi.listTrips().firstOrNull { it.id == tripId }
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            return
        } catch (e: ApiException) {
            return
        }
        trip?.let { fresh -> _uiState.update { it.copy(trip = fresh) } }
    }

    /** A location the phone could not produce — said out loud, not swallowed. */
    fun onNoLocation(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
