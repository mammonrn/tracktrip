package app.ptrip.tracktrip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.LiveCadence
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.data.RouteLine
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.Bounds
import app.ptrip.tracktrip.map.CameraAction
import app.ptrip.tracktrip.map.CameraRules
import app.ptrip.tracktrip.map.CameraTarget
import app.ptrip.tracktrip.map.FOLLOW_ZOOM
import app.ptrip.tracktrip.map.MapCamera
import app.ptrip.tracktrip.map.ProgressBarLayout
import app.ptrip.tracktrip.map.DirectProgress
import app.ptrip.tracktrip.map.RouteGeometry
import app.ptrip.tracktrip.map.RoutePlan
import app.ptrip.tracktrip.map.RouteProgress
import app.ptrip.tracktrip.map.boundsAround
import app.ptrip.tracktrip.map.centre
import app.ptrip.tracktrip.map.EndpointMarker
import app.ptrip.tracktrip.map.FALLBACK_CENTRE
import app.ptrip.tracktrip.map.FALLBACK_ZOOM
import app.ptrip.tracktrip.map.FixAge
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.map.MapConfig
import app.ptrip.tracktrip.map.MarkerMotion
import app.ptrip.tracktrip.map.RiderMarker
import app.ptrip.tracktrip.map.SOLO_ZOOM
import app.ptrip.tracktrip.map.Speed
import app.ptrip.tracktrip.map.WaypointMarker
import app.ptrip.tracktrip.map.fitZoom
import app.ptrip.tracktrip.map.initialCamera
import app.ptrip.tracktrip.map.reportAgeMinutes
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppPrimarySoft
import app.ptrip.tracktrip.ui.theme.AppLine
import app.ptrip.tracktrip.ui.theme.AppRouteProgress
import app.ptrip.tracktrip.ui.theme.AppRouteProgressCasing
import app.ptrip.tracktrip.ui.theme.AppRouteProgressTrack
import app.ptrip.tracktrip.ui.theme.AppSurfaceAlt
import app.ptrip.tracktrip.ui.theme.AppSurface
import app.ptrip.tracktrip.ui.theme.AppBackground
import app.ptrip.tracktrip.data.PersonalPlace
import app.ptrip.tracktrip.data.Place
import app.ptrip.tracktrip.data.PlaceSearchProblem
import app.ptrip.tracktrip.data.PlaceSource
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.AppDanger
import app.ptrip.tracktrip.ui.theme.RankIcon
import app.ptrip.tracktrip.ui.theme.HudBackIcon
import app.ptrip.tracktrip.ui.theme.HudBatteryReadout
import app.ptrip.tracktrip.ui.theme.HudConfirmDialog
import app.ptrip.tracktrip.ui.theme.HudDivider
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudIconButton
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPinIcon
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudReadout
import app.ptrip.tracktrip.ui.theme.HudRouteIcon
import app.ptrip.tracktrip.ui.theme.HudSearchIcon
import app.ptrip.tracktrip.ui.theme.HudSecondaryButton
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.riderColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * A point the map should move to, and a sequence number so that asking for the
 * same point twice still moves it.
 *
 * Tapping "centre on me" after dragging the map away has to work the second
 * time as well as the first, and without the counter the state would be equal
 * to what it already was and nothing would happen.
 */
data class MapFocus(val lat: Double, val lng: Double, val sequence: Int)

/**
 * A point waiting for a rider to say what it is.
 *
 * The long press's own path, and now only that: a point picked while a route
 * field is waiting knows what it is for already, and goes straight into the
 * field. This is what is left — a rider pressing and holding the map with no
 * field waiting, who still has all three answers open to them.
 *
 * [name] is therefore always empty on the way in. It stays on the type because
 * it is what the dialog's field is seeded from, and a point pressed and held
 * has nothing to call itself yet.
 */
data class PendingPlacement(val point: LatLng, val name: String = "")

private const val FOCUS_ZOOM = 16.0

/** How many frames to wait for the map to be measured before giving up on fitting. */
private const val LAYOUT_WAIT_FRAMES = 120

/**
 * How often the rows' "x min ago" is recomputed. Finer than a minute so the
 * number never looks stuck, coarse enough to cost nothing.
 */
private const val AGE_TICK_MS = 20_000L

/**
 * Where everyone on the trip is.
 *
 * Map on top, every member listed underneath — not a map you have to tap pin
 * by pin to read. The two halves are wired together: tapping a rider in the
 * list moves the map to them, and tapping their pin highlights their row.
 *
 * [myLocation] is this phone's own position, used to open the camera
 * somewhere useful before anyone on the trip has reported. [mySpeedKmh] is
 * this rider's own speed, read live from the device rather than from the
 * server — a poll is a cadence behind by definition, and a speedometer that
 * lags by a cadence is not a speedometer.
 */
