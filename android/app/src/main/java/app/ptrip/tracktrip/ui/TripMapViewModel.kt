package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.PersonalPlace
import app.ptrip.tracktrip.data.PersonalPlaceStore
import app.ptrip.tracktrip.data.PlaceLookup
import app.ptrip.tracktrip.data.SharedPlaceStore
import app.ptrip.tracktrip.data.PositionSocket
import app.ptrip.tracktrip.data.RiderLevel
import app.ptrip.tracktrip.data.RouteLine
import app.ptrip.tracktrip.data.RouteLookup
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.data.TripWaypoints
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.map.RideOrder
import app.ptrip.tracktrip.map.DirectProgress
import app.ptrip.tracktrip.map.RoutePlan
import app.ptrip.tracktrip.map.RoutePlans
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
    /**
     * The route a rider is setting up, before anybody has confirmed it.
     *
     * Empty whenever the route card is closed, which is what makes every
     * derived value below fall back to the trip's own ends and the trip's own
     * road. Nothing in here is on the server until [TripMapViewModel.confirmRoute]
     * is called — see [RouteDraft] for why that is the point of it.
     */
    val routeDraft: RouteDraft = RouteDraft(),
    /**
     * The road along the draft's two ends, when they are not the trip's own.
     *
     * Null when the draft is incomplete, when the draft happens to be the
     * route the trip already has (in which case [route] is the same answer,
     * already paid for), or when the fetch found no road.
     */
    val routePreview: RouteLine? = null,
    /** Whether a preview is in flight, so the summary can say so rather than read empty. */
    val routePreviewLoading: Boolean = false,
    /**
     * This rider's own saved places, for the shortcut chips over the search.
     *
     * Theirs and nobody else's — the server has no way to answer with anybody
     * else's, and this app has no way to ask. See [PersonalPlace].
     */
    val personalPlaces: List<PersonalPlace> = emptyList(),
    val error: String? = null,
) {
    /** The riders with a fix — the ones that can be drawn. */
    val placed: List<MemberPosition> get() = members.filter { it.hasPosition }

    /** Everyone else. Listed, never dropped: an absent friend is information. */
    val unplaced: List<MemberPosition> get() = members.filterNot { it.hasPosition }

    /**
     * Whether the draft is a route of its own rather than the trip's, redrawn.
     *
     * The distinction the three values below turn on: a rider who opened the
     * card and changed nothing is looking at the trip, and everything should
     * read exactly as it did before they opened it.
     */
    private val hasOwnDraft: Boolean
        get() = routeDraft.isComplete &&
            !RouteSetupRules.matchesTrip(routeDraft, waypoints.all, trip?.origin, trip?.destination)

    /**
     * The route the trip currently describes: its two ends and its planned
     * stops, in order. Null until both ends are set.
     *
     * What the progress bar measures against when there is no road route to
     * measure against — see [DirectProgress] — and what `loadRoute` asks the
     * server for.
     */
    val tripPlan: RoutePlan?
        get() = RoutePlans.of(
            from = trip?.origin?.let { LatLng(it.lat, it.lng) },
            to = trip?.destination?.let { LatLng(it.lat, it.lng) },
            waypoints = waypoints.all,
        )

    /** The route the open card describes, including stops it has not saved yet. */
    val draftPlan: RoutePlan? get() = RouteSetupRules.plan(routeDraft)

    /**
     * The route the summary sheet measures: the draft's when there is one, and
     * the trip's the rest of the time.
     */
    val summaryPlan: RoutePlan? get() = draftPlan ?: tripPlan

    /**
     * The route the summary sheet reads: the draft's when there is one, and
     * otherwise the trip's own — which is the same line, already fetched.
     */
    val draftRoute: RouteLine? get() = if (hasOwnDraft) routePreview else route

    /**
     * The two ends the map draws flags on.
     *
     * The draft's while one is being set up, so a rider sees the start land
     * the moment they pick it rather than after they confirm; the trip's the
     * rest of the time, which is every moment the card is closed.
     */
    val drawnOrigin: TripEndpoint?
        get() = routeDraft.from?.let { TripEndpoint(it.point.lat, it.point.lng, it.label) }
            ?: trip?.origin

    /** Where the map draws the finish. See [drawnOrigin]. */
    val drawnDestination: TripEndpoint?
        get() = routeDraft.to?.let { TripEndpoint(it.point.lat, it.point.lng, it.label) }
            ?: trip?.destination

    /**
     * The line the map draws.
     *
     * A draft with its own two ends draws its own preview and nothing else:
     * leaving the trip's road on screen under a start the rider has just
     * moved would be a road to somewhere they are no longer setting off from.
     */
    val drawnRoute: List<LatLng> get() = draftRoute?.points.orEmpty()

    /**
     * Every stop the map draws.
     *
     * With the route list open, the draft *is* the planned route — it was
     * seeded from the trip's stops, so drawing both would put two pins on
     * every one of them. The trip's live waypoints stay: those are dropped
     * while riding and are not part of the road anybody planned, so no draft
     * ever holds them.
     *
     * With it closed, the trip's own, exactly as before.
     */
    val drawnWaypoints: List<Waypoint>
        get() = if (routeDraft.isEmpty) waypoints.all
        else waypoints.live + RouteSetupRules.draftWaypoints(routeDraft)
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
     * The riders' own places, or null in a preview or a test.
     *
     * Separate from [placeSearchApi] because the two answer separately: this
     * one is a table on this app's own server and needs no metered key, so it
     * still answers on the day LocationIQ does not. See [SharedPlaceStore].
     */
    private val sharedPlacesApi: SharedPlaceStore? = null,
    /**
     * This rider's own places, or null in a preview or a test.
     *
     * A separate dependency from [sharedPlacesApi] rather than a mode on it,
     * because the property worth keeping is that a private place and a shared
     * one never travel through the same code.
     */
    private val personalPlacesApi: PersonalPlaceStore? = null,
    /**
     * Road routing, or null in a preview or a test. Null behaves exactly like
     * a server with no key: no route, and the straight line stays.
     */
    private val routeApi: RouteLookup? = null,
    /** The clock, injected so the routing backoff can be tested. */
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
        shared = sharedPlacesApi,
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

    private val _uiState = MutableStateFlow(TripMapUiState())
    val uiState: StateFlow<TripMapUiState> = _uiState.asStateFlow()

    // After the state it writes to, not before it: an initialiser block runs
    // in declaration order, and one placed above `_uiState` would be starting
    // a listener that writes to a field that does not exist yet.
    init {
        listenForPositions()
        // Once, on the way in. These change only when this rider changes them,
        // so there is nothing for a poll to notice — and the shortcut row has
        // to be there the first time the search opens, not a beat later.
        loadPersonalPlaces()
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
                // Not a fetch unless the route has actually changed — see
                // loadRoute for why that matters here more than anywhere else
                // in the app.
                loadRoute(trip)
                // An open card's preview follows the trip as well as the
                // rider: another member adding a planned stop changes the road
                // the summary sheet is quoting, and the sheet sits directly
                // above the button that saves it. Costs nothing when nothing
                // moved — RouteRequests is the guard, the same one that keeps
                // loadRoute off this beat.
                if (!_uiState.value.routeDraft.isEmpty) refreshRoutePreview()
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
    private var routeFor: RoutePlan? = null

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
        // The trip's planned stops are part of the route, not decoration on
        // top of it — see RoutePlans. Read from the state rather than passed
        // in, because loadWaypoints has just written them there and they are
        // what makes the line go the way the rider planned.
        val plan = RoutePlans.of(
            from = trip?.origin?.let { LatLng(it.lat, it.lng) },
            to = trip?.destination?.let { LatLng(it.lat, it.lng) },
            waypoints = _uiState.value.waypoints.all,
        )

        if (plan == null) {
            routeFor = null
            routeRetryAtMs = null
            if (_uiState.value.route != null) _uiState.update { it.copy(route = null) }
            return
        }

        // The rule that keeps this off the poll's beat, written down where it
        // can be tested: see RouteRequests. The whole plan is the key, so a
        // stop added mid-ride re-fetches once and a poll never does.
        if (!RouteRequests.shouldFetch(plan, routeFor, routeRetryAtMs, now())) return

        routeFor = plan
        routeRetryAtMs = null

        val api = routeApi ?: return
        val line = try {
            api.route(plan.from, plan.to, plan.via)
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

    /** What [TripMapUiState.routePreview] was fetched for. See [routeFor]. */
    private var previewFor: RoutePlan? = null

    /** When a failed preview may be tried again. See [routeRetryAtMs]. */
    private var previewRetryAtMs: Long? = null

    /**
     * Opens the route card on what the trip already has.
     *
     * Seeded rather than blank, which is the whole of requirement four: after
     * a route is confirmed, opening the card again shows it, and changing one
     * end is one tap. The edit mechanism did not change — the way in did.
     */
    fun openRouteSetup() {
        viewModelScope.launch {
            // Re-read before seeding, rather than trusting whatever the last
            // poll left behind. Two reasons, and the first is a race this
            // feature would lose silently without it: the screen can open the
            // list as soon as the *trip* has loaded, which on a cold start is
            // before the waypoints have — and a list seeded from an empty
            // `waypoints` is exactly the two-empty-ends bug this change is
            // fixing, re-created one layer up.
            //
            // The second is that a trip is shared. Somebody may have added a
            // stop since the last poll, and a rider about to re-order the
            // route should be arranging what the route actually is.
            //
            // A failed fetch falls through to what is already in state, which
            // is what every other read on this screen does.
            loadWaypoints()

            _uiState.update {
                it.copy(
                    routeDraft = RouteSetupRules.fromTrip(
                        it.trip?.origin,
                        it.trip?.destination,
                        // The stops the trip already has, so the list a rider
                        // reopens is the route they saved rather than two
                        // empty ends. This one argument is the bug fix:
                        // without it the draft could only ever describe stops
                        // it had made itself.
                        it.waypoints.planned,
                    ),
                )
            }
            refreshRoutePreview()
        }
    }

    /**
     * Throws the draft away.
     *
     * Closing the card *is* cancelling: nothing in a draft has been sent, so
     * there is nothing to undo, and keeping a half-finished route to show on
     * the way back would mean a rider re-opening the card to look at their
     * trip's route and being shown somebody's abandoned edit of it instead.
     */
    fun closeRouteSetup() {
        previewFor = null
        previewRetryAtMs = null
        _uiState.update {
            it.copy(routeDraft = RouteDraft(), routePreview = null, routePreviewLoading = false)
        }
    }

    /**
     * Puts a chosen point into the draft.
     *
     * No round trip, and no question about what the point is for: the field
     * the rider tapped to open the picker is the answer, which is the reason
     * the old three-button dialog is gone from this path.
     */
    fun pickRoutePoint(field: RouteField, picked: RoutePoint) {
        _uiState.update { it.copy(routeDraft = RouteSetupRules.with(it.routeDraft, field, picked)) }
        // Every kind of point changes the road, stops included: the route is
        // one line through all of them. Adding a stop that did not re-route
        // was the bug — a sheet quoting the distance of a road the rider was
        // no longer going to take, right above the button that saves it.
        refreshRoutePreview()
    }

    /** Takes a stop back off the draft. Nothing was ever sent, so nothing is deleted. */
    fun removeRouteStop(index: Int) {
        _uiState.update { it.copy(routeDraft = RouteSetupRules.withoutStop(it.routeDraft, index)) }
        // A route with one fewer stop is a different road, so the sheet's
        // figures have to stop describing the old one.
        refreshRoutePreview()
    }

    /**
     * Clears one row of the route list — an end, or a stop.
     *
     * The same call for all three because on the list they are one thing: a
     * row with a cross on it. Which of them it was is [RouteSetupRules]'
     * business, and clearing an end leaves the row asking to be filled rather
     * than shortening the list under the rider's finger.
     */
    fun removeRouteRow(index: Int) {
        _uiState.update { it.copy(routeDraft = RouteSetupRules.withoutRow(it.routeDraft, index)) }
        refreshRoutePreview()
    }

    /**
     * Drags one row of the route list to another position.
     *
     * ## Why this is the whole of "the order index updates immediately"
     *
     * A stop's `order_index` is not stored on the draft — it *is* the stop's
     * position in [RouteDraft.stops], read off the list by [confirmRoute] and
     * by [RouteSetupRules.draftWaypoints]. So this one update renumbers the
     * pins on the map, re-measures the road, and fixes what the confirm will
     * write, with no second field that could fall out of step with it.
     *
     * And it stays a draft. Nothing here is sent: the trip's waypoints are
     * untouched until the rider confirms, exactly as with every other edit on
     * this card.
     */
    fun moveRoutePoint(from: Int, to: Int) {
        val before = _uiState.value.routeDraft
        val after = RouteSetupRules.moved(before, from, to)
        // A drag that changed nothing must not spend a routing request. A
        // finger crossing a row boundary and coming back is one gesture and
        // several of these.
        if (after == before) return
        _uiState.update { it.copy(routeDraft = after) }
        refreshRoutePreview()
    }

    /**
     * Writes the draft to the trip.
     *
     * Exactly the calls the old flow made when a rider finished setting both
     * ends by hand — one PATCH per end, one POST per stop — with two
     * differences that both come out of holding the draft first: an end that
     * did not move costs no request at all, and the stops go on in the order
     * the rider added them because there is an order to read.
     *
     * The draft is cleared here, before the writes, rather than by the screen:
     * the screen closes the card the moment this is called, and a failure is
     * reported as the line above the map like every other failure on this
     * screen. Holding the card open over a failed save would leave a rider
     * looking at a route they cannot tell apart from a saved one.
     */
    fun confirmRoute() {
        val state = _uiState.value
        val trip = state.trip
        val draft = state.routeDraft

        // Cleared first and unconditionally, before anything can return early.
        // The screen closes the card the moment this is called, and a draft
        // left behind a closed card would go on drawing its own flags over the
        // trip's for the rest of the ride.
        previewFor = null
        previewRetryAtMs = null
        _uiState.update {
            it.copy(
                routeDraft = RouteDraft(),
                routePreview = null,
                routePreviewLoading = false,
                error = null,
            )
        }

        if (trip == null || !draft.isComplete) return

        viewModelScope.launch {
            try {
                // Owner-gated here as well as on the card, because the server
                // gates it: a member who is not the owner can only ever have
                // got this far to add stops.
                if (RouteSetupRules.canEditEnds(trip.isOwner)) {
                    RouteSetupRules.endpointToSave(draft.from, trip.origin)
                        ?.let { tripApi.setOrigin(tripId, it) }
                    RouteSetupRules.endpointToSave(draft.to, trip.destination)
                        ?.let { tripApi.setDestination(tripId, it) }
                }

                if (RouteSetupRules.canAddStops(trip.isActive)) {
                    applyWaypointEdits(
                        RouteSetupRules.waypointEdits(state.waypoints.all, draft.stops)
                    )
                }

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
     * Reads this rider's own places.
     *
     * Its failure is swallowed: shortcuts that did not load are a row of chips
     * that is not there, which is a smaller thing than an error line over a
     * map. The search underneath still works, and so does everything else.
     */
    fun loadPersonalPlaces() {
        val api = personalPlacesApi ?: return
        viewModelScope.launch {
            val places = try {
                api.list()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
                return@launch
            } catch (e: ApiException) {
                return@launch
            }
            _uiState.update { it.copy(personalPlaces = places) }
        }
    }

    /**
     * Saves a place to this rider's own list, and hands it back for the row
     * that asked for it.
     *
     * Deliberately a twin of [addSharedPlace] rather than a branch inside it.
     * The two write to different tables through different routes under
     * different rules, and the one mistake this feature cannot afford is a
     * private place taking a shared code path — which is exactly what a
     * boolean parameter on one function invites.
     */
    suspend fun addPersonalPlace(label: String, name: String, point: LatLng): RoutePoint? {
        val api = personalPlacesApi ?: return null
        val trimmedName = name.trim()
        val trimmedLabel = label.trim().takeIf { it.isNotEmpty() } ?: trimmedName
        if (!RouteSetupRules.isStopNameValid(trimmedName)) return null

        return try {
            val saved = api.add(trimmedLabel, trimmedName, point)
            _uiState.update { it.copy(error = null) }
            loadPersonalPlaces()
            RoutePoint(saved.point, saved.name)
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            null
        } catch (e: ApiException) {
            _uiState.update { it.copy(error = e.message) }
            null
        }
    }

    /** Takes one of this rider's own shortcuts away. Nobody else is affected. */
    fun removePersonalPlace(id: Long) {
        val api = personalPlacesApi ?: return
        viewModelScope.launch {
            try {
                api.remove(id)
                _uiState.update { it.copy(error = null) }
                loadPersonalPlaces()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Writes a place to the shared list, and hands it back for the slot that
     * asked for it.
     *
     * ## Why this returns the point rather than doing something with it
     *
     * Adding a place and *using* one are two things that happen together but
     * are not the same: a rider who could not find "ปตท สวนดอก" was in the
     * middle of filling a From/To/stop row when they gave up and typed it in,
     * and dropping them back at an empty row afterwards would make them search
     * again for the thing they just created. So this saves it and returns it,
     * and the screen puts it where the rider was already pointing.
     *
     * Null on failure, with the reason reported the way every other failure on
     * this screen is — the line above the map. The two worth expecting are the
     * daily allowance (429, and the server's own sentence says so) and a
     * backend older than this app (404 on a route that is not there yet).
     */
    suspend fun addSharedPlace(name: String, point: LatLng): RoutePoint? {
        val api = sharedPlacesApi ?: return null
        val trimmed = name.trim()
        // The same bound the server puts on it, so a name that cannot be saved
        // is refused here rather than by a failed round trip.
        if (!RouteSetupRules.isStopNameValid(trimmed)) return null

        return try {
            val saved = api.add(trimmed, point)
            _uiState.update { it.copy(error = null) }
            RoutePoint(LatLng(saved.lat, saved.lng), saved.name)
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            null
        } catch (e: ApiException) {
            _uiState.update { it.copy(error = e.message) }
            null
        }
    }

    /**
     * Takes a place back off the shared list.
     *
     * The server allows whoever added it, or a super user — and the screen
     * only offers the cross to the first of those, so a refusal here means the
     * rules moved under somebody rather than that they tried something they
     * should not have.
     *
     * [onRemoved] runs only on success, so the row leaves the list because the
     * server agreed it should and not because a tap was registered.
     */
    fun removeSharedPlace(id: Long, onRemoved: () -> Unit = {}) {
        val api = sharedPlacesApi ?: return
        viewModelScope.launch {
            try {
                api.remove(id)
                _uiState.update { it.copy(error = null) }
                onRemoved()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Writes the difference between the trip's stops and the list the rider
     * confirmed.
     *
     * ## Why a failure here does not abort the rest
     *
     * A trip is shared, and the list a rider has been arranging can be minutes
     * old by the time they press confirm — a stop they crossed off may already
     * have been deleted by somebody else, and a stop they moved may be gone
     * too. Both come back 404. Treating that as an error would abandon the
     * remaining edits half way through and leave the route in a state matching
     * neither what the rider saw nor what the trip had.
     *
     * So a 404 on a stop is taken as "somebody got there first", which is the
     * truth, and the sync carries on. Anything else — a 403, a network
     * failure — is real and is reported, once, after the rest have been
     * attempted. `refresh()` then replaces the whole list with what the server
     * actually holds, so whatever happened, what the rider sees next is true.
     */
    private suspend fun applyWaypointEdits(edits: List<WaypointEdit>) {
        var failure: ApiException? = null

        for (edit in edits) {
            try {
                when (edit) {
                    is WaypointEdit.Remove -> tripApi.deleteWaypoint(tripId, edit.id)
                    is WaypointEdit.Move ->
                        tripApi.updateWaypoint(
                            tripId = tripId,
                            waypointId = edit.id,
                            name = edit.name,
                            orderIndex = edit.orderIndex,
                        )
                    is WaypointEdit.Add ->
                        tripApi.addWaypoint(
                            tripId = tripId,
                            name = edit.stop.label,
                            lat = edit.stop.point.lat,
                            lng = edit.stop.point.lng,
                            type = Waypoint.TYPE_PLANNED,
                            orderIndex = edit.orderIndex,
                        )
                }
            } catch (e: SessionExpiredException) {
                // Not survivable and not this loop's to soften: everything
                // after it would fail the same way.
                throw e
            } catch (e: ApiException) {
                // Somebody else removed it first. That is an outcome, not a
                // fault, and the refresh afterwards will show the truth.
                if (e.status == 404) continue
                if (failure == null) failure = e
            }
        }

        failure?.let { throw it }
    }

    /**
     * The road along the draft's ends, for the summary sheet.
     *
     * ## Why this is not simply another call to the directions API
     *
     * It is the same 5,000-a-day quota [loadRoute] is so careful with, and a
     * route card is a thing a rider opens and fiddles with. So the same rule
     * applies, keyed on the same pair of ends: one request per pair somebody
     * actually settles on, a failure that waits [RouteRequests.RETRY_AFTER_MS]
     * before being worth another go, and nothing at all on a poll.
     *
     * On top of that, one case that only exists here: a draft whose ends are
     * the trip's own ends is not fetched at all. The trip's route has already
     * been fetched for exactly that pair, and [TripMapUiState.draftRoute]
     * reads it. That is the common case — a rider opens the card, looks at
     * the route that is already set, and changes one end — so the common case
     * costs nothing.
     */
    private fun refreshRoutePreview() {
        val state = _uiState.value
        val draft = state.routeDraft
        val plan = RouteSetupRules.plan(draft)

        val alreadyTheTrips = plan != null &&
            RouteSetupRules.matchesTrip(
                draft,
                state.waypoints.all,
                state.trip?.origin,
                state.trip?.destination,
            )

        if (plan == null || alreadyTheTrips) {
            previewFor = null
            previewRetryAtMs = null
            if (state.routePreview != null || state.routePreviewLoading) {
                _uiState.update { it.copy(routePreview = null, routePreviewLoading = false) }
            }
            return
        }

        if (!RouteRequests.shouldFetch(plan, previewFor, previewRetryAtMs, now())) return

        // Null behaves exactly like a server with no key: no road figures, and
        // the sheet falls back to the straight-line measure it labels "direct".
        val api = routeApi ?: return

        previewFor = plan
        previewRetryAtMs = null
        _uiState.update { it.copy(routePreview = null, routePreviewLoading = true) }

        viewModelScope.launch {
            val line = try {
                api.route(plan.from, plan.to, plan.via)
            } catch (e: SessionExpiredException) {
                // The spinner is stopped as well as the session ended: the
                // sign-out takes the rider off this screen, but a state left
                // saying "still working it out" would greet them on the way
                // back in.
                _uiState.update { it.copy(routePreviewLoading = false) }
                onSessionExpired()
                return@launch
            } catch (e: ApiException) {
                previewRetryAtMs = now() + RouteRequests.RETRY_AFTER_MS
                _uiState.update { it.copy(routePreview = null, routePreviewLoading = false) }
                return@launch
            }
            _uiState.update {
                // Guarded: the rider may have moved an end or added a stop
                // while this was in flight, and a road drawn through points
                // they just replaced is the search-as-you-type bug wearing a
                // different hat.
                if (RouteSetupRules.plan(it.routeDraft) != plan) it
                else it.copy(routePreview = line, routePreviewLoading = false)
            }
        }
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
