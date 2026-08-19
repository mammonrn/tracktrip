package app.ptrip.tracktrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.LiveCadence
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.Bounds
import app.ptrip.tracktrip.map.CameraRules
import app.ptrip.tracktrip.map.CameraTarget
import app.ptrip.tracktrip.map.FOLLOW_ZOOM
import app.ptrip.tracktrip.map.MapCamera
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
import app.ptrip.tracktrip.data.Place
import app.ptrip.tracktrip.data.PlaceSearchProblem
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.RankIcon
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
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

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
 * [name] is empty for a point pressed and held on the map — there is nothing
 * to call it yet — and carries the place's own name when it came out of the
 * search box, which is the entire saving of typing that a search offers.
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
     * the map "Mae Hong Son" is. Both gestures end in the same dialog.
     */
    searchState: PlaceSearchState = PlaceSearchState(),
    onSearchQueryChanged: (String) -> Unit = {},
    onSearchCleared: () -> Unit = {},
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

    // Whether the search panel is open. A panel rather than a box that is
    // always there: it covers the top of the route-progress bar while it is
    // open, and a rider who is riding rather than planning is not typing.
    var searching by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Waypoint?>(null) }

    // Who is driving the camera. See CameraRules — the rule that matters is
    // that a drag always wins, and nothing takes the map back on its own.
    var camera by remember(isSharingThisTrip) {
        mutableStateOf(CameraRules.initial(isSharingThisTrip))
    }
    var overview by remember { mutableStateOf<MapOverview?>(null) }
    var overviewSequence by remember { mutableIntStateOf(0) }

    val destination = state.trip?.destination?.let { LatLng(it.lat, it.lng) }

    // What "the whole journey" means: this rider, and the two ends of the trip
    // if they have been set. Not the other riders — the overview answers "how
    // far have I got", and a friend who set off from home three provinces away
    // would blow the frame out to nothing useful.
    val overviewPoints = listOfNotNull(
        myLocation,
        state.trip?.origin?.let { LatLng(it.lat, it.lng) },
        destination,
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

    // The top bar is *over* the map, not above it. As a row in the same column
    // it belonged to the scrolling layout and slid away the moment the map was
    // dragged; a rider then had no trip name and, worse, no way back.
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                RiderMap(
                    members = state.placed,
                    waypoints = state.waypoints.all,
                    trip = state.trip,
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
                        // Nothing to offer means nothing to open: a member
                        // looking at a finished ride can place neither end
                        // nor a stop, and a dialog with no buttons is worse
                        // than no dialog.
                        if (placementsAllowed(state.trip).isNotEmpty()) {
                            placing = PendingPlacement(point)
                        }
                    },
                    onWaypointTap = { waypoint ->
                        val allowed = MapPlacementRules.canRemoveWaypoint(
                            isOwner = state.trip?.isOwner == true,
                            addedBy = waypoint.addedBy,
                            currentUserId = currentUserId,
                        )
                        if (allowed && state.trip?.isActive == true) removing = waypoint
                    },
                    follow = myLocation.takeIf { CameraRules.followsPosition(camera) },
                    overview = overview,
                    onUserPan = { camera = CameraRules.afterPan() },
                )

                // How far there is left to go, down the right-hand edge.
                // Drawn only when there is something to measure — a trip with
                // a destination, and a rider with a position to measure from.
                RouteProgressBar(
                    fraction = RouteProgress.fraction(
                        origin = state.trip?.origin?.let { LatLng(it.lat, it.lng) },
                        destination = destination,
                        current = myLocation,
                    ),
                    remaining = RouteProgress.format(
                        RouteProgress.remainingKm(destination, myLocation)
                    ),
                    // Taller than it was: the old insets gave up 168dp of a
                    // screen the bar is the only thing on, and a short bar is
                    // a short answer to "how much is left". The bottom inset
                    // is what it is because the overview and centre-on-me
                    // buttons live under it in the same corner.
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            end = 12.dp,
                            top = PROGRESS_BAR_TOP_INSET,
                            bottom = PROGRESS_BAR_BOTTOM_INSET,
                        ),
                )

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
                    // The whole journey at once: this rider and both ends of
                    // the trip. Its own control rather than the default,
                    // because the default while riding is the road ahead —
                    // and it is hidden entirely when there is no route to take
                    // in, rather than sitting there doing nothing.
                    if (overviewPoints.size >= 2) {
                        HudIconButton(
                            onClick = {
                                overviewSequence += 1
                                boundsAround(overviewPoints)?.let {
                                    camera = MapCamera.OVERVIEW
                                    overview = MapOverview(it, overviewSequence)
                                }
                            },
                            contentDescription = stringResource(R.string.map_overview),
                            icon = { HudRouteIcon(tint = AppPrimary) },
                            modifier = Modifier
                                .background(AppSurface.copy(alpha = 0.92f), CircleShape),
                        )
                    }

                    HudIconButton(
                        onClick = {
                            // For a rider who is sharing, this means "follow
                            // me again" — which the follow effect does on its
                            // own the moment the camera says so, at the zoom
                            // following uses. Calling onCenterOnMe as well
                            // would animate to a different zoom first and
                            // fight it.
                            //
                            // For everyone else, and for a rider whose phone
                            // has not produced a position yet, it means the
                            // thing it has always meant: go and find me.
                            if (isSharingThisTrip && myLocation != null) {
                                camera = MapCamera.FOLLOW
                            } else {
                                onCenterOnMe()
                            }
                        },
                        contentDescription = stringResource(R.string.map_center_on_me),
                        icon = {
                            HudPinIcon(
                                tint = if (camera == MapCamera.FOLLOW) AppPrimary else AppTextMuted
                            )
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

        Column(modifier = Modifier.align(Alignment.TopStart)) {
            MapOverlayBar(
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
                // Offered only to a rider who has somewhere to put what they
                // find. On a finished trip a member can place nothing, and a
                // search whose every result opens a dialog with no buttons is
                // worse than no search.
                canSearch = placementsAllowed(state.trip).isNotEmpty(),
                searchOpen = searching,
                onToggleSearch = {
                    searching = !searching
                    if (!searching) onSearchCleared()
                },
            )

            if (searching && placementsAllowed(state.trip).isNotEmpty()) {
                PlaceSearchPanel(
                    state = searchState,
                    onQueryChanged = onSearchQueryChanged,
                    onClear = onSearchCleared,
                    onPick = { place ->
                        // Straight to the map as well as into the dialog: a
                        // rider deciding between two results wants to see
                        // where they are, not read two addresses.
                        sequence += 1
                        camera = MapCamera.FREE
                        focused = null
                        focus = MapFocus(place.lat, place.lng, sequence)
                        placing = PendingPlacement(LatLng(place.lat, place.lng), place.name)
                        searching = false
                    },
                    myLocation = myLocation,
                    hasLocationPermission = hasLocationPermission,
                    onUseCurrentLocation = { here ->
                        // The same ending as picking a search result — the
                        // one dialog, with the same three buttons — so the
                        // shortcut is a faster way to the existing flow
                        // rather than a second flow to learn.
                        //
                        // No name is prefilled. "My location" as a label
                        // would be a name a rider has to delete before typing
                        // the one they meant, and it means nothing on the map
                        // an hour later. The start and the finish take no
                        // label at all; a stop still asks for one, exactly as
                        // it does after a long press.
                        sequence += 1
                        camera = MapCamera.FREE
                        focused = null
                        focus = MapFocus(here.lat, here.lng, sequence)
                        placing = PendingPlacement(here)
                        searching = false
                    },
                )
            }
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
 * Type a place name, pick it off a list, and place it.
 *
 * ## Why this sits under the top bar rather than in the dialog
 *
 * The long press already had a dialog, and putting the search inside it would
 * mean pressing and holding somewhere on the map first — which is exactly the
 * thing a rider who does not know where the place is cannot do. So the box
 * opens from the header instead, and a result taken from it opens the *same*
 * dialog with the coordinate and the name already filled in.
 *
 * It opens rather than sitting there because it covers the top of the
 * route-progress bar while it is open, and a rider who is riding rather than
 * planning is not typing.
 *
 * Nothing about the long press changes. It is still the way to place a point
 * that has no name — the viewpoint you are standing at — and still the only
 * one that works with the server's search key unset.
 */
@Composable
private fun PlaceSearchPanel(
    state: PlaceSearchState,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    onPick: (Place) -> Unit,
    /**
     * Where this phone is, and whether it was allowed to know — the two
     * inputs to the shortcut pinned above the results. See
     * [CurrentLocationRules].
     */
    myLocation: LatLng?,
    hasLocationPermission: Boolean,
    onUseCurrentLocation: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppSurface.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            singleLine = true,
            label = { Text(stringResource(R.string.map_search_label)) },
            placeholder = { Text(stringResource(R.string.map_search_placeholder)) },
            trailingIcon = {
                // One slot, two jobs: the spinner while a request is out, and
                // a way to empty the box once there is something in it. They
                // never both apply — a search in flight has text behind it,
                // and the spinner is the more urgent thing to say.
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
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(AppSurface, AppSearchPanelShape)
                .border(1.dp, AppLine, AppSearchPanelShape),
        ) {
            // Pinned above everything, and there before a key is pressed:
            // most points a rider places are where they already are, and
            // making them type a name for the spot they are standing on — and
            // then wait on a geocoder to hand back a coordinate the phone has
            // had all along — is the long way round. Costs no request.
            CurrentLocationRow(
                location = myLocation,
                hasPermission = hasLocationPermission,
                onClick = onUseCurrentLocation,
            )

            if (!state.hasPanel) return@Column

            HudDivider()

            state.problem?.let { problem ->
                // Worded from the reason, not from the HTTP status. The
                // status's own wording for a 404 is "That's no longer there.",
                // which on a search box is a sentence about a place that has
                // closed down — and what a 404 here actually means is that the
                // server has no search route at all.
                Text(
                    text = placeSearchMessage(problem, state.serverMessage),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            if (state.isEmpty) {
                Text(
                    text = stringResource(R.string.map_search_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = SEARCH_RESULTS_MAX_HEIGHT)) {
                items(state.results, key = { it.key }) { place ->
                    PlaceSearchRow(place = place, onClick = { onPick(place) })
                }
            }
        }
    }
}

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

/** One result: what it is called, and where that is. */
@Composable
private fun PlaceSearchRow(place: Place, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = place.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = AppText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The full address, which is what tells two places with the same name
        // apart — and there are a great many 7-Elevens.
        Text(
            text = place.address,
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
                    text = stringResource(R.string.map_progress_straight_line),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
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

/**
 * How far the bar starts below the top of the map.
 *
 * Just clear of the floating header, which is about 68dp tall. The search
 * panel opens *below* that and would cover the top of the bar — which is why
 * it is a panel that opens rather than a box that is always there.
 */
private val PROGRESS_BAR_TOP_INSET = 76.dp

/**
 * How far the bar stops short of the bottom.
 *
 * A margin now, not a clearance: the controls that used to sit in this corner
 * moved to the other one precisely so this number could be small.
 */
private val PROGRESS_BAR_BOTTOM_INSET = 16.dp

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
    canSearch: Boolean = false,
    searchOpen: Boolean = false,
    onToggleSearch: () -> Unit = {},
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
                if (canSearch) {
                    HudIconButton(
                        onClick = onToggleSearch,
                        contentDescription = stringResource(R.string.map_search_places),
                        icon = {
                            HudSearchIcon(
                                tint = if (searchOpen) AppPrimary else AppTextMuted
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
private fun RiderMap(
    members: List<MemberPosition>,
    waypoints: List<Waypoint>,
    trip: Trip?,
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
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

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
    LaunchedEffect(trip?.origin, trip?.destination) {
        syncEndpoints(mapView, trip, endpointMarkers, originFallback, destinationFallback)
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
    trip: Trip?,
    drawn: MutableList<Marker>,
    originFallback: String,
    destinationFallback: String,
) {
    drawn.forEach { view.overlays.remove(it) }
    drawn.clear()

    val ends = listOfNotNull(
        trip?.origin?.let { Triple(EndpointMarker.Kind.ORIGIN, it, originFallback) },
        trip?.destination?.let { Triple(EndpointMarker.Kind.DESTINATION, it, destinationFallback) },
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

        member.batteryPct?.let {
            Text(
                text = "$it%",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
            )
        }
    }
}
