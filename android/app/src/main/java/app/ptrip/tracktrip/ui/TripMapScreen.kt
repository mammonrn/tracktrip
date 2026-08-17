package app.ptrip.tracktrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.ptrip.tracktrip.map.MapConfig
import app.ptrip.tracktrip.map.RiderMarker
import app.ptrip.tracktrip.ui.theme.HudBlack
import app.ptrip.tracktrip.ui.theme.HudCyan
import app.ptrip.tracktrip.ui.theme.HudDivider
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudIconButton
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPinIcon
import app.ptrip.tracktrip.ui.theme.HudText
import app.ptrip.tracktrip.ui.theme.HudTextDim
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.riderColor
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/**
 * A point the map should move to, and a sequence number so that asking for the
 * same point twice still moves it.
 *
 * Tapping "centre on me" after dragging the map away has to work the second
 * time as well as the first, and without the counter the state would be equal
 * to what it already was and nothing would happen.
 */
data class MapFocus(val lat: Double, val lng: Double, val sequence: Int)

/** Chiang Mai. Somewhere to point the map before anyone has reported. */
private val FALLBACK_CENTRE = GeoPoint(18.7883, 98.9853)
private const val DEFAULT_ZOOM = 13.0
private const val FOCUS_ZOOM = 16.0

/**
 * Where everyone on the trip is.
 *
 * Map on top, every member listed underneath — not a map you have to tap pin
 * by pin to read. The two halves are wired together: tapping a rider in the
 * list moves the map to them, and tapping their pin highlights their row.
 */
@Composable
fun TripMapScreen(
    state: TripMapUiState,
    currentUserId: Long?,
    centreOn: MapFocus?,
    onRefresh: () -> Unit,
    onCenterOnMe: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf<Long?>(null) }
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

    Column(modifier = modifier.fillMaxSize()) {
        HudTopBar(
            title = state.trip?.name ?: stringResource(R.string.map_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
            subtitle = stringResource(R.string.map_riders_placed, state.placed.size, state.members.size),
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        state.error?.let { HudError(it, modifier = Modifier.padding(horizontal = 16.dp)) }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            RiderMap(
                members = state.placed,
                focus = focus,
                onMarkerTap = { focused = it },
            )

            HudIconButton(
                onClick = onCenterOnMe,
                contentDescription = stringResource(R.string.map_center_on_me),
                icon = { HudPinIcon(tint = HudCyan) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .background(HudBlack.copy(alpha = 0.75f), CircleShape),
            )
        }

        HudDivider()

        if (state.loading && state.members.isEmpty()) {
            HudLoading()
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(MEMBER_LIST_WEIGHT)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
                items(state.members, key = { it.userId }) { member ->
                    MemberMapRow(
                        member = member,
                        isSelf = member.userId == currentUserId,
                        focused = member.userId == focused,
                        onClick = { if (member.hasPosition) focused = member.userId },
                    )
                }
            }
        }
    }
}

/** The list gets about a third of the screen; the map keeps the rest. */
private const val MEMBER_LIST_WEIGHT = 0.55f

/**
 * The osmdroid map, wrapped for Compose.
 *
 * Markers are rebuilt on every update rather than diffed. There are as many of
 * them as there are people on a trip, and rebuilding is what keeps a rider who
 * has just moved from being drawn at their old position.
 */
@Composable
private fun RiderMap(
    members: List<MemberPosition>,
    focus: MapFocus?,
    onMarkerTap: (Long) -> Unit,
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
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(FALLBACK_CENTRE)

            // OpenStreetMap serves one style of tile and it is a daylight one.
            // Rather than run a tile server to get a dark map, the standard
            // tiles are put through osmdroid's own night-mode colour matrix —
            // which is what its INVERT_COLORS constant is for — and then
            // toned towards the app's navy by the scrim below. No custom
            // tiles, no second server, and the labels stay readable.
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.overlays.removeAll { it is Marker }
                members.forEach { member ->
                    val lat = member.lat ?: return@forEach
                    val lng = member.lng ?: return@forEach
                    view.overlays.add(
                        Marker(view).apply {
                            position = GeoPoint(lat, lng)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            icon = RiderMarker.forRider(member.userId, view.resources)
                            title = member.label
                            setOnMarkerClickListener { _, _ ->
                                onMarkerTap(member.userId)
                                true
                            }
                        }
                    )
                }
                view.invalidate()
            },
        )

        // A thin navy wash over the tiles, so the map sits in the same world
        // as the rest of the app instead of glowing out of it. Light enough
        // to read a road name through.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HudBlack.copy(alpha = 0.18f))
        )
    }

    // Panning follows an explicit request, never the data: re-centring on
    // every poll would fight a rider who has dragged the map somewhere.
    LaunchedEffect(focus) {
        val target = focus ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(target.lat, target.lng), FOCUS_ZOOM, null)
    }
}

/** One rider under the map: their colour, their name, and how current they are. */
@Composable
private fun MemberMapRow(
    member: MemberPosition,
    isSelf: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (focused) HudCyan.copy(alpha = 0.08f) else HudBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The same colour as this rider's pin, from the same function.
        HudDot(color = if (member.hasPosition) riderColor(member.userId) else HudTextDim)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isSelf) {
                    stringResource(R.string.map_you, member.label)
                } else {
                    member.label
                },
                style = MaterialTheme.typography.titleMedium,
                color = HudText,
            )
            Text(
                text = when {
                    !member.hasPosition -> stringResource(R.string.map_no_position)
                    !member.isSharing -> stringResource(R.string.map_last_seen)
                    else -> stringResource(R.string.sharing_on)
                },
                style = MaterialTheme.typography.labelSmall,
                color = HudTextDim,
            )
        }

        member.batteryPct?.let {
            Text(
                text = "$it%",
                style = MaterialTheme.typography.labelSmall,
                color = HudTextDim,
            )
        }
    }
}
