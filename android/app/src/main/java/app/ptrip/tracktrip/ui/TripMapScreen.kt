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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.CameraTarget
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
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppPrimarySoft
import app.ptrip.tracktrip.ui.theme.AppSurface
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf<Long?>(null) }

    // A point pressed and held on the map, waiting for the rider to say what
    // it is. Held here rather than in the view model: nothing has happened
    // yet, and a half-finished gesture is not state the server needs to know
    // about.
    var placing by remember { mutableStateOf<LatLng?>(null) }
    var removing by remember { mutableStateOf<Waypoint?>(null) }
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
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(TripMapViewModel.POLL_INTERVAL_MS)
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
                    onMarkerTap = { focused = it },
                    onLongPress = { point ->
                        // Nothing to offer means nothing to open: a member
                        // looking at a finished ride can place neither end
                        // nor a stop, and a dialog with no buttons is worse
                        // than no dialog.
                        if (placementsAllowed(state.trip).isNotEmpty()) placing = point
                    },
                    onWaypointTap = { waypoint ->
                        val allowed = MapPlacementRules.canRemoveWaypoint(
                            isOwner = state.trip?.isOwner == true,
                            addedBy = waypoint.addedBy,
                            currentUserId = currentUserId,
                        )
                        if (allowed && state.trip?.isActive == true) removing = waypoint
                    },
                )

                HudIconButton(
                    onClick = onCenterOnMe,
                    contentDescription = stringResource(R.string.map_center_on_me),
                    icon = { HudPinIcon(tint = AppPrimary) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(AppSurface.copy(alpha = 0.92f), CircleShape),
                )
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
                            onClick = { if (member.hasPosition) focused = member.userId },
                        )
                    }
                }
            }
        }

        MapOverlayBar(
            title = state.trip?.name ?: stringResource(R.string.map_title),
            subtitle = stringResource(
                R.string.map_riders_placed,
                state.placed.size,
                state.members.size,
            ),
            speedKmh = mySpeedKmh,
            error = state.error,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }

    placing?.let { point ->
        PlacePointDialog(
            point = point,
            options = placementsAllowed(state.trip),
            onPlace = { placement, name ->
                onPlace(placement, point, name)
                placing = null
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
) {
    var name by rememberSaveable { mutableStateOf("") }

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
    error: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
            subtitle = subtitle,
            trailing = {
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
}

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