@Composable
fun TripMapScreen(
    state: TripMapUiState,
    currentUserId: Long?,
    centreOn: MapFocus?,
    myLocation: LatLng?,
    mySpeedKmh: Int?,
    onRefresh: () -> Unit,
    onCenterOnMe: () -> Unit,
    onPlace: (MapPlacement, LatLng, String) -> Unit,
    onRemoveWaypoint: (Long) -> Unit,
    /**
     * What the place search is showing, and the two things a rider does to it.
     *
     * An *addition* to pressing and holding the map, never a replacement:
     * dropping a stop at the viewpoint you are standing at has no name to
     * type, and a rider planning a route from the sofa has no idea where on
     * the map "Mae Hong Son" is. Both gestures end in the same place.
     */
    searchState: PlaceSearchState = PlaceSearchState(),
    onSearchQueryChanged: (String) -> Unit = {},
    onSearchCleared: () -> Unit = {},
    /**
     * Setting a route up: opening the card, filling a field, and committing.
     *
     * Five callbacks rather than one because they are five different moments,
     * and only the last of them touches the server. Defaulted so the previews
     * and the tests that do not exercise routing need none of them.
     */
    onOpenRouteSetup: () -> Unit = {},
    onCloseRouteSetup: () -> Unit = {},
    onPickRoutePoint: (RouteField, RoutePoint) -> Unit = { _, _ -> },
    onRemoveRouteStop: (Int) -> Unit = {},
    /**
     * The cross on a row of the route list, and a row dragged to a new place
     * in it.
     *
     * By *row* rather than by stop, because on the list the two ends and the
     * stops are one sequence — which is the whole of what changed here. A drag
     * that crosses the top of the list makes a stop the start; the row index
     * is what says so, and [RouteSetupRules] is what works out which halves of
     * the draft that means.
     */
    onRemoveRouteRow: (Int) -> Unit = {},
    onMoveRoutePoint: (Int, Int) -> Unit = { _, _ -> },
    onConfirmRoute: () -> Unit = {},
    /**
     * Writing a place to the shared list, and taking one off it.
     *
     * [onAddSharedPlace] answers with the saved point so the row the rider was
     * filling when they gave up searching gets filled too — see
     * TripMapViewModel.addSharedPlace for why the two happen together. Null
     * back means it did not save, and the reason is already on screen.
     */
    onAddSharedPlace: suspend (String, LatLng) -> RoutePoint? = { _, _ -> null },
    onRemoveSharedPlace: (Long, () -> Unit) -> Unit = { _, _ -> },
    /**
     * Saving a place to this rider's own list, and the list itself.
     *
     * Separate from the shared pair above and never routed through them: the
     * whole property this feature has to hold is that a private row and a
     * shared row are handled by different code from end to end.
     */
    onAddPersonalPlace: suspend (String, String, LatLng) -> RoutePoint? = { _, _, _ -> null },
    personalPlaces: List<PersonalPlace> = emptyList(),
    /**
     * Whether this app may read the phone's position at all.
     *
     * Passed in rather than checked here so the screen stays testable and so
     * the answer is re-read on the same beat as everything else location —
     * a permission granted from the Android settings while this screen is
     * open has to take effect without it being reopened. Only used to word
     * the "use my current location" row: [myLocation] being null is what
     * actually disables it.
     */
    hasLocationPermission: Boolean = false,
    /**
     * When this phone last had a report accepted, or null when it is not
     * sharing on this trip.
     *
     * On screen because its absence cost a ride: with the pins unchanging,
     * there was no way to tell "my phone has stopped reporting" from "my phone
     * is reporting and the map is not drawing it", and those need completely
     * different fixes.
     */
    lastReportedAtMillis: Long? = null,
    /**
     * Whether this phone is broadcasting on *this* trip.
     *
     * Decides who the camera belongs to when the screen opens: a rider who is
     * sharing is the one going somewhere, so the map follows them the way a
     * navigation app does. Anybody else — watching a ride they are not on —
     * gets the framing this map has always used and keeps it.
     */
    isSharingThisTrip: Boolean = false,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf<Long?>(null) }

    // A point pressed and held on the map, waiting for the rider to say what
    // it is. Held here rather than in the view model: nothing has happened
    // yet, and a half-finished gesture is not state the server needs to know
    // about.
    var placing by remember { mutableStateOf<PendingPlacement?>(null) }

    // Whether the route card is open. A card rather than something that is
    // always there: it covers the top of the route-progress bar while it is
    // open, and a rider who is riding rather than planning is not typing.
    var routeSetupOpen by rememberSaveable { mutableStateOf(false) }

    // Which field the picker that is open is filling, or null when none is.
    //
    // The whole of the new flow is in this one value. It is set by tapping a
    // field, and it is what a search result, "use my current location" and a
    // long press all land in — so none of the three has to ask what the point
    // is for, because the rider already said.
    var picking by rememberSaveable { mutableStateOf<RouteField?>(null) }

    // A stop chosen with no name on it, waiting for one. The server refuses an
    // unnamed waypoint, so this is the one thing a picker still has to ask.
    var namingStop by remember { mutableStateOf<RoutePoint?>(null) }

    // A place the rider is adding to the shared list: the name they typed into
    // the search, waiting for them to point at where it is.
    //
    // Non-null means the search screen steps aside and the map is armed. It is
    // held rather than being a mode on the picker because the two halves of
    // "add a place" arrive from opposite directions — the name by keyboard,
    // the point by finger — and nothing is saved until both are in.
    var droppingPlace by rememberSaveable { mutableStateOf<String?>(null) }

    // The dropped point, waiting for the name to be confirmed before it is
    // written to the shared list.
    var namingPlace by remember { mutableStateOf<PendingPlacement?>(null) }

    // A shared place the rider has asked to remove. Confirmed first: it is
    // shared, so it disappears from everybody's search and there is no undo.
    var removingPlace by remember { mutableStateOf<Place?>(null) }

    // Whether a save is in flight, and whether the last one failed. Both are
    // about the dialog rather than about the trip, which is why they are here
    // and not in the view model — nothing has been written yet.
    var savingPlace by remember { mutableStateOf(false) }
    var failedToSavePlace by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    var removing by remember { mutableStateOf<Waypoint?>(null) }

    // Who is driving the camera. See CameraRules — the rule that matters is
    // that a drag always wins, and nothing takes the map back on its own.
    var camera by remember(isSharingThisTrip) {
        mutableStateOf(CameraRules.initial(isSharingThisTrip))
    }
    var overview by remember { mutableStateOf<MapOverview?>(null) }
    var overviewSequence by remember { mutableIntStateOf(0) }

    // What "the whole journey" means: this rider, and the two ends of the trip
    // if they have been set. Not the other riders — the overview answers "how
    // far have I got", and a friend who set off from home three provinces away
    // would blow the frame out to nothing useful.
    //
    // The drawn ends rather than the trip's, so that a rider who has just
    // picked a start two provinces away can frame what they are planning as
    // well as what is saved.
    val overviewPoints = listOfNotNull(
        myLocation,
        state.drawnOrigin?.let { LatLng(it.lat, it.lng) },
        state.drawnDestination?.let { LatLng(it.lat, it.lng) },
    )
    var focus by remember { mutableStateOf<MapFocus?>(null) }
    var sequence by remember { mutableIntStateOf(0) }

    // Centring from outside — the "centre on me" button, which had to go and
    // ask the phone where it is first.
    LaunchedEffect(centreOn) {
        val target = centreOn ?: return@LaunchedEffect
        focused = null
        sequence += 1
        focus = MapFocus(target.lat, target.lng, sequence)
    }

    // Filling the card in from the trip, whenever it is open with nothing in
    // it yet.
    //
    // An effect rather than a line in the button's handler, because there are
    // two ways to arrive at an open card with an empty draft and only one of
    // them is a press: the open flag is saved across the process being killed
    // and the draft is not, so a rider coming back to a re-created screen
    // would otherwise find two blank fields over a trip whose route is set.
    // It also covers a card opened while the trip was still loading.
    LaunchedEffect(routeSetupOpen, state.trip?.id) {
        if (routeSetupOpen && state.routeDraft.isEmpty) onOpenRouteSetup()
    }

    // Centring from a tap, on a pin or on a row.
    LaunchedEffect(focused, state.members) {
        val target = state.members.firstOrNull { it.userId == focused } ?: return@LaunchedEffect
        val lat = target.lat ?: return@LaunchedEffect
        val lng = target.lng ?: return@LaunchedEffect
        sequence += 1
        focus = MapFocus(lat, lng, sequence)
    }

    // Polling lives here rather than in the view model so it stops when the
    // map is no longer on screen.
    //
    // It never stops while the map *is* on screen, even with the live feed
    // connected. The socket carries what happens while it is listening; it
    // says nothing about the second it spent reconnecting, and nothing about a
    // member joining, leaving, or stopping sharing. This is the pass that
    // makes those right — slow while the socket is doing the work, back to its
    // usual rate the moment it is not. See LiveCadence.
    LaunchedEffect(state.live) {
        while (true) {
            onRefresh()
            delay(LiveCadence.pollIntervalMs(state.live))
        }
    }

    // How tall the floating header actually is, in dp, or null before it has
    // been measured.
    //
    // Measured rather than assumed, because assuming it is what put the
    // progress bar underneath it. See ProgressBarLayout: the header is a
    // title, a subtitle, an optional error row and a speed readout, and every
    // one of those grows with a long trip name, a wrapped subtitle, an error,
    // or the system font scale.
    var headerHeightDp by remember { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current

    // The wall clock, re-read on its own beat. The ages on the rows have to
    // keep counting up between polls — otherwise each one would sit still
    // until the next fetch and then jump.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(AGE_TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }

    val canSetUpRoute = RouteSetupRules.canSetUpRoute(
        isOwner = state.trip?.isOwner == true,
        isTripActive = state.trip?.isActive == true,
    )

    // Whether a press and hold on the map means "drop a stop here" outright.
    //
    // The summary sheet is up, so the two ends are already set and the only
    // point left to add is a stop — which is what the gesture almost always
    // means at that moment. Without this a rider had to open the search panel
    // they were not going to use, *then* press and hold: two screens and four
    // taps for a spot their finger was already on.
    val longPressDropsStop = routeSetupOpen &&
        canSetUpRoute &&
        picking == null &&
        state.routeDraft.isComplete &&
        RouteSetupRules.canAddStops(state.trip?.isActive == true)

    // Everything a picked point does, wherever it was picked from: move the
    // map to it so the rider can see what they chose, put it in the field that
    // asked for it, and close the picker.
    //
    // One lambda rather than three copies, because the whole point of the new
    // flow is that the three ways in — a search result, "use my current
    // location", a long press — end identically. A stop is the one that still
    // has a question left, and only when it arrived without a name: the server
    // refuses an unnamed waypoint.
    val takePicked: (RouteField, RoutePoint) -> Unit = { field, chosen ->
        sequence += 1
        camera = MapCamera.FREE
        focused = null
        focus = MapFocus(chosen.point.lat, chosen.point.lng, sequence)
        if (field == RouteField.STOP && !RouteSetupRules.isStopNameValid(chosen.label)) {
            namingStop = chosen
        } else {
            onPickRoutePoint(field, chosen)
        }
        picking = null
        onSearchCleared()
    }

    // Back steps out of what is open rather than off the map.
    //
    // A rider two taps deep into a picker pressing back means "not that one",
    // not "leave the ride I am watching" — and a route card that can only be
    // closed by finding the button that opened it is a trap on a phone whose
    // whole navigation is one gesture. Disabled when nothing is open, so the
    // back stack behaves exactly as it did.
    BackHandler(enabled = routeSetupOpen || droppingPlace != null) {
        if (droppingPlace != null) {
            // Out of the armed map and back to the search that armed it, with
            // what was typed still in the box. Pressing back here means "not
            // like that", not "forget the name I just typed".
            droppingPlace = null
        } else if (picking != null) {
            picking = null
        } else {
            routeSetupOpen = false
            onCloseRouteSetup()
        }
        onSearchCleared()
    }

    // The top bar is *over* the map, not above it. As a row in the same column
    // it belonged to the scrolling layout and slid away the moment the map was
    // dragged; a rider then had no trip name and, worse, no way back.
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // BoxWithConstraints, not Box: the progress bar needs to know how
            // much map there is before it can decide whether it fits under the
            // header at all. See ProgressBarLayout.
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val mapHeightDp = maxHeight.value

                RiderMap(
                    members = state.placed,
                    waypoints = state.drawnWaypoints,
                    origin = state.drawnOrigin,
                    destination = state.drawnDestination,
                    route = state.drawnRoute,
                    myLocation = myLocation,
                    focus = focus,
                    onMarkerTap = {
                        // Looking at somebody else is an explicit request, and
                        // it wins over following: without this the camera
                        // would snap back to this rider on their next fix, a
                        // second or two after the tap.
                        camera = MapCamera.FREE
                        focused = it
                    },
                    onLongPress = { point ->
                        val field = picking
                        when {
                            // Armed from the search screen: the rider has
                            // already said what this place is called and is
                            // now saying where. Ahead of everything else,
                            // because it is the gesture they were just asked
                            // for and the banner over the map says so.
                            droppingPlace != null ->
                                namingPlace = PendingPlacement(point, droppingPlace.orEmpty())
                            // Pressing and holding while a field is waiting for
                            // a point fills that field. The third way in, and
                            // the only one that works with the server's search
                            // key unset — a rider pointing at a spot on the map
                            // has said where, and the field they tapped to open
                            // the picker already said what for.
                            field != null -> takePicked(field, RoutePoint(point))
                            // Route set, sheet up: the gesture is a stop, and
                            // it goes straight to the one question a stop
                            // still has to answer.
                            longPressDropsStop -> takePicked(RouteField.STOP, RoutePoint(point))
                            // With no field waiting and no route being set up,
                            // the gesture means what it has always meant.
                            // Nothing to offer means nothing to open: a member
                            // looking at a finished ride can place neither end
                            // nor a stop, and a dialog with no buttons is
                            // worse than no dialog.
                            placementsAllowed(state.trip).isNotEmpty() ->
                                placing = PendingPlacement(point)
                        }
                    },
                    onWaypointTap = { waypoint ->
                        val draftIndex = RouteSetupRules.draftIndex(waypoint.id)
                        val draftStop = draftIndex?.let { state.routeDraft.stops.getOrNull(it) }
                        if (draftIndex != null) {
                            // A stop on the open route list. It comes off the
                            // list rather than off the trip — the server hears
                            // about it when the rider confirms, like every
                            // other edit on this card — so there is no
                            // confirmation to ask for and nothing to undo.
                            //
                            // Gated all the same: a saved stop follows the
                            // waypoints route's rule, so a member tapping
                            // somebody else's pin would be queueing a delete
                            // that comes back 403.
                            val mayRemove = draftStop != null &&
                                RouteSetupRules.canAddStops(state.trip?.isActive == true) &&
                                RouteSetupRules.canRemoveStop(
                                    stop = draftStop,
                                    isOwner = state.trip?.isOwner == true,
                                    currentUserId = currentUserId,
                                )
                            if (mayRemove) onRemoveRouteStop(draftIndex)
                        } else {
                            val allowed = MapPlacementRules.canRemoveWaypoint(
                                isOwner = state.trip?.isOwner == true,
                                addedBy = waypoint.addedBy,
                                currentUserId = currentUserId,
                            )
                            if (allowed && state.trip?.isActive == true) removing = waypoint
                        }
                    },
                    follow = myLocation.takeIf { CameraRules.followsPosition(camera) },
                    overview = overview,
                    onUserPan = { camera = CameraRules.afterPan() },
                )

                // How far there is left to go, down the right-hand edge.
                // Drawn only when there is something to measure — a trip with
                // a destination, and a rider with a position to measure from.
                //
                // Two measures, and the label says which one is on screen.
                // With a road route fetched, the rider is projected onto it
                // and what is left is the road ahead of them; without one —
                // no key on the server, no road between the points, a rider
                // nowhere near the line — it is the straight-line measure the
                // app has always shown, captioned "direct" exactly as before.
                val routePoints = state.route?.points.orEmpty()
                val alongRoute = if (myLocation != null && routePoints.size >= 2) {
                    RouteGeometry.progress(routePoints, myLocation)
                } else {
                    null
                }

                // Only drawn when there is room for it under a header whose
                // real height is measured rather than assumed — the old fixed
                // 76dp inset is what put the label plate underneath the
                // header on a long trip name, a wrapped subtitle, an error
                // row, or a raised font scale.
                // The fallback, when there is no road route to project onto.
                // Measured along the trip's own legs — start, stops, finish —
                // so a ride planned round a viewpoint is measured as that ride
                // rather than as the gap to the far end of it. On a trip with
                // no stops this is the gap-closing measure, unchanged.
                val direct = DirectProgress.of(state.tripPlan, myLocation)

                if (ProgressBarLayout.fits(mapHeightDp, headerHeightDp)) {
                    RouteProgressBar(
                        fraction = alongRoute?.fraction ?: direct?.fraction,
                        remaining = RouteProgress.format(
                            alongRoute?.remainingKm ?: direct?.remainingKm
                        ),
                        byRoad = alongRoute != null,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(
                                end = 12.dp,
                                top = ProgressBarLayout.topInsetDp(headerHeightDp).dp,
                                bottom = ProgressBarLayout.BOTTOM_INSET_DP.dp,
                            ),
                    )
                }

                // Bottom *left*, not bottom right, since the progress bar was
                // made long enough to be read: the two used to share the
                // right-hand corner, and the buttons painted over the bottom
                // 118dp of the bar — the part nearest the start of the ride.
                // The right edge is the gauge now; the left holds the
                // controls.
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // One button for the two ends of one question: am I
                    // looking at the journey, or at myself? The answer is
                    // always whichever one is not on screen, so the button
                    // alternates and its icon shows where the next press
                    // goes rather than where the last one went.
                    //
                    // See CameraRules.nextAction — the icon, the words and
                    // the press all read the same rule, so they cannot
                    // disagree about what the button is for.
                    val nextCamera = CameraRules.nextAction(
                        camera = camera,
                        canFitRoute = overviewPoints.size >= 2,
                    )
                    HudIconButton(
                        onClick = {
                            when (nextCamera) {
                                CameraAction.FIT_ROUTE -> {
                                    overviewSequence += 1
                                    boundsAround(overviewPoints)?.let {
                                        camera = MapCamera.OVERVIEW
                                        overview = MapOverview(it, overviewSequence)
                                    }
                                }
                                // For a rider who is sharing, this means
                                // "follow me again" — which the follow effect
                                // does on its own the moment the camera says
                                // so, at the zoom following uses. Calling
                                // onCenterOnMe as well would animate to a
                                // different zoom first and fight it.
                                //
                                // For everyone else, and for a rider whose
                                // phone has not produced a position yet, it
                                // means the thing it has always meant: go and
                                // find me.
                                CameraAction.FOLLOW_ME -> {
                                    if (isSharingThisTrip && myLocation != null) {
                                        camera = MapCamera.FOLLOW
                                    } else {
                                        onCenterOnMe()
                                    }
                                }
                            }
                        },
                        contentDescription = stringResource(
                            when (nextCamera) {
                                CameraAction.FIT_ROUTE -> R.string.map_overview
                                CameraAction.FOLLOW_ME -> R.string.map_center_on_me
                            }
                        ),
                        icon = {
                            when (nextCamera) {
                                CameraAction.FIT_ROUTE -> HudRouteIcon(tint = AppPrimary)
                                CameraAction.FOLLOW_ME -> HudPinIcon(
                                    tint = if (camera == MapCamera.FOLLOW) AppPrimary
                                           else AppTextMuted
                                )
                            }
                        },
                        modifier = Modifier
                            .background(AppSurface.copy(alpha = 0.92f), CircleShape),
                    )
                }
            }

            HudDivider()

            if (state.loading && state.members.isEmpty()) {
                HudLoading()
            } else {
                LazyColumn(
                    // Wraps its rows, capped — never a fixed share of the
                    // screen. A weight() here reserved the same slab of height
                    // for a trip of one as for a trip of ten, and the surplus
                    // read as a grey hole under the single row.
                    modifier = Modifier
                        .heightIn(max = MEMBER_LIST_MAX_HEIGHT)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                ) {
                    // The long press is the only gesture on this screen with
                    // nothing on the screen to suggest it, so the list says
                    // so — and only to riders who can actually place
                    // something, since to anyone else it would be an
                    // instruction that does nothing.
                    if (placementsAllowed(state.trip).isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.map_place_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextMuted,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }

                    if (state.orderedByProgress) {
                        item {
                            // The order is a guess from a heading, not a road
                            // distance (see RideOrder) — the row says as much
                            // rather than letting the list imply certainty.
                            Text(
                                text = stringResource(R.string.map_order_leader_first),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextMuted,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                    items(state.members, key = { it.userId }) { member ->
                        MemberMapRow(
                            member = member,
                            levelName = state.levels[member.userId]?.levelName,
                            fixAgeMinutes = FixAge.minutesAgo(member.recordedAt, nowMs),
                            isSelf = member.userId == currentUserId,
                            focused = member.userId == focused,
                            onClick = {
                                if (member.hasPosition) {
                                    camera = MapCamera.FREE
                                    focused = member.userId
                                }
                            },
                        )
                    }
                }
            }
        }

        MapOverlayBar(
            // Measured, and the progress bar down the right-hand edge starts
            // below whatever this actually came out as. Nothing else is
            // measured with it any more: the route list is a sheet at the
            // bottom, and the picker is a screen of its own.
            modifier = Modifier
                .align(Alignment.TopStart)
                .onSizeChanged { size ->
                    headerHeightDp = with(density) { size.height.toDp() }.value
                },
            title = state.trip?.name ?: stringResource(R.string.map_title),
            subtitle = stringResource(
                R.string.map_riders_placed,
                state.placed.size,
                state.members.size,
            ),
            speedKmh = mySpeedKmh,
            reportAgeMinutes = FixAge.reportAgeMinutes(lastReportedAtMillis, nowMs),
            error = state.error,
            onBack = onBack,
            // Offered only to a rider who has somewhere to put what they find.
            // On a finished trip a member can set neither end nor add a stop,
            // and a route list that saves nothing is worse than no list.
            canSetUpRoute = canSetUpRoute,
            routeSetupOpen = routeSetupOpen,
            onToggleRouteSetup = {
                routeSetupOpen = !routeSetupOpen
                picking = null
                onSearchCleared()
                // Only the closing half here — the opening half is the effect
                // above, which has one more case to cover.
                if (!routeSetupOpen) onCloseRouteSetup()
            },
        )

        // The route itself, at the bottom, over the map: both ends, every stop,
        // and the row that adds another — the one place any of it is edited.
        //
        // Open from the moment the card is, not only once both ends are set.
        // The list *is* the way in now: an empty "From" row is how a rider
        // fills the start, so a sheet that waited for a complete route would
        // be a sheet nobody could ever open.
        //
        // Hidden while the picker is up, which is a full screen over this one
        // anyway — the flag keeps the sheet from measuring itself behind a
        // keyboard it cannot be read through.
        if (routeSetupOpen && canSetUpRoute && picking == null) {
            RouteListSheet(
                draft = state.routeDraft,
                plan = state.summaryPlan,
                route = state.draftRoute,
                loading = state.routePreviewLoading,
                canEditEnds = RouteSetupRules.canEditEnds(state.trip?.isOwner == true),
                canAddStops = RouteSetupRules.canAddStops(state.trip?.isActive == true),
                currentUserId = currentUserId,
                onPick = { tapped ->
                    picking = tapped
                    onSearchCleared()
                },
                onRemoveRow = onRemoveRouteRow,
                onMoveRow = onMoveRoutePoint,
                onConfirm = {
                    // The draft is read and cleared inside confirmRoute before
                    // it returns, so closing the card here cannot race it.
                    onConfirmRoute()
                    routeSetupOpen = false
                    picking = null
                    onSearchCleared()
                },
                onDismiss = {
                    routeSetupOpen = false
                    picking = null
                    onSearchCleared()
                    onCloseRouteSetup()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // The picker, over the whole screen and over everything in this box.
        //
        // Last in the box on purpose: it is a screen, not a panel, and
        // anything drawn after it would be drawn on top of a search a rider is
        // typing into.
        // Hidden while the map is armed: the rider is being asked to point at
        // something, and a full-screen search over the map would be covering
        // the thing they have to point at. `picking` stays set throughout, so
        // the place lands in the row they were filling when they gave up.
        picking?.takeIf { routeSetupOpen && canSetUpRoute && droppingPlace == null }?.let { field ->
            PlaceSearchScreen(
                state = searchState,
                heading = stringResource(field.pickHeadingRes),
                onQueryChanged = onSearchQueryChanged,
                onClear = onSearchCleared,
                onBack = {
                    picking = null
                    onSearchCleared()
                },
                onPick = { place ->
                    takePicked(field, RoutePoint(LatLng(place.lat, place.lng), place.name))
                },
                myLocation = myLocation,
                hasLocationPermission = hasLocationPermission,
                // No name is prefilled. "My location" as a label would be a
                // name a rider has to delete before typing the one they meant,
                // and it means nothing on the map an hour later. The two ends
                // take no label at all; a stop still asks for one.
                onUseCurrentLocation = { here -> takePicked(field, RoutePoint(here)) },
                currentUserId = currentUserId,
                onAddPlace = { typed -> droppingPlace = typed.trim() },
                onRemoveSharedPlace = { place -> removingPlace = place },
                personalPlaces = personalPlaces,
                onPickPersonal = { saved ->
                    // Straight into the row the rider came here to fill. No
                    // request: the coordinate arrived with the chip.
                    takePicked(field, RoutePoint(saved.point, saved.name))
                },
            )
        }

        // The map is armed and the rider has to be told what for. Over the
        // map at the top, where the search screen they just left was — the
        // instruction has to be somewhere they are already looking, and the
        // gesture it asks for has nothing on the map to suggest it.
        droppingPlace?.let { name ->
            DropPlaceBanner(
                name = name,
                onCancel = { droppingPlace = null },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    placing?.let { pending ->
        PlacePointDialog(
            point = pending.point,
            initialName = pending.name,
            options = placementsAllowed(state.trip),
            onPlace = { placement, name ->
                onPlace(placement, pending.point, name)
                placing = null
                onSearchCleared()
            },
            onDismiss = { placing = null },
        )
    }

    namingStop?.let { stop ->
        StopNameDialog(
            point = stop.point,
            suggestedName = stringResource(
                R.string.map_route_stop_default_name,
                RouteSetupRules.nextStopNumber(state.routeDraft),
            ),
            onName = { name ->
                onPickRoutePoint(RouteField.STOP, stop.copy(label = name.trim()))
                namingStop = null
            },
            onDismiss = { namingStop = null },
        )
    }

    namingPlace?.let { pending ->
        SavePlaceDialog(
            point = pending.point,
            suggestedName = pending.name,
            canSavePersonal = true,
            saving = savingPlace,
            // Whatever the last failed save said. The dialog is the only place
            // this can be read: the line above the map, where every other
            // failure on this screen goes, is behind a full-screen picker by
            // the time a rider gets back to it.
            error = state.error.takeIf { !savingPlace && failedToSavePlace },
            onSave = { visibility, name, label ->
                val point = pending.point
                val field = picking
                savingPlace = true
                scope.launch {
                    // Saved first, and only used if the save worked. A place
                    // dropped into the route on the strength of a request that
                    // failed would be a row nobody else can ever find.
                    val saved = when (visibility) {
                        PlaceVisibility.SHARED -> onAddSharedPlace(name, point)
                        PlaceVisibility.PERSONAL -> onAddPersonalPlace(label, name, point)
                    }
                    savingPlace = false
                    if (saved == null) {
                        // The dialog stays up with the reason on it. The two
                        // worth expecting are the daily allowance and a
                        // backend older than this app, and neither is fixed by
                        // making the rider find their way back here.
                        failedToSavePlace = true
                        return@launch
                    }
                    failedToSavePlace = false
                    namingPlace = null
                    droppingPlace = null
                    if (field != null) {
                        takePicked(field, saved)
                    } else {
                        picking = null
                        onSearchCleared()
                    }
                }
            },
            onDismiss = {
                // Back to the armed map rather than out of the flow: the point
                // was wrong, which is a reason to point again.
                namingPlace = null
                failedToSavePlace = false
            },
        )
    }

    removingPlace?.let { place ->
        HudConfirmDialog(
            title = stringResource(R.string.map_shared_place_remove_title),
            message = stringResource(R.string.map_shared_place_remove_message, place.name),
            confirmText = stringResource(R.string.map_remove_point),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                val id = place.sharedId
                removingPlace = null
                // Re-runs the search so the row leaves the list, and leaves it
                // only because the server agreed rather than because a tap was
                // registered.
                if (id != null) onRemoveSharedPlace(id) { onSearchQueryChanged(searchState.query) }
            },
            onDismiss = { removingPlace = null },
        )
    }

    removing?.let { waypoint ->
        HudConfirmDialog(
            title = stringResource(R.string.map_remove_point_title),
            message = stringResource(R.string.map_remove_point_message, waypoint.name),
            confirmText = stringResource(R.string.map_remove_point),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                onRemoveWaypoint(waypoint.id)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * The map, armed, and what it is waiting for.
 *
 * ## Why the name is on it
 *
 * A rider arrives here having typed "ปตท สวนดอก" into a search that found
 * nothing and tapped a row offering to add it. Between that tap and the long
 * press there is a whole map to look at, and a banner that only said "press
 * and hold" would leave them wondering which of the two things they were
 * doing. It says the name back.
 *
 * The way out is on it too. The gesture it asks for is one the rider might not
 * want to make after all, and an armed mode whose only exit is the system back
 * gesture is a mode people get stuck in.
 */
@Composable
internal fun DropPlaceBanner(name: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(AppSurface.copy(alpha = 0.96f), AppSearchPanelShape)
            .border(1.dp, AppLine, AppSearchPanelShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = name.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.map_shared_place_drop, it) }
                ?: stringResource(R.string.map_shared_place_drop_unnamed),
            style = MaterialTheme.typography.bodySmall,
            color = AppText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.cancel),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = AppPrimary,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

/**
 * Where a dropped pin is about to be saved.
 *
 * Two lists, and the difference between them is not a setting — it is whether
 * the whole server can see where somebody lives. So it is a choice the rider
 * makes explicitly, in words, every time, rather than a toggle that remembers
 * what they picked last and quietly applies it to their home address.
 */
enum class PlaceVisibility {
    /** `shared_places` — every rider on this server finds it. */
    SHARED,

    /** `personal_places` — a private shortcut, and nobody else's business. */
    PERSONAL,
}

/**
 * The name a place goes under, and which of the two lists it joins.
 *
 * ## Why one dialog and not two entry points
 *
 * A rider who has pressed and held a spot on the map has already said the hard
 * part: *where*. Sending them back to choose which kind of place they meant
 * before they can name it would be asking them to classify something they have
 * not described yet. So the pin is dropped, the name is typed, and the last
 * question is who gets to see it — which is the order the decision actually
 * happens in.
 *
 * The two options are spelled out rather than iconified. "Only me" and
 * "Everyone on this server" are the whole of what a rider needs to understand,
 * and a padlock glyph is not: this is the one screen in the app where getting
 * the wrong answer publishes an address.
 *
 * A personal place also takes a [PersonalPlace.label] — what the shortcut chip
 * says. Prefilled from the name and offered as two chips, because the honest
 * answer is "บ้าน" or "ที่ทำงาน" almost every time.
 */
@Composable
internal fun SavePlaceDialog(
    point: LatLng,
    suggestedName: String,
    /** Whether the private half is offered at all. See [PlacesAccess]. */
    canSavePersonal: Boolean,
    /** Whether a save is in flight, so the button cannot be pressed twice. */
    saving: Boolean,
    /** Why the last save failed, or null. Shown here because nowhere else can. */
    error: String?,
    onSave: (PlaceVisibility, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(point) { mutableStateOf(suggestedName) }
    var label by rememberSaveable(point) { mutableStateOf("") }
    // Shared is the default because it is the ordinary case — the feature
    // exists because OpenStreetMap is missing petrol stations, not because
    // people want private bookmarks. Nothing is remembered between dialogs.
    var visibility by rememberSaveable(point) { mutableStateOf(PlaceVisibility.SHARED) }

    val homeLabel = stringResource(R.string.map_place_shortcut_home)
    val workLabel = stringResource(R.string.map_place_shortcut_work)
    val personal = visibility == PlaceVisibility.PERSONAL
    val effectiveLabel = label.trim().takeIf { it.isNotEmpty() } ?: name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.map_shared_place_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(
                        R.string.map_place_coordinates,
                        formatCoordinate(point.lat),
                        formatCoordinate(point.lng),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextMuted,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { typed ->
                        if (typed.length <= MapPlacementRules.NAME_MAX_LENGTH) name = typed
                    },
                    label = { Text(stringResource(R.string.map_place_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )

                if (canSavePersonal) {
                    Text(
                        text = stringResource(R.string.map_place_visibility),
                        style = MaterialTheme.typography.labelMedium,
                        color = AppText,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    VisibilityChoice(
                        selected = visibility,
                        option = PlaceVisibility.SHARED,
                        title = stringResource(R.string.map_place_shared_option),
                        detail = stringResource(R.string.map_place_shared_hint),
                        onSelect = { visibility = it },
                    )
                    VisibilityChoice(
                        selected = visibility,
                        option = PlaceVisibility.PERSONAL,
                        title = stringResource(R.string.map_place_personal_option),
                        detail = stringResource(R.string.map_place_personal_hint),
                        onSelect = { visibility = it },
                    )
                }

                if (personal) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { typed ->
                            if (typed.length <= SHORTCUT_LABEL_MAX_LENGTH) label = typed
                        },
                        label = { Text(stringResource(R.string.map_place_shortcut_label)) },
                        placeholder = { Text(name) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    // The honest answer almost every time, one tap away.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        listOf(homeLabel, workLabel).forEach { suggestion ->
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelLarge,
                                color = AppPrimary,
                                modifier = Modifier
                                    .background(AppPrimarySoft, AppChipShape)
                                    .clickable { label = suggestion }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.map_shared_place_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                error?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppDanger,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            HudPrimaryButton(
                text = stringResource(R.string.map_shared_place_save),
                onClick = { onSave(visibility, name.trim(), effectiveLabel) },
                // The same bounds the server puts on these, checked here so a
                // rider learns while they are still typing rather than by
                // having the save come back 400. Off while a save is in
                // flight, so a second press cannot write the place twice.
                enabled = RouteSetupRules.isStopNameValid(name) &&
                    !saving &&
                    (!personal || effectiveLabel.isNotEmpty()),
            )
        },
        dismissButton = {
            HudSecondaryButton(text = stringResource(R.string.cancel), onClick = onDismiss)
        },
        containerColor = AppSurface,
        titleContentColor = AppText,
        textContentColor = AppTextMuted,
    )
}

/** One of the two answers to "who can find this?", with what it means under it. */
@Composable
private fun VisibilityChoice(
    selected: PlaceVisibility,
    option: PlaceVisibility,
    title: String,
    detail: String,
    onSelect: (PlaceVisibility) -> Unit,
) {
    val chosen = selected == option
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(if (chosen) AppPrimarySoft else AppSurface, AppSearchPanelShape)
            .border(1.dp, if (chosen) AppPrimary else AppLine, AppSearchPanelShape)
            .clickable { onSelect(option) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (chosen) AppPrimary else AppText,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted,
        )
    }
}

/** As long a shortcut name as the server will take. See src/places/personal.js. */
private const val SHORTCUT_LABEL_MAX_LENGTH = 24

/** The heading over the picker: which field it is filling. */
internal val RouteField.pickHeadingRes: Int
    get() = when (this) {
        RouteField.FROM -> R.string.map_route_pick_from
        RouteField.TO -> R.string.map_route_pick_to
        RouteField.STOP -> R.string.map_route_pick_stop
    }

/** What to call a chosen point: its own name, or where it is. */
@Composable
private fun routePointLabel(picked: RoutePoint): String =
    picked.label.trim().takeIf { it.isNotEmpty() } ?: stringResource(
        R.string.map_place_coordinates,
        formatCoordinate(picked.point.lat),
        formatCoordinate(picked.point.lng),
    )

/**
 * The route as one list: both ends, every stop, in riding order.
 *
 * ## Why this replaced the summary sheet rather than joining it
 *
 * What was here read the route out — two ends on a card at the top, a numbered
 * list of stops in a sheet at the bottom, a distance between them — and let a
 * rider do exactly two things to it: append a stop, or take one off. The order
 * stops went on in was the order they were thought of in, and there was no way
 * to say "that one first". A route planned in the wrong order had to be
 * deleted stop by stop and typed again.
 *
 * So the two halves became one list, and the list became the thing you edit.
 * Every point on the ride is a row; the rows are in the order they will be
 * ridden; a row is dragged by its handle to change that order and crossed off
 * to remove it; and the row after the last one adds another. The distance and
 * the time did not go anywhere — they are the line under the title, which is
 * where a figure that describes the whole list belongs.
 *
 * ## What a drag actually changes
 *
 * The draft, and only the draft. A stop's `order_index` is its position in
 * [RouteDraft.stops] — see [RouteSetupRules.moved] — so the pins renumber and
 * the road re-measures as the finger moves, and nothing at all is sent to the
 * server until the rider confirms. That is the same rule the rest of this card
 * has always followed; the drag is just another edit to a draft.
 *
 * The figures are LocationIQ's own, over the road. When there is no road route
 * — no key on the server, no road between the points, a quota already spent —
 * it falls back to the straight-line measure the progress bar has always
 * shown, captioned "direct" in exactly the same words, rather than showing
 * nothing or pretending the straight line is a road.
 *
 * ## Why the button does not say "Start"
 *
 * This app has no turn-by-turn navigation and is not getting any. A button
 * saying "Start" on a screen that looks like this one would be read by every
 * rider who has ever used Google Maps as "begin guiding me", and what it
 * actually does is save two coordinates to a trip. So it says what it does.
 */
@Composable
internal fun RouteListSheet(
    draft: RouteDraft,
    /**
     * Every coordinate the route touches, in order — what the straight-line
     * fallback measures when there is no road figure to show.
     */
    plan: RoutePlan?,
    route: RouteLine?,
    loading: Boolean,
    canEditEnds: Boolean,
    canAddStops: Boolean,
    /** Whoever is signed in — the author half of who may remove a saved stop. */
    currentUserId: Long?,
    /** Tapping a row: fill this part of the route. */
    onPick: (RouteField) -> Unit,
    /** The cross on a row, by row index rather than by stop index. */
    onRemoveRow: (Int) -> Unit,
    /** A row dragged from one position in the list to another. */
    onMoveRow: (Int, Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Whether to mention the long press.
     *
     * True over the map, where pressing and holding really does drop a stop,
     * and false on the trip's own edit screen, where there is no map under the
     * list to point at. An instruction for a gesture the rider cannot make is
     * worse than no instruction.
     */
    showLongPressHint: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val rowCount = RouteSetupRules.rowCount(draft)
    val movable = RouteSetupRules.movableRows(draft, canEditEnds)

    // Which row is under the finger, and how far it has been dragged past the
    // slot it now occupies.
    //
    // The index moves with the point rather than with the row that captured
    // the gesture: the list re-orders under the finger as soon as a boundary
    // is crossed, so after one hop the dragged point is a row further down and
    // the offset that is left is the remainder inside that row.
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    // A drag that outlives its row would leave a row translated by a stale
    // offset and nothing to end the gesture — a stop removed mid-drag, or a
    // trip that finished and took the right to re-order with it.
    LaunchedEffect(rowCount, movable) {
        val current = dragging
        if (current != null && current !in movable) {
            dragging = null
            dragOffset = 0f
        }
    }

    val rowHeightPx = with(LocalDensity.current) { ROUTE_ROW_HEIGHT.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppSurface, AppSheetShape)
            .border(1.dp, AppLine, AppSheetShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.map_route_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppText,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.map_search_clear_symbol),
                color = AppTextMuted,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // One line for the whole list, directly under its title. Only once
        // there is a route to measure: a distance quoted over a half-filled
        // list would be a figure for a ride nobody has described yet.
        if (draft.isComplete) {
            RouteMeasureRow(plan = plan, route = route, loading = loading)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(AppSurface, AppSearchPanelShape)
                .border(1.dp, AppLine, AppSearchPanelShape)
                // Capped and scrolling rather than growing without limit: this
                // sheet sits over the map, and a route of a dozen stops must
                // not swallow the thing it is describing.
                .heightIn(max = ROUTE_LIST_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            repeat(rowCount) { index ->
                if (index > 0) HudDivider(modifier = Modifier.padding(start = 62.dp))
                RouteListRow(
                    draft = draft,
                    index = index,
                    canEditEnds = canEditEnds,
                    canAddStops = canAddStops,
                    currentUserId = currentUserId,
                    dragging = dragging == index,
                    dragOffset = if (dragging == index) dragOffset else 0f,
                    canDrag = RouteSetupRules.canMoveRow(draft, index, canEditEnds),
                    onClick = {
                        RouteSetupRules.fieldAtRow(draft, index)?.let(onPick)
                    },
                    onRemove = { onRemoveRow(index) },
                    onDragStart = {
                        dragging = index
                        dragOffset = 0f
                    },
                    onDrag = { delta ->
                        dragOffset += delta
                        val current = dragging
                        if (current != null && !movable.isEmpty()) {
                            // Rounded, so a row swaps when the finger passes
                            // the middle of its neighbour rather than when it
                            // has cleared it completely — which is what makes
                            // a slow drag feel like it is pushing the list
                            // rather than dropping into it.
                            val shift = (dragOffset / rowHeightPx).roundToInt()
                            val target = (current + shift)
                                .coerceIn(movable.first, movable.last)
                            if (target != current) {
                                onMoveRow(current, target)
                                dragOffset -= (target - current) * rowHeightPx
                                dragging = target
                            }
                        }
                    },
                    onDragEnd = {
                        dragging = null
                        dragOffset = 0f
                    },
                )
            }

            if (canAddStops) {
                HudDivider(modifier = Modifier.padding(start = 62.dp))
                // The row that used to be a button under the list. On the list
                // it reads as what it is — the next stop, waiting to be told
                // where — and it is where a rider's eye already is after
                // reading the last row.
                AddStopRow(onClick = { onPick(RouteField.STOP) })
            }
        }

        // A member who is not the owner sees the two ends but cannot move or
        // clear them — `PATCH /trips/:id` is owner-only. Rows with no handle
        // and no cross say that they are fixed but not why, so the sheet does.
        if (!canEditEnds) {
            Text(
                text = stringResource(R.string.map_route_ends_locked),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // The long press is the one gesture on the map with nothing on the
        // screen to suggest it, so the sheet says so — for a rider who would
        // rather point at the map than type a name.
        if (canAddStops && showLongPressHint) {
            Text(
                text = stringResource(R.string.map_route_stop_hint),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        HudPrimaryButton(
            text = stringResource(R.string.map_route_confirm),
            onClick = onConfirm,
            // Off until there is a route to save. A confirm on a half-filled
            // list would PATCH one end and leave the ride with a start and no
            // finish, which is the state this whole card exists to avoid.
            enabled = draft.isComplete,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
    }
}

/**
 * How far the route is and how long it takes, in one line under the title.
 *
 * The caption says which of the two measures is on screen, in the same words
 * the gauge down the side of the map uses — "by road" when LocationIQ quoted a
 * road, "direct" when this is the straight line through the points. A rider
 * who is looking at the second one is entitled to know it.
 */
@Composable
private fun RouteMeasureRow(plan: RoutePlan?, route: RouteLine?, loading: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = AppPrimary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.map_route_measuring),
                style = MaterialTheme.typography.bodySmall,
                color = AppTextMuted,
                modifier = Modifier.padding(start = 10.dp),
            )
        } else {
            Text(
                text = routeDistanceText(plan, route),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppText,
            )
            Text(
                text = routeMeasureCaption(route),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
            routeDurationText(route)?.let { duration ->
                Text(
                    text = duration,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = AppPrimary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/**
 * One row of the route list.
 *
 * Four things, left to right, and each of them is one of the things a rider
 * can do to a point: a handle to drag it somewhere else in the ride, a marker
 * saying what it is, the place itself — tap to change it — and a cross to take
 * it off.
 *
 * A row a rider may not act on shows neither handle nor cross rather than
 * greying them: `PATCH /trips/:id` is owner-only and the waypoints route
 * refuses a finished trip, and a control that is visible but inert gets
 * pressed again and again before anybody believes it.
 */
@Composable
private fun RouteListRow(
    draft: RouteDraft,
    index: Int,
    canEditEnds: Boolean,
    canAddStops: Boolean,
    /** Whoever is signed in — the author half of who may remove a saved stop. */
    currentUserId: Long?,
    dragging: Boolean,
    dragOffset: Float,
    canDrag: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val field = RouteSetupRules.fieldAtRow(draft, index) ?: return
    val picked = RouteSetupRules.pointAtRow(draft, index)
    val stopNumber = RouteSetupRules.stopIndexAtRow(draft, index)?.plus(1)
    val editable = if (field == RouteField.STOP) canAddStops else canEditEnds
    val removable =
        RouteSetupRules.canRemoveRow(draft, index, canEditEnds, canAddStops, currentUserId)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ROUTE_ROW_HEIGHT)
            // Lifted out of the list while it is being dragged, so the row
            // under the finger is drawn over its neighbours rather than
            // sliding behind them as they move.
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = dragOffset }
            .background(if (dragging) AppSurfaceAlt else Color.Transparent)
            // A stop row has nothing to open — a stop is changed by removing
            // it and adding the one you meant — so only the two ends are
            // tappable, and only for a rider who may set them.
            .then(
                if (editable && field != RouteField.STOP) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp),
    ) {
        if (canDrag) {
            RouteDragHandle(
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
            )
        } else {
            Spacer(modifier = Modifier.width(ROUTE_HANDLE_WIDTH))
        }

        // The same three shapes the map draws — a dot where you set off, a
        // number on each stop, a pin at the finish — so the list and the map
        // read as one route rather than as two lists of the same coordinates.
        Box(
            modifier = Modifier.width(28.dp).padding(start = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (editable) AppPrimary else AppTextMuted
            when {
                stopNumber != null -> Text(
                    text = stringResource(R.string.map_route_stop_number, stopNumber),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
                field == RouteField.FROM -> HudDot(color = tint)
                else -> HudPinIcon(tint = tint)
            }
        }

        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = stringResource(field.rowLabelRes),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
            )
            Text(
                // An empty end says what to do about it rather than sitting
                // blank: a row a rider has not filled in and a row whose tap
                // did not register look identical otherwise.
                text = picked?.let { routePointLabel(it) }
                    ?: stringResource(field.rowHintRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (picked == null) AppTextMuted else AppText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (removable) {
            Text(
                text = stringResource(R.string.map_search_clear_symbol),
                color = AppTextMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The grip a row is dragged by.
 *
 * A handle rather than the whole row, and no long press to arm it. The row is
 * over a map that pans, inside a list that scrolls, and both of those are
 * vertical drags: a row that could be dragged anywhere on it would be a row
 * that fights the list it is in. Two rules-of-thumb of grip, on the far left
 * where a thumb already is, and the gesture is unambiguous from the first
 * pixel.
 */
@Composable
private fun RouteDragHandle(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // Read through state rather than captured: the gesture outlives several
    // recompositions — the list re-orders under it on every hop — and a
    // captured lambda would go on calling the one from the frame it started in.
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)

    Box(
        modifier = Modifier
            .width(ROUTE_HANDLE_WIDTH)
            .height(ROUTE_ROW_HEIGHT)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start() },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                    onDrag = { change, amount ->
                        // Consumed, or the sheet's own scroll and the map
                        // behind it would both take the same finger.
                        change.consume()
                        drag(amount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(1.5.dp)
                        .background(AppTextMuted),
                )
            }
        }
    }
}

/** The row after the last one: another stop, waiting to be told where. */
@Composable
private fun AddStopRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ROUTE_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Spacer(modifier = Modifier.width(ROUTE_HANDLE_WIDTH))
        Box(
            modifier = Modifier.width(28.dp).padding(start = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.map_route_add_symbol),
                style = MaterialTheme.typography.titleMedium,
                color = AppPrimary,
            )
        }
        Text(
            text = stringResource(R.string.map_route_stop_add),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = AppPrimary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** What a row is called: the part of the route it holds. */
private val RouteField.rowLabelRes: Int
    get() = when (this) {
        RouteField.FROM -> R.string.map_route_from
        RouteField.TO -> R.string.map_route_to
        RouteField.STOP -> R.string.map_route_stop
    }

/**
 * What an empty row says instead of nothing.
 *
 * Only the two ends can actually be empty — a stop row exists because a stop
 * is in it. Its wording is here because the `when` is exhaustive, and "Add
 * stop" is the honest thing for a row that somehow has no stop in it.
 */
private val RouteField.rowHintRes: Int
    get() = when (this) {
        RouteField.FROM -> R.string.map_route_from_hint
        RouteField.TO -> R.string.map_route_to_hint
        RouteField.STOP -> R.string.map_route_stop_add
    }

/** One row, and the unit a drag is measured in. Fixed, so the arithmetic is. */
private val ROUTE_ROW_HEIGHT = 56.dp

/** The grip on the left of a row. Wide enough for a thumb, narrow enough to miss. */
private val ROUTE_HANDLE_WIDTH = 30.dp

/**
 * How tall the list may get before it starts scrolling.
 *
 * Five rows and a bit, which covers a start, a finish, three stops and the row
 * that adds a fourth — more than most rides have, and the point past which the
 * sheet would be swallowing the map it is describing. A longer route scrolls;
 * a row dragged towards an edge stops at it rather than scrolling the list
 * after it, which is worth knowing about before planning a ride with ten stops
 * on one screen.
 */
private val ROUTE_LIST_MAX_HEIGHT = 320.dp

/**
 * The distance to show: the road's when there is one, the straight line's
 * otherwise.
 *
 * The fallback is the length of every leg — start to first stop, stop to stop,
 * last stop to finish — rather than the gap between the two ends. Quoting the
 * end-to-end gap on a route with three stops on it would understate the ride
 * by however far out of the way the stops are, directly above the button that
 * saves it.
 */
@Composable
private fun routeDistanceText(plan: RoutePlan?, route: RouteLine?): String {
    val km = route?.distanceKm ?: plan?.let { RouteGeometry.lengthKm(it.points) }
    return RouteProgress.format(km) ?: stringResource(R.string.map_speed_unknown)
}

/** Which of the two measures is on screen, in the same words the gauge uses. */
@Composable
private fun routeMeasureCaption(route: RouteLine?): String =
    if (route?.distanceKm != null) stringResource(R.string.map_progress_by_road)
    else stringResource(R.string.map_progress_straight_line)

/**
 * How long the road takes, or null when nothing said.
 *
 * Nothing is estimated from the distance when the figure is missing: an
 * invented time on a mountain road would be wrong by a factor, and wrong in
 * the direction that makes a rider late.
 */
@Composable
private fun routeDurationText(route: RouteLine?): String? {
    val (hours, minutes) = RouteEta.split(route?.durationMinutes) ?: return null
    return when {
        hours == 0 -> stringResource(R.string.map_route_duration_minutes, minutes)
        minutes == 0 -> stringResource(R.string.map_route_duration_hours, hours)
        else -> stringResource(R.string.map_route_duration_hours_minutes, hours, minutes)
    }
}

/**
 * A name for a stop that arrived without one.
 *
 * The one question the new flow still has to ask, and only for a stop: the
 * server refuses an unnamed waypoint, because a list of unnamed stops is a
 * list of identical rows. The two ends need nothing — the map draws the flag
 * either way — which is why picking them opens no dialog at all.
 */
@Composable
internal fun StopNameDialog(
    point: LatLng,
    /**
     * What the field starts with, so confirming takes no typing at all.
     *
     * "Stop 3" rather than empty: the server refuses an unnamed waypoint, and
     * a rider who pressed and held a spot on the map has already said the only
     * thing they wanted to say. A number is a poor name and a fine label — it
     * is ordinal, it tells two rows apart, and it is selected for overtyping
     * by anybody who has something better.
     */
    suggestedName: String,
    onName: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(point) { mutableStateOf(suggestedName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.map_route_stop_name_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.map_place_coordinates,
                        formatCoordinate(point.lat),
                        formatCoordinate(point.lng),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextMuted,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { typed ->
                        if (typed.length <= MapPlacementRules.NAME_MAX_LENGTH) name = typed
                    },
                    label = { Text(stringResource(R.string.map_place_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            HudPrimaryButton(
                text = stringResource(R.string.map_route_stop_add),
                onClick = { onName(name) },
                enabled = RouteSetupRules.isStopNameValid(name),
            )
        },
        dismissButton = {
            HudSecondaryButton(text = stringResource(R.string.cancel), onClick = onDismiss)
        },
        containerColor = AppSurface,
        titleContentColor = AppText,
        textContentColor = AppTextMuted,
    )
}

/** Tall enough for three or four stops, short enough to leave the map visible. */
private val ROUTE_STOPS_MAX_HEIGHT = 132.dp

/** Rounded at the top only: it rises off the bottom edge rather than floating. */
private val AppSheetShape = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
)


/**
 * The place picker, as its own screen.
 *
 * ## Why it takes the whole display
 *
 * What was here was a panel: a search box under the map's header, with a
 * results list capped at 240dp so the map stayed visible behind it. That cap
 * was the problem. On a phone with the keyboard up there was room for three
 * results, and the answer a rider wanted was very often the fourth — so a
 * search that had worked perfectly looked like a search that had not found
 * anything, and the fix was to scroll a list nothing said was scrollable.
 *
 * Searching is not something you do *while* looking at the map. It is a
 * question with an answer, and the answer is the only thing on screen until
 * it is given: a field at the top, results filling everything below it, back
 * to the map the moment one is tapped. Nothing is hidden by taking the map
 * away, because a rider typing a name is not reading it.
 *
 * Opaque rather than translucent, and drawn over everything else in the
 * screen's root box — a picker you can see the map through is a picker whose
 * taps a rider expects to reach the map.
 */
@Composable
internal fun PlaceSearchScreen(
    state: PlaceSearchState,
    /** Which part of the route this is filling, said out loud over the box. */
    heading: String,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    /** Back to the route list without choosing anything. */
    onBack: () -> Unit,
    onPick: (Place) -> Unit,
    /**
     * Where this phone is, and whether it was allowed to know — the two
     * inputs to the shortcut pinned above the results. See
     * [CurrentLocationRules].
     */
    myLocation: LatLng?,
    hasLocationPermission: Boolean,
    onUseCurrentLocation: (LatLng) -> Unit,
    /** Whoever is signed in, so their own shared places can offer a cross. */
    currentUserId: Long?,
    /**
     * "This is not on the map" — arms the map to be pointed at, carrying
     * whatever has been typed so far as the name.
     */
    onAddPlace: (String) -> Unit,
    onRemoveSharedPlace: (Place) -> Unit,
    /**
     * This rider's own saved places, drawn as shortcuts above everything else.
     *
     * Never anybody else's — see [PersonalPlace]. They are at the top because
     * they are the answer most often: the two places a person rides to most
     * are home and work, and typing either of them into a geocoder to find a
     * point the phone already knows is the long way round.
     */
    personalPlaces: List<PersonalPlace> = emptyList(),
    onPickPersonal: (PersonalPlace) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }

    // Open with the keyboard already up and the cursor in the box. The screen
    // exists to be typed into, and a rider who has to tap the field they are
    // already looking at has been made to ask for what they came for twice.
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            // Nothing behind this may be tapped through it. Without this the
            // map still takes a long press through the gaps between rows.
            .pointerInput(Unit) { detectTapGestures { } }
            // The system bars are already inset by the Scaffold this screen
            // lives in; the keyboard is not, and it is the one that matters
            // here — without this the results list runs on underneath it and
            // the rows a rider is reaching for are the ones they cannot see.
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(AppSurface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            HudIconButton(
                onClick = onBack,
                contentDescription = stringResource(R.string.back),
                icon = { HudBackIcon(tint = AppText) },
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                singleLine = true,
                // The slot as the label and the kind of thing as the
                // placeholder: "Where are you going?" over "Town, road, petrol
                // station…". A rider two taps in has been told what the box is
                // for, and the box still says what goes in it.
                label = { Text(heading) },
                placeholder = { Text(stringResource(R.string.map_search_placeholder)) },
                trailingIcon = {
                    // One slot, two jobs: the spinner while a request is out,
                    // and a way to empty the box once there is something in
                    // it. They never both apply — a search in flight has text
                    // behind it, and the spinner is the more urgent thing to
                    // say.
                    if (state.searching) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = AppPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    } else if (state.query.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.map_search_clear_symbol),
                            color = AppTextMuted,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .clickable(onClick = onClear)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
                    .focusRequester(focus),
            )
        }

        HudDivider()

        // The rider's own shortcuts, above everything. Home and work are the
        // two places most rides start or end at, and neither is worth a search.
        if (personalPlaces.isNotEmpty()) {
            PersonalPlaceChips(places = personalPlaces, onPick = onPickPersonal)
            HudDivider()
        }

        // Pinned above everything else, and there before a key is pressed:
        // most points a rider places are where they already are, and making
        // them type a name for the spot they are standing on — and then wait
        // on a geocoder to hand back a coordinate the phone has had all along
        // — is the long way round. Costs no request.
        CurrentLocationRow(
            location = myLocation,
            hasPermission = hasLocationPermission,
            onClick = onUseCurrentLocation,
        )

        HudDivider()

        state.problem?.let { problem ->
            // Worded from the reason, not from the HTTP status. The status's
            // own wording for a 404 is "That's no longer there.", which on a
            // search box is a sentence about a place that has closed down —
            // and what a 404 here actually means is that the server has no
            // search route at all.
            Text(
                text = placeSearchMessage(problem, state.serverMessage),
                style = MaterialTheme.typography.bodySmall,
                color = AppTextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        if (state.isEmpty) {
            Text(
                text = stringResource(R.string.map_search_empty),
                style = MaterialTheme.typography.bodySmall,
                color = AppTextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        // Everything that is left, which is the whole point of the screen: as
        // many results as the phone has room for rather than the three that
        // fitted under a 240dp cap.
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // A heading over the riders' own places, and only when there are
            // any. They are first — see mergePlaceResults for why — and a
            // group of rows that arrived by a different route with a different
            // kind of authority is worth naming before a rider reads it.
            if (state.hasShared) {
                item {
                    Text(
                        text = stringResource(R.string.map_search_shared_heading),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted,
                        modifier = Modifier.padding(
                            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp,
                        ),
                    )
                }
            }
            items(state.results, key = { it.key }) { place ->
                PlaceSearchRow(
                    place = place,
                    onClick = { onPick(place) },
                    currentUserId = currentUserId,
                    onRemove = onRemoveSharedPlace,
                )
                HudDivider(modifier = Modifier.padding(start = 16.dp))
            }

            // Under the results rather than above them: a rider reads what was
            // found first, and only then decides that none of it is the place
            // they meant.
            item { AddSharedPlaceRow(query = state.query, onClick = { onAddPlace(state.query) }) }
        }

        // The third way in, and the only one that needs neither a name nor a
        // working search key. Said out loud at the bottom because a long press
        // is the one gesture on the map with nothing on screen to suggest it —
        // and while a row is waiting, it fills that row.
        Text(
            text = stringResource(R.string.map_route_pick_hint),
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * A rider's own saved places, as a row of chips.
 *
 * ## Why these are chips and not rows
 *
 * They are not results. A result is something the rider is choosing between;
 * these are things they already decided on, and the decision was made the day
 * they saved them. A chip reads as a shortcut — one tap, no reading — and a
 * row of them costs a fraction of the height the same places would take as
 * list rows, above a list that needs every pixel it can get.
 *
 * It also keeps them visually apart from the two lists underneath, which
 * matters more here than it usually would: everything below this row is
 * visible to the whole server, and everything in it is visible to nobody.
 *
 * Tapping one fills the row the rider came here to fill and closes the search.
 * There is no request: the coordinate arrived with the chip.
 */
@Composable
private fun PersonalPlaceChips(places: List<PersonalPlace>, onPick: (PersonalPlace) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        places.forEach { place ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(AppPrimarySoft, AppChipShape)
                    .clickable { onPick(place) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                HudPinIcon(tint = AppPrimary)
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = place.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = AppPrimary,
                        maxLines = 1,
                    )
                    // The place's own name under the label, because "บ้าน" a
                    // year later is not obviously the same address it was.
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
            }
        }
    }
}

/** The rounded shape behind a shortcut chip. */
private val AppChipShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)

/**
 * "Use my current location", as the first row of the panel.
 *
 * Reads the fix the screen already holds rather than asking for a new one:
 * the map is being driven by a live 1 Hz feed while it is open, so the
 * coordinate under this row is the same one the rider's own dot is drawn at,
 * and tapping is instant. Nothing is sent to LocationIQ, so nothing is taken
 * out of the day's quota either.
 *
 * Offered for all three placements, not only the start. A rider who has
 * stopped somewhere worth meeting at wants that spot as the finish or as a
 * stop just as often as they want it as where they set off from.
 *
 * When it cannot be used it stays visible and says why, rather than
 * disappearing: a row that is missing tells a rider nothing, and one that is
 * greyed out with no reason gets tapped again and again.
 */
@Composable
private fun CurrentLocationRow(
    location: LatLng?,
    hasPermission: Boolean,
    onClick: (LatLng) -> Unit,
) {
    val state = CurrentLocationRules.state(hasPermission, location)
    val usable = state == CurrentLocation.READY

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Only clickable when there is something to place. An enabled-
            // looking row that does nothing reads as the app being broken.
            .then(
                if (usable && location != null) {
                    Modifier.clickable { onClick(location) }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        HudPinIcon(tint = if (usable) AppPrimary else AppTextMuted)
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = stringResource(R.string.map_search_use_my_location),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (usable) AppText else AppTextMuted,
            )
            Text(
                // The coordinate when there is one, so a rider can see what
                // they are about to place before the dialog opens; the reason
                // when there is not.
                text = when (state) {
                    CurrentLocation.READY -> stringResource(
                        R.string.map_place_coordinates,
                        formatCoordinate(location!!.lat),
                        formatCoordinate(location.lng),
                    )
                    CurrentLocation.NO_PERMISSION ->
                        stringResource(R.string.map_search_location_no_permission)
                    CurrentLocation.NO_FIX ->
                        stringResource(R.string.map_search_location_no_fix)
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One result: what it is called, where that is, and which list it came from.
 *
 * ## Why the two sources are told apart on the row
 *
 * A LocationIQ row is a map company's record of somewhere that exists. A
 * shared row is a rider on this server saying "this is here, I have been" —
 * which is very often the only answer there is, because the geocoder cannot
 * find what OpenStreetMap does not contain. Both are worth having and they are
 * not the same claim, so the row says which, and says who wrote it.
 *
 * The cross is offered only to whoever typed the place in. Everyone can see
 * every row, so a cross on somebody else's would be an invitation to remove
 * something the group is still using — see canDeleteSharedPlace in
 * src/places/shared.js, which is the rule this mirrors.
 */
@Composable
private fun PlaceSearchRow(
    place: Place,
    onClick: () -> Unit,
    /** Whoever is signed in, so a rider's own places can offer a cross. */
    currentUserId: Long?,
    onRemove: (Place) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = AppText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (place.source == PlaceSource.SHARED) {
                    // Small, quiet, and next to the name rather than under it:
                    // it qualifies what the name is, and a rider reading down
                    // a list of names has to meet it at the same moment.
                    Text(
                        text = stringResource(R.string.map_search_shared_tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppPrimary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(AppPrimarySoft, AppTagShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                // For a geocoder result, the full address — which is what
                // tells two places with the same name apart, and there are a
                // great many 7-Elevens. For a shared one there is no address
                // to show, so it says who wrote it down instead.
                text = when {
                    place.source != PlaceSource.SHARED -> place.address
                    place.address.isNotBlank() ->
                        stringResource(R.string.map_search_shared_by, place.address)
                    else -> stringResource(R.string.map_search_shared_by_gone)
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (place.isRemovableBy(currentUserId)) {
            Text(
                text = stringResource(R.string.map_search_clear_symbol),
                color = AppTextMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clickable { onRemove(place) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The row that turns "nothing found" into something a rider can do about it.
 *
 * Offered whatever the search came back with, not only when it found nothing.
 * A rider looking at eight towns none of which is the petrol station they
 * meant is in exactly the same position as one looking at an empty list, and
 * making them clear the box first to be offered the way out would be a step
 * that exists only because the code found it convenient.
 *
 * What it does *not* do is save anything. It arms the map: the name is known,
 * the point is not, and a point is a thing you say by pointing at it. See the
 * banner in TripMapScreen.
 */
@Composable
private fun AddSharedPlaceRow(query: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.map_route_add_symbol),
            style = MaterialTheme.typography.titleMedium,
            color = AppPrimary,
        )
        Text(
            // The typed name in the row when there is one, so the tap reads as
            // "add this" rather than as opening one more thing to fill in.
            text = query.trim().takeIf { it.isNotEmpty() }
                ?.let { stringResource(R.string.map_search_add_named, it) }
                ?: stringResource(R.string.map_search_add_place),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = AppPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** The pill behind the "rider-added" tag. */
private val AppTagShape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

/**
 * What to say when a search fails, in the rider's own language.
 *
 * The two "search does not work here" cases are worded apart on purpose. Both
 * are for somebody to fix rather than for the rider to retry, but they have
 * different fixes — one needs the backend deployed, the other needs a key in
 * `.env` — and the person reading this is very often the person who has to
 * apply one of them.
 */
@Composable
private fun placeSearchMessage(problem: PlaceSearchProblem, serverMessage: String?): String =
    when (problem) {
        PlaceSearchProblem.NOT_DEPLOYED -> stringResource(R.string.map_search_not_deployed)
        PlaceSearchProblem.NOT_CONFIGURED -> stringResource(R.string.map_search_not_configured)
        PlaceSearchProblem.TOO_MANY -> stringResource(R.string.map_search_too_many)
        PlaceSearchProblem.UPSTREAM -> stringResource(R.string.map_search_upstream)
        PlaceSearchProblem.OFFLINE -> stringResource(R.string.map_search_offline)
        // The one case where the server knows something this app does not, so
        // its own sentence is better than anything written here in advance.
        PlaceSearchProblem.UNKNOWN ->
            serverMessage?.takeIf { it.isNotBlank() } ?: stringResource(R.string.map_search_failed)
    }

/** Tall enough for four or five results, short enough to leave the map visible. */
private val SEARCH_RESULTS_MAX_HEIGHT = 240.dp

private val AppSearchPanelShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

/** What this rider may place on this trip. Null trip means nothing yet. */
private fun placementsAllowed(trip: Trip?): List<MapPlacement> {
    if (trip == null) return emptyList()
    return MapPlacementRules.allowed(isOwner = trip.isOwner, isTripActive = trip.isActive)
}

/**
 * What to do with the point a rider just pressed and held on.
 *
 * One dialog for all three answers rather than a mode the rider has to enter
 * first: the gesture is the same whichever they meant, and which of them are
 * offered depends on whether they own the trip and whether it is still
 * running — see [MapPlacementRules].
 *
 * The name field is the same field for all three, and only its rules differ:
 * a stop must be named, an end of the trip need not be.
 */
@Composable
private fun PlacePointDialog(
    point: LatLng,
    options: List<MapPlacement>,
    onPlace: (MapPlacement, String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * The name the point arrives with, which is the searched place's own when
     * it came from the search box and empty when it came from a long press.
     *
     * Keyed on the point rather than remembered flat: picking a second search
     * result without closing the dialog has to replace the first one's name,
     * and a bare `rememberSaveable` would keep showing the first.
     */
    initialName: String = "",
) {
    var name by rememberSaveable(point, initialName) {
        mutableStateOf(initialName.take(MapPlacementRules.LABEL_MAX_LENGTH))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.map_place_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        R.string.map_place_coordinates,
                        formatCoordinate(point.lat),
                        formatCoordinate(point.lng),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextMuted,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { typed ->
                        // Bounded by the longest field any of the buttons
                        // could write to, so switching between them never
                        // silently truncates what has been typed.
                        if (typed.length <= MapPlacementRules.LABEL_MAX_LENGTH) name = typed
                    },
                    label = { Text(stringResource(R.string.map_place_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    options.forEach { placement ->
                        HudPrimaryButton(
                            text = stringResource(placement.labelRes),
                            onClick = { onPlace(placement, name) },
                            enabled = MapPlacementRules.isNameValid(placement, name),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                }
            }
        },
        // The choices are the buttons in the body — there is no single
        // "confirm" here, because which button was pressed is the answer.
        confirmButton = {},
        dismissButton = {
            HudSecondaryButton(text = stringResource(R.string.cancel), onClick = onDismiss)
        },
        containerColor = AppSurface,
        titleContentColor = AppText,
        textContentColor = AppTextMuted,
    )
}

/** The label on the button that places this kind of point. */
private val MapPlacement.labelRes: Int
    get() = when (this) {
        MapPlacement.ORIGIN -> R.string.map_place_origin
        MapPlacement.DESTINATION -> R.string.map_place_destination
        MapPlacement.WAYPOINT -> R.string.map_place_waypoint
    }

/**
 * A coordinate, to five decimal places.
 *
 * About a metre at this latitude — enough to tell two ends of a car park
 * apart, and short enough to read back to somebody over a phone. Locale-fixed
 * so the decimal separator is a point on a Thai phone as well: this is a
 * coordinate, not a quantity, and every map that could be pasted into expects
 * one.
 */
private fun formatCoordinate(value: Double): String =
    String.format(java.util.Locale.US, "%.5f", value)

/**
 * How far there is left to go, as a column down the edge of the map.
 *
 * Vertical because the map is the screen and a horizontal bar would take a
 * band out of the middle of it; down the right because that is the hand that
 * is already there. It fills from the bottom, which is the direction a rider
 * reads a journey filling.
 *
 * ## Drawn to be seen on a map, not on a card
 *
 * The first version was the theme blue on a grey track, 10dp wide, inset a
 * long way at both ends — and on a real ride it was invisible. Everything
 * about that is fixed here at once, because none of the three fixes works
 * alone:
 *
 *  - **Orange, not blue.** OSM's daylight style is full of blue water. See
 *    [AppRouteProgress].
 *  - **A white casing.** The same thing a road atlas does with a coloured
 *    route line: the fill then only has to contrast with its own outline,
 *    never with whatever tile happens to be behind it.
 *  - **Longer and thicker.** 14dp instead of 10, and nearly the full height
 *    of the map instead of two thirds — a bar that is mostly inset reads as
 *    a decoration rather than a gauge.
 *
 * Straight-line distance, and the label says so — the app has no routing
 * engine and is not getting one to draw a bar. On a mountain road the number
 * will read low, and a bar that claimed to know the road would be wrong by
 * however much the road bends, and wrong confidently.
 *
 * ## The labels are not inside the bar's width
 *
 * They were, and it made them unreadable: the column carried
 * `.width(PROGRESS_BAR_TOTAL_WIDTH)`, all 18dp of it, which every child
 * inherited as a maximum. After the plate's own padding that left a couple of
 * dp for glyphs, so each label wrapped one character per line and the word
 * "direct" arrived as six letters stacked vertically down the edge of the
 * map. Only the [Canvas] is width-constrained now; the column takes whatever
 * the labels need and hangs them off the right edge.
 *
 * Nothing is drawn at all without a destination and a position: an empty bar
 * would be a claim that the rider has not started, which is a different and
 * false statement from having nothing to measure.
 */
@Composable
private fun RouteProgressBar(
    fraction: Double?,
    remaining: String?,
    /**
     * Whether the numbers came from a road route rather than from the straight
     * line between two points.
     *
     * It changes the caption and nothing else, which is the point: the two
     * measures answer the same question to very different accuracies on a
     * mountain road, and a rider who cannot tell which one they are reading
     * cannot judge either.
     */
    byRoad: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (fraction == null && remaining == null) return

    // The bar slides rather than jumping between polls, for the same reason
    // the pins do: a step is read as a glitch, a slide as movement.
    val filled by animateFloatAsState(
        targetValue = fraction?.toFloat() ?: 0f,
        label = "route-progress",
    )

    Column(
        // Deliberately *not* width-constrained. It used to be
        // `.width(PROGRESS_BAR_TOTAL_WIDTH)`, which is 18dp — and since the
        // labels are children of this column, that became their maximum width
        // too. Minus their own padding it left about two dp of room for text,
        // so every label wrapped one character per line and "direct" came out
        // as six stacked letters. Only the bar itself has a fixed width; the
        // column wraps whatever the labels need.
        modifier = modifier,
        // End rather than centre, so the bar keeps hugging the right edge
        // while the label plate grows leftward over the map instead of
        // dragging the bar away from the edge with it.
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The number and the caveat that qualifies it, on one plate.
        //
        // They used to sit at opposite ends of a bar most of a screen tall,
        // which put the word "direct" nowhere near the distance it was
        // describing. It qualifies the number, so it lives with the number —
        // and it now appears whenever the number does, including on a trip
        // with a destination but no start, where there is no bar to draw and
        // the caveat used to be silently dropped.
        remaining?.let {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .background(AppSurface.copy(alpha = 0.96f), PROGRESS_LABEL_SHAPE)
                    .border(1.dp, AppRouteProgress.copy(alpha = 0.5f), PROGRESS_LABEL_SHAPE)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    // The one number on this bar, so it gets the bar's colour.
                    color = AppRouteProgress,
                    // Belt and braces against the bug above: if some future
                    // layout squeezes this again it ends as an ellipsis, which
                    // is legible, rather than as a column of single letters.
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (byRoad) R.string.map_progress_by_road
                        else R.string.map_progress_straight_line
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (byRoad) AppRouteProgress.copy(alpha = 0.85f) else AppTextMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (fraction != null) {
            Canvas(
                modifier = Modifier
                    .width(PROGRESS_BAR_TOTAL_WIDTH)
                    .weight(1f)
            ) {
                val x = size.width / 2f
                val barWidth = PROGRESS_BAR_WIDTH.toPx()
                val casingWidth = barWidth + 2 * PROGRESS_BAR_CASING.toPx()
                val top = Offset(x, 0f)
                val bottom = Offset(x, size.height)
                val head = Offset(x, size.height * (1f - filled))

                // White underneath the whole length, so the fill contrasts
                // with its own outline rather than with the map.
                drawLine(
                    color = AppRouteProgressCasing,
                    start = top,
                    end = bottom,
                    strokeWidth = casingWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = AppRouteProgressTrack.copy(alpha = 0.28f),
                    start = top,
                    end = bottom,
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
                // Filled from the bottom: the rider is at the top of what they
                // have done, with what is left above them.
                drawLine(
                    color = AppRouteProgress,
                    start = bottom,
                    end = head,
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
                // The head of the fill, marked so the eye finds "here" without
                // having to find the join between two colours.
                drawCircle(
                    color = AppRouteProgressCasing,
                    radius = casingWidth / 2f,
                    center = head,
                )
                drawCircle(
                    color = AppRouteProgress,
                    radius = barWidth / 2f,
                    center = head,
                )
            }
        }
    }
}

/** Rounded, not a pill: the plate carries two lines now, not one. */
private val PROGRESS_LABEL_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)

/**
 * The coloured part of the bar.
 *
 * 14dp rather than the 10 it was: on a 400dp-wide screen that is still under
 * four per cent of the width, and it is the difference between a gauge and a
 * hairline seen through a visor at speed.
 */
private val PROGRESS_BAR_WIDTH = 14.dp

/** How far the white casing stands out past the fill, on each side. */
private val PROGRESS_BAR_CASING = 2.dp

/** The column's own width: the bar, its casing, and room for the round cap. */
private val PROGRESS_BAR_TOTAL_WIDTH = PROGRESS_BAR_WIDTH + PROGRESS_BAR_CASING * 2

// Where the bar starts and stops now lives in ProgressBarLayout, worked out
// from the header's measured height rather than from a constant that assumed
// one. The constants that used to be here — a 76dp top inset "just clear of
// the floating header, which is about 68dp tall" — are exactly what put the
// bar's label plate underneath the header the moment the header was taller
// than the guess.

/**
 * The floating header: trip name, the way back, and this rider's own speed.
 *
 * Painted on a near-opaque plate because it sits over map tiles, which are
 * pale and busy and would otherwise swallow the title.
 */
@Composable
private fun MapOverlayBar(
    title: String,
    subtitle: String,
    speedKmh: Int?,
    reportAgeMinutes: Long?,
    error: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether this rider has anything they could set, and so a button worth showing. */
    canSetUpRoute: Boolean = false,
    routeSetupOpen: Boolean = false,
    onToggleRouteSetup: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppSurface.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp),
    ) {
        HudTopBar(
            title = title,
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
            subtitle = buildString {
                append(subtitle)
                // Only while sharing: on a trip this phone is only watching,
                // there is no report of its own to be late.
                reportAgeMinutes?.let { minutes ->
                    append(" · ")
                    append(
                        if (minutes < 1) {
                            stringResource(R.string.map_reported_now)
                        } else {
                            stringResource(R.string.map_reported_minutes, minutes)
                        }
                    )
                }
            },
            trailing = {
                if (canSetUpRoute) {
                    HudIconButton(
                        onClick = onToggleRouteSetup,
                        contentDescription = stringResource(R.string.map_route_setup),
                        icon = {
                            HudSearchIcon(
                                tint = if (routeSetupOpen) AppPrimary else AppTextMuted
                            )
                        },
                    )
                }
                HudReadout(
                    label = stringResource(R.string.map_own_speed),
                    value = speedText(speedKmh),
                    valueColor = if (speedKmh != null) AppPrimary else AppTextMuted,
                )
            },
        )
        error?.let { HudError(it) }
    }
}

/** A speed in km/h, or a dash when the phone has never said. */
@Composable
private fun speedText(kmh: Int?): String =
    kmh?.let { stringResource(R.string.map_speed_kmh, it) }
        ?: stringResource(R.string.map_speed_unknown)

/**
 * How tall the member list may grow before it starts scrolling.
 *
 * A ceiling rather than a share: the list is as tall as the riders in it, so a
 * solo trip gives its whole screen to the map, and a group of ten still leaves
 * the map more than half the height. Sized for about five rows, which is the
 * point at which scrolling is expected anyway.
 */
private val MEMBER_LIST_MAX_HEIGHT = 300.dp

/**
 * The osmdroid map, wrapped for Compose.
 *
 * Markers are kept between updates rather than rebuilt, which is what lets a
 * rider's pin *travel* to its new position — see [MarkerMotion]. Rebuilding
 * them, as this did, made every update a teleport.
 */
@Composable
internal fun RiderMap(
    members: List<MemberPosition>,
    waypoints: List<Waypoint>,
    /**
     * The two ends to draw flags on.
     *
     * The trip's own most of the time, and the draft's while a route is being
     * set up — which is why they are passed as endpoints rather than as the
     * trip: a start a rider has just picked has to appear under their finger
     * before it is saved, or picking it looks like nothing happened.
     */
    origin: TripEndpoint?,
    destination: TripEndpoint?,
    /**
     * The road between those two ends, or empty when there is none to
     * draw. Empty is the ordinary state and draws nothing — the map looks
     * exactly as it did before road routing existed.
     */
    route: List<LatLng>,
    myLocation: LatLng?,
    focus: MapFocus?,
    onMarkerTap: (Long) -> Unit,
    onLongPress: (LatLng) -> Unit,
    onWaypointTap: (Waypoint) -> Unit,
    /** Where to keep the camera, or null when nothing is following anything. */
    follow: LatLng?,
    /** A box to fit once, when the rider asks for the overview. */
    overview: MapOverview?,
    /** Called the moment a finger drags the map. */
    onUserPan: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapConfig.ensureConfigured(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // osmdroid's own zoom buttons duplicate pinch-to-zoom and sit on
            // top of the pins.
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
            // A holding position only: the real one is chosen below, once it
            // is known whether anybody on this trip has reported.
            controller.setZoom(FALLBACK_ZOOM)
            controller.setCenter(GeoPoint(FALLBACK_CENTRE.lat, FALLBACK_CENTRE.lng))

            // Tiles are drawn as OpenStreetMap serves them.
            //
            // They used to be inverted by osmdroid's night-mode colour matrix
            // and then washed with a dark scrim, which was the only way to
            // make a daylight tile set belong in an app that was near-black
            // everywhere else. The app is light now, so the tiles already
            // match it — and the inversion was never free: it fought the
            // cartography, turning green parks grey-brown and water into
            // something the eye reads as land.
        }
    }

    // A marker per rider, and where each one is drawn right now — which is not
    // where that rider is until their slide has finished.
    val markers = remember { mutableMapOf<Long, Marker>() }
    val drawn = remember { mutableMapOf<Long, LatLng>() }

    // Kept apart from the rider markers so the two can never be confused for
    // one another — by this code or by the person reading the screen.
    val waypointMarkers = remember { mutableListOf<Marker>() }
    val endpointMarkers = remember { mutableListOf<Marker>() }

    // The road, and everybody's breadcrumbs. Lines rather than markers, and
    // kept in their own lists for the same reason the markers are: each is
    // rebuilt on its own trigger, and a shared list would have one of them
    // clearing the other's overlays.
    val routeLines = remember { mutableListOf<Polyline>() }

    // Press and hold anywhere to place a point.
    //
    // Read through `rememberUpdatedState` because the overlay is attached to a
    // map view that outlives recomposition: capturing the callback directly
    // would leave the map for ever calling whichever one existed when it was
    // first built.
    val longPress by rememberUpdatedState(onLongPress)

    // Same reason, one step removed: the waypoint markers are rebuilt only
    // when the waypoints themselves change, so a listener that captured the
    // callback directly would go on answering with whatever the trip looked
    // like the last time a stop was added or removed.
    val waypointTap by rememberUpdatedState(onWaypointTap)
    DisposableEffect(mapView) {
        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

            override fun longPressHelper(p: GeoPoint?): Boolean {
                val point = p ?: return false
                longPress(LatLng(point.latitude, point.longitude))
                return true
            }
        })
        // Bottom of the pile: osmdroid offers a gesture to the topmost overlay
        // first, so pins and flags get their refusal in before a press counts
        // as one on open map.
        mapView.overlays.add(0, events)
        onDispose { mapView.overlays.remove(events) }
    }

    // The flags fall back to a word when the owner set a coordinate but no
    // name, which is what dropping one on the map gives you.
    val originFallback = stringResource(R.string.map_origin)
    val destinationFallback = stringResource(R.string.map_destination)
    var framedOnRiders by remember { mutableStateOf(false) }
    var framedOnMe by remember { mutableStateOf(false) }

    // osmdroid's MapView keeps a tile-download thread pool and a location
    // client alive; without pausing it the map keeps working from behind
    // another screen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // No scrim: the map is the thing being read, and every layer over it costs
    // contrast on the road names.
    //
    // ## Why the clip is load-bearing
    //
    // `AndroidView` does not clip the view it hosts to the bounds Compose
    // measured for it, and osmdroid paints *whole tiles*. A 256px tile row is
    // ~93dp at xhdpi, and the topmost row is aligned to the tile grid rather
    // than to the top edge of the view — so the map paints up to a tile above
    // where it ends, over whatever Compose drew there first.
    //
    // Nothing was ever drawn there until the places screen put a search bar
    // above the map inside the same Column. The trip map has the map first,
    // with its header drawn *after* the column and therefore on top, so this
    // was invisible for as long as there was only one map screen. On the
    // places screen it painted over all but the top ~8dp of a 46dp search bar
    // and the bug arrived as "the search box renders empty" — a white pill
    // with no icon and no placeholder, because the icon and the placeholder
    // are vertically centred and the overdraw started above them.
    //
    // Here rather than on the caller so that the next screen to put anything
    // above a map does not have to know this.
    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize().clipToBounds(),
    )

    // Pins: added, removed, and then slid to where their rider now is.
    LaunchedEffect(members) {
        val targets = syncMarkers(mapView, members, markers, drawn, onMarkerTap)
        slideMarkers(mapView, markers, drawn, targets)
    }

    // Waypoints are rebuilt wholesale rather than diffed and animated: a stop
    // does not move, so there is never anything to slide, and there are few
    // enough of them that redrawing costs nothing.
    LaunchedEffect(waypoints) {
        syncWaypoints(mapView, waypoints, waypointMarkers) { waypointTap(it) }
    }

    // The two ends of the trip. They change only when the owner edits them, so
    // they are keyed on the pair rather than redrawn with every poll.
    LaunchedEffect(origin, destination) {
        syncEndpoints(
            view = mapView,
            origin = origin,
            destination = destination,
            drawn = endpointMarkers,
            originFallback = originFallback,
            destinationFallback = destinationFallback,
        )
    }

    // The road between them, when the server managed to work one out. Changes
    // only when the ends do, so it is keyed on the line itself.
    LaunchedEffect(route) {
        syncRouteLine(mapView, route, routeLines)
    }

    // The opening camera: it follows this phone's own position while the trip
    // has no fixes yet, then frames the group once, for good, the first time
    // anybody has reported. After that the camera belongs to whoever is
    // dragging it.
    LaunchedEffect(members, myLocation) {
        if (framedOnRiders) return@LaunchedEffect

        val points = members.mapNotNull { it.latLng }
        // Nothing new to frame on: no rider has reported, and this phone's own
        // position has either not arrived or has already been used. Framing on
        // it again every few seconds would drag the map out from under anyone
        // panning an empty one.
        if (points.isEmpty() && (myLocation == null || framedOnMe)) return@LaunchedEffect

        // Fitting needs a measured view: the zoom that frames a box depends
        // on how many pixels there are to frame it in, and before layout
        // there are none.
        var frames = 0
        while ((mapView.width == 0 || mapView.height == 0) && frames < LAYOUT_WAIT_FRAMES) {
            withFrameNanos { }
            frames += 1
        }

        applyCamera(mapView, initialCamera(points, myLocation))
        if (points.isNotEmpty()) framedOnRiders = true else framedOnMe = true
    }

    // Panning follows an explicit request, never the data: re-centring on
    // every poll would fight a rider who has dragged the map somewhere.
    LaunchedEffect(focus) {
        val target = focus ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(target.lat, target.lng), FOCUS_ZOOM, null)
    }

    // A finger on the map ends whatever the camera was doing.
    //
    // A touch listener rather than osmdroid's `onScroll`, which cannot tell a
    // drag from the app's own `animateTo` — following would then cancel itself
    // on the first position it moved to. `false` so the map still handles the
    // gesture; this only watches.
    val userPanned by rememberUpdatedState(onUserPan)
    DisposableEffect(mapView) {
        mapView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) userPanned()
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Following. Animated rather than snapped, so the map reads as travelling
    // with the rider rather than jumping under them, and only ever to a
    // position the caller has decided should be followed.
    LaunchedEffect(follow) {
        val target = follow ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(target.lat, target.lng), FOLLOW_ZOOM, null)
    }

    // The overview, applied once per request. Keyed on the whole object, which
    // carries a sequence number for exactly this reason: asking for the same
    // overview twice has to move the camera twice, and two identical boxes are
    // equal.
    LaunchedEffect(overview) {
        val requested = overview ?: return@LaunchedEffect
        var frames = 0
        while ((mapView.width == 0 || mapView.height == 0) && frames < LAYOUT_WAIT_FRAMES) {
            withFrameNanos { }
            frames += 1
        }
        mapView.controller.animateTo(
            GeoPoint(requested.bounds.centre().lat, requested.bounds.centre().lng),
            fitZoom(requested.bounds, mapView.width, mapView.height),
            null,
        )
    }
}

/**
 * A request to frame a box, with a sequence number so that asking twice for
 * the same box moves the camera twice.
 *
 * The same shape and the same reason as [MapFocus]: a rider who has panned
 * away and taps overview again expects it to work the second time.
 */
data class MapOverview(val bounds: Bounds, val sequence: Int)

/**
 * Points the camera as [target] asks, fitting a box when there is one.
 *
 * The fit is arithmetic this app owns ([fitZoom]) rather than a call to
 * osmdroid's `zoomToBoundingBox`. That method adds a margin of its own to the
 * box it is given and then rounds the zoom down a step, and since
 * [initialCamera] already leaves a margin, the two compounded: the map opened
 * on a view several times wider than the riders on it, and every rider's
 * first act was to pinch in.
 */
private fun applyCamera(view: MapView, target: CameraTarget) {
    val bounds = target.bounds
    if (bounds != null) {
        view.controller.setZoom(fitZoom(bounds, view.width, view.height))
        view.controller.setCenter(GeoPoint(target.centre.lat, target.centre.lng))
        return
    }
    view.controller.setZoom(target.zoom ?: SOLO_ZOOM)
    view.controller.setCenter(GeoPoint(target.centre.lat, target.centre.lng))
}

/**
 * Brings the map's markers in line with [members], and returns where each one
 * should end up.
 *
 * A rider seen for the first time is placed at their position outright — there
 * is nowhere to slide from, and animating in from a previous position they
 * never had would be a lie about where they have been.
 */
private fun syncMarkers(
    view: MapView,
    members: List<MemberPosition>,
    markers: MutableMap<Long, Marker>,
    drawn: MutableMap<Long, LatLng>,
    onMarkerTap: (Long) -> Unit,
): Map<Long, LatLng> {
    val placed = members.mapNotNull { member -> member.latLng?.let { member to it } }
    val present = placed.map { it.first.userId }.toSet()

    (markers.keys - present).toList().forEach { userId ->
        markers.remove(userId)?.let { view.overlays.remove(it) }
        drawn.remove(userId)
    }

    placed.forEach { (member, point) ->
        val existing = markers[member.userId]
        val marker = existing ?: Marker(view).also {
            markers[member.userId] = it
            view.overlays.add(it)
        }
        // The icon carries the rider's name, so it is refreshed on every sync:
        // a rider who sets a username mid-ride gets it on their pin.
        marker.icon = RiderMarker.forRider(member.userId, member.label, view.resources)
        marker.setAnchor(Marker.ANCHOR_CENTER, RiderMarker.anchorV(view.resources))
        marker.title = member.label
        marker.setOnMarkerClickListener { _, _ ->
            onMarkerTap(member.userId)
            // Consumed: the name is on the pin already, so osmdroid's info
            // window would be a bubble repeating it over the map.
            true
        }
        if (existing == null) {
            marker.position = GeoPoint(point.lat, point.lng)
            drawn[member.userId] = point
        }
    }

    view.invalidate()
    return placed.associate { it.first.userId to it.second }
}

/**
 * Draws the road between the trip's two ends.
 *
 * ## Two lines, not one
 *
 * The route is drawn twice: a wide white casing, then the orange fill on top
 * of it. That is the same trick the progress bar down the edge of the map uses
 * and it is here for the same reason — an OpenStreetMap daylight tile is pale,
 * busy and full of coloured roads, and a single coloured line laid over it
 * disappears the moment it crosses a motorway that happens to be drawn in a
 * similar colour. With a casing the fill only ever has to contrast with its
 * own outline.
 *
 * ## Underneath everything else
 *
 * Inserted at the bottom of the overlay list, below the long-press receiver's
 * neighbours and every marker: the route is context, and a rider tapping a pin
 * that happens to sit on the road must get the pin. It is also removed and
 * re-added wholesale, which is cheap — this changes when the trip's ends do,
 * which is a handful of times a ride.
 */
private fun syncRouteLine(
    view: MapView,
    route: List<LatLng>,
    drawn: MutableList<Polyline>,
) {
    drawn.forEach { view.overlays.remove(it) }
    drawn.clear()

    if (route.size < 2) {
        view.invalidate()
        return
    }

    val points = route.map { GeoPoint(it.lat, it.lng) }
    val casing = Polyline(view).apply {
        setPoints(points)
        outlinePaint.color = AppRouteProgressCasing.toArgb()
        outlinePaint.strokeWidth = ROUTE_CASING_WIDTH_PX
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        // The route is not a control. Without this osmdroid swallows taps that
        // land on it and pops its own info window over the map.
        setOnClickListener { _, _, _ -> false }
    }
    val fill = Polyline(view).apply {
        setPoints(points)
        outlinePaint.color = AppRouteProgress.toArgb()
        outlinePaint.strokeWidth = ROUTE_LINE_WIDTH_PX
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        setOnClickListener { _, _, _ -> false }
    }

    // Casing first so the fill lands on top of it, and both at the bottom of
    // the pile so no marker is ever covered.
    view.overlays.add(0, fill)
    view.overlays.add(0, casing)
    drawn += casing
    drawn += fill
    view.invalidate()
}

/** The coloured part of the drawn route, in pixels. */
private const val ROUTE_LINE_WIDTH_PX = 12f

/** Its white casing, wide enough to stand clear on both sides. */
private const val ROUTE_CASING_WIDTH_PX = 20f

/**
 * Draws the trip's waypoints, replacing whatever was drawn before.
 *
 * They are added to the map *before* the rider markers in z-order terms only
 * incidentally — osmdroid draws overlays in list order, and since these are
 * removed and re-added on each change they end up on top. That is acceptable:
 * a stop is a fixed thing a rider is looking for, and a pin passing over it
 * for a second and a half is the transient one.
 */
private fun syncWaypoints(
    view: MapView,
    waypoints: List<Waypoint>,
    drawn: MutableList<Marker>,
    onTap: (Waypoint) -> Unit,
) {
    drawn.forEach { view.overlays.remove(it) }
    drawn.clear()

    waypoints.forEach { waypoint ->
        val marker = Marker(view).apply {
            position = GeoPoint(waypoint.lat, waypoint.lng)
            setAnchor(Marker.ANCHOR_CENTER, WaypointMarker.anchorV(view.resources))
            icon = WaypointMarker.forWaypoint(waypoint, view.resources)
            title = waypoint.name
            // A stop is not a rider, so there is no row to highlight and the
            // name is already drawn on the pin. What a tap *is* good for is
            // taking it away again; the screen decides whether this rider is
            // allowed to, and says nothing if not.
            setOnMarkerClickListener { _, _ ->
                onTap(waypoint)
                true
            }
        }
        drawn.add(marker)
        view.overlays.add(marker)
    }

    view.invalidate()
}

/**
 * Draws the trip's origin and destination, replacing whatever was drawn before.
 *
 * Both are optional and independent: a group that set off from a known place
 * with no plan gets one flag, and a trip nobody has filled in gets none. A
 * flag with a coordinate but no name still needs *something* under it, so it
 * falls back to the word for which end it is.
 */
private fun syncEndpoints(
    view: MapView,
    origin: TripEndpoint?,
    destination: TripEndpoint?,
    drawn: MutableList<Marker>,
    originFallback: String,
    destinationFallback: String,
) {
    drawn.forEach { view.overlays.remove(it) }
    drawn.clear()

    val ends = listOfNotNull(
        origin?.let { Triple(EndpointMarker.Kind.ORIGIN, it, originFallback) },
        destination?.let { Triple(EndpointMarker.Kind.DESTINATION, it, destinationFallback) },
    )

    ends.forEach { (kind, endpoint, fallback) ->
        val marker = Marker(view).apply {
            position = GeoPoint(endpoint.lat, endpoint.lng)
            setAnchor(Marker.ANCHOR_CENTER, EndpointMarker.anchorV(view.resources))
            icon = EndpointMarker.forEndpoint(
                kind = kind,
                label = endpoint.label?.takeIf { it.isNotBlank() } ?: fallback,
                resources = view.resources,
            )
            title = endpoint.label ?: fallback
            // Fixed geography: there is no row to highlight and nothing to say
            // that the flag is not already saying.
            setOnMarkerClickListener { _, _ -> true }
        }
        drawn.add(marker)
        view.overlays.add(marker)
    }

    view.invalidate()
}

/**
 * Slides every moved marker to its new position over [MarkerMotion.DURATION_MS].
 *
 * Driven off the frame clock rather than a fixed tick, so it takes the same
 * wall-clock time on a phone dropping frames as on one that isn't. Cancelling
 * the effect mid-slide leaves the pins wherever they got to and the next
 * update carries on from there, which is why [drawn] is only written at the
 * end of a completed leg.
 */
private suspend fun slideMarkers(
    view: MapView,
    markers: Map<Long, Marker>,
    drawn: MutableMap<Long, LatLng>,
    targets: Map<Long, LatLng>,
) {
    val moving = targets.filter { (userId, target) ->
        val from = drawn[userId]
        from != null && from != target
    }
    if (moving.isEmpty()) return

    val from = moving.keys.associateWith { drawn.getValue(it) }
    val startNanos = withFrameNanos { it }

    var fraction = 0f
    while (fraction < 1f) {
        val nanos = withFrameNanos { it }
        fraction = ((nanos - startNanos) / 1_000_000.0 / MarkerMotion.DURATION_MS)
            .coerceIn(0.0, 1.0)
            .toFloat()

        moving.forEach { (userId, target) ->
            val point = MarkerMotion.at(from.getValue(userId), target, fraction)
            markers[userId]?.position = GeoPoint(point.lat, point.lng)
        }
        view.invalidate()
    }

    moving.forEach { (userId, target) -> drawn[userId] = target }
}

/** One rider under the map: their colour, name, level, speed and battery. */
@Composable
private fun MemberMapRow(
    member: MemberPosition,
    levelName: String?,
    fixAgeMinutes: Long?,
    isSelf: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (focused) AppPrimarySoft else AppSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The same colour as this rider's pin, from the same function.
        HudDot(color = if (member.hasPosition) riderColor(member.userId) else AppTextMuted)

        // The badge appears only once the levels call has landed. Reserving
        // space for one that may never arrive would leave a hole in every row
        // on a trip where the request failed.
        levelName?.let { RankIcon(levelName = it, iconSize = 22.dp) }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                // riderLabel, via MemberPosition.label: a rider's own handle
                // wherever they set one, and the same string on their pin.
                text = if (isSelf) {
                    stringResource(R.string.map_you, member.label)
                } else {
                    member.label
                },
                style = MaterialTheme.typography.titleMedium,
                color = AppText,
            )
            Text(
                text = buildString {
                    levelName?.let {
                        append(it)
                        append(" · ")
                    }
                    append(
                        when {
                            !member.hasPosition -> stringResource(R.string.map_no_position)
                            !member.isSharing -> stringResource(R.string.map_last_seen)
                            else -> stringResource(R.string.sharing_on)
                        }
                    )
                    // How old the fix is. Positions arrive on a cadence, not
                    // continuously, so a pin a cycle behind is normal — without
                    // this the rider cannot tell that from a dead app.
                    fixAgeMinutes?.let { minutes ->
                        append(" · ")
                        append(
                            if (minutes < 1) {
                                stringResource(R.string.map_fix_age_now)
                            } else {
                                stringResource(R.string.map_fix_age_minutes, minutes)
                            }
                        )
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
            )
        }

        // Only for riders who have actually reported: a dash against somebody
        // who has never been on the map reads as "stopped", which is wrong.
        if (member.hasPosition) {
            Text(
                text = speedText(Speed.kmh(member.speedMps)),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
            )
        }

        // The same component the member list uses, deliberately: the two
        // screens each drew their own bare "$it%" once, which is the seam the
        // battery readings drifted apart along.
        member.batteryPct?.let { HudBatteryReadout(percent = it) }
    }
}
