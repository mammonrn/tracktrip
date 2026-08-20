package app.ptrip.tracktrip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.PersonalPlace
import app.ptrip.tracktrip.data.SharedPlace
import app.ptrip.tracktrip.data.Waypoint
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.ui.theme.AppLine
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppSurface
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudConfirmDialog
import app.ptrip.tracktrip.ui.theme.HudDivider
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudIconButton
import app.ptrip.tracktrip.ui.theme.HudPinIcon
import app.ptrip.tracktrip.ui.theme.HudSearchIcon
import app.ptrip.tracktrip.ui.theme.HudTopBar
import kotlinx.coroutines.launch

/**
 * The map with no trip behind it.
 *
 * ## What this is for
 *
 * Two things a rider could previously only do from inside a ride: look a place
 * up, and write one down. Both are things people think of when they are not
 * riding — the petrol station nobody could find last weekend is worth adding
 * on a Tuesday evening — and until now the only way in was to open a trip and
 * pretend to plan a route on it.
 *
 * ## What it deliberately is not
 *
 * Not a ride. No riders, no positions, no position reporting, no route, no
 * poll and no live feed. Everything that makes the trip map expensive is about
 * the ride rather than about the map, and none of it means anything here.
 *
 * What it shares with the trip map is shared as pieces rather than as a mode:
 * the same [RiderMap], the same [PlaceSearchScreen], the same
 * [SavePlaceDialog], the same long press. A rider who has learned the gesture
 * on one screen already knows it on the other.
 */
@Composable
fun PlacesMapScreen(
    state: PlacesUiState,
    searchState: PlaceSearchState,
    myLocation: LatLng?,
    /**
     * Where to put the camera, when something outside this screen has decided.
     *
     * The "centre on me" button's answer, which arrives late: the button has
     * to go and ask the phone where it is, and [myLocation] on its own cannot
     * carry the difference between "here is a fix" and "here is a fix *and*
     * the rider just asked to be taken to it". The sequence number is what
     * makes pressing it twice move the map twice.
     */
    centreOn: MapFocus?,
    /**
     * Whether the phone is being asked where it is *right now*, because this
     * rider pressed the button.
     *
     * The press is answered by the phone, not by this screen, and a cold
     * provider can take the better part of `LocationFix.QUICK_TIMEOUT_MS` to
     * answer — fifteen seconds in which the old button did not change by a
     * single pixel. A rider who cannot tell "asking" from "broken" presses it
     * again, and then stops pressing it.
     */
    centringOnMe: Boolean,
    hasLocationPermission: Boolean,
    currentUserId: Long?,
    onSearchQueryChanged: (String) -> Unit,
    onSearchCleared: () -> Unit,
    onAddShared: suspend (String, LatLng) -> Boolean,
    onAddPersonal: suspend (String, String, LatLng) -> Boolean,
    onRemoveShared: (Long) -> Unit,
    onRemovePersonal: (Long) -> Unit,
    onDismissError: () -> Unit,
    /**
     * Go and find this rider — asking for the location permission first if
     * this screen has never had it. The answer comes back as [centreOn].
     */
    onCenterOnMe: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Whether the full-screen picker is up. There is no route row to fill
    // here, so it opens to *look* — tapping a result moves the map to it
    // rather than putting it anywhere.
    var searching by rememberSaveable { mutableStateOf(false) }

    // A point pressed and held, waiting to be named and filed.
    var namingPlace by remember { mutableStateOf<PendingPlacement?>(null) }
    var savingPlace by remember { mutableStateOf(false) }
    var failedToSavePlace by remember { mutableStateOf(false) }

    var removingShared by remember { mutableStateOf<SharedPlace?>(null) }
    var removingPersonal by remember { mutableStateOf<PersonalPlace?>(null) }

    var focus by remember { mutableStateOf<MapFocus?>(null) }
    var sequence by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val moveTo: (LatLng) -> Unit = { point ->
        sequence += 1
        focus = MapFocus(point.lat, point.lng, sequence)
    }

    // Centring from outside, the same way the trip map does it: the button
    // asks the phone where it is, and the answer arrives here rather than
    // being read out of `myLocation` at the moment of the press — which on a
    // screen that had never asked for the permission was always null.
    LaunchedEffect(centreOn) {
        val target = centreOn ?: return@LaunchedEffect
        moveTo(LatLng(target.lat, target.lng))
    }

    BackHandler(enabled = searching) {
        searching = false
        onSearchCleared()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Padded like every other header in the app.
            //
            // The padding is the caller's job here rather than HudTopBar's:
            // every screen with one wraps it in a column that is already
            // inset by 16dp, and this was the only screen that could not,
            // because the map underneath is deliberately full-bleed. The
            // result was a back button and a title flush against the edge of
            // the glass, over a search bar that was not.
            HudTopBar(
                title = stringResource(R.string.places_title),
                subtitle = stringResource(R.string.places_drop_hint),
                onBack = onBack,
                backContentDescription = stringResource(R.string.back),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            state.error?.let { message ->
                HudError(message = message, modifier = Modifier.padding(horizontal = 16.dp))
            }

            // A bar rather than a field: tapping it opens the same full-screen
            // picker the trip map uses, which is where the typing belongs.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .background(AppSurface, PlacesBarShape)
                    .border(1.dp, AppLine, PlacesBarShape)
                    .clickable {
                        onDismissError()
                        searching = true
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                HudSearchIcon(tint = AppTextMuted)
                Text(
                    text = stringResource(R.string.places_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextMuted,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                RiderMap(
                    members = emptyList(),
                    // Every place this rider can act on, drawn as pins so the
                    // list below and the map agree about what exists.
                    waypoints = placePins(state),
                    origin = null,
                    destination = null,
                    route = emptyList(),
                    myLocation = myLocation,
                    focus = focus,
                    onMarkerTap = {},
                    onLongPress = { point ->
                        namingPlace = PendingPlacement(point)
                        failedToSavePlace = false
                    },
                    onWaypointTap = { pin ->
                        // The pins are drawn from the two lists below, so a
                        // tap can go straight to the one it belongs to.
                        sharedPinId(pin.id)?.let { id ->
                            removingShared = state.mine.firstOrNull { it.id == id }
                        }
                        personalPinId(pin.id)?.let { id ->
                            removingPersonal = state.personal.firstOrNull { it.id == id }
                        }
                    },
                    follow = null,
                    overview = null,
                    onUserPan = {},
                )

                // Go and find me.
                //
                // The trip map has had this since there was a trip map, and
                // splitting this screen off left it behind — so the only way
                // to a rider's own position here was to open the search and
                // pick "use my current location", which is a picker two taps
                // deep for the one place they are definitely standing.
                //
                // One state, not the trip map's two: there is no route to fit
                // and nothing to follow on a screen with no ride on it, so the
                // button always means the same thing and always looks the
                // same. Tinted only once there is a fix — grey says "I would
                // have to go and ask", which is exactly what pressing it then
                // does, permission dialog and all.
                //
                // While the phone is being asked, the pin is replaced by a
                // spinner and the button stops taking presses. Both halves
                // matter: the spinner is the only thing on the screen that
                // says the press landed, and without the guard a rider who
                // pressed three times queued three location requests, each
                // with its own fifteen-second timeout, to answer a question
                // that had already been asked.
                HudIconButton(
                    onClick = { if (!centringOnMe) onCenterOnMe() },
                    contentDescription = stringResource(
                        if (centringOnMe) R.string.map_finding_me
                        else R.string.map_center_on_me
                    ),
                    icon = {
                        if (centringOnMe) {
                            CircularProgressIndicator(
                                color = AppPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            HudPinIcon(
                                tint = if (myLocation != null) AppPrimary else AppTextMuted
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .background(AppSurface.copy(alpha = 0.92f), CircleShape),
                )
            }

            HudDivider()

            MyPlacesList(
                state = state,
                onShowShared = { moveTo(LatLng(it.lat, it.lng)) },
                onShowPersonal = { moveTo(it.point) },
                onRemoveShared = { removingShared = it },
                onRemovePersonal = { removingPersonal = it },
            )
        }

        if (searching) {
            PlaceSearchScreen(
                state = searchState,
                heading = stringResource(R.string.places_search_hint),
                onQueryChanged = onSearchQueryChanged,
                onClear = onSearchCleared,
                onBack = {
                    searching = false
                    onSearchCleared()
                },
                onPick = { place ->
                    // Nothing to fill in here, so a result is somewhere to
                    // look: the map goes to it and the picker closes.
                    moveTo(LatLng(place.lat, place.lng))
                    searching = false
                    onSearchCleared()
                },
                myLocation = myLocation,
                hasLocationPermission = hasLocationPermission,
                onUseCurrentLocation = { here ->
                    moveTo(here)
                    searching = false
                    onSearchCleared()
                },
                currentUserId = currentUserId,
                onAddPlace = { typed ->
                    // Straight to the naming dialog on the rider's own
                    // position when there is one, because on this screen there
                    // is no row waiting and no reason to make them point twice
                    // at somewhere they already are. Without a fix, the picker
                    // closes and the long press is the way in — which the top
                    // bar says.
                    searching = false
                    onSearchCleared()
                    myLocation?.let { namingPlace = PendingPlacement(it, typed.trim()) }
                },
                onRemoveSharedPlace = { place ->
                    removingShared = state.mine.firstOrNull { it.id == place.sharedId }
                },
                personalPlaces = state.personal,
                onPickPersonal = { saved ->
                    moveTo(saved.point)
                    searching = false
                    onSearchCleared()
                },
            )
        }
    }

    namingPlace?.let { pending ->
        SavePlaceDialog(
            point = pending.point,
            suggestedName = pending.name,
            canSavePersonal = true,
            saving = savingPlace,
            error = state.error.takeIf { !savingPlace && failedToSavePlace },
            onSave = { visibility, name, label ->
                savingPlace = true
                scope.launch {
                    val saved = when (visibility) {
                        PlaceVisibility.SHARED -> onAddShared(name, pending.point)
                        PlaceVisibility.PERSONAL -> onAddPersonal(label, name, pending.point)
                    }
                    savingPlace = false
                    if (!saved) {
                        failedToSavePlace = true
                        return@launch
                    }
                    failedToSavePlace = false
                    namingPlace = null
                }
            },
            onDismiss = {
                namingPlace = null
                failedToSavePlace = false
            },
        )
    }

    removingShared?.let { place ->
        HudConfirmDialog(
            title = stringResource(R.string.map_shared_place_remove_title),
            message = stringResource(R.string.map_shared_place_remove_message, place.name),
            confirmText = stringResource(R.string.map_remove_point),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                onRemoveShared(place.id)
                removingShared = null
            },
            onDismiss = { removingShared = null },
        )
    }

    removingPersonal?.let { place ->
        HudConfirmDialog(
            title = stringResource(R.string.map_personal_place_remove_title),
            message = stringResource(R.string.map_personal_place_remove_message, place.label),
            confirmText = stringResource(R.string.map_remove_point),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                onRemovePersonal(place.id)
                removingPersonal = null
            },
            onDismiss = { removingPersonal = null },
        )
    }
}

/**
 * The two lists, under the map, headed so they can never be mistaken for one
 * another.
 *
 * The headings are the whole point of drawing them as two sections rather than
 * one merged list: everything under "Shared with everyone" is visible to the
 * whole server and everything under "Only you" is visible to nobody, and a
 * rider tidying up needs to know which is which without tapping anything.
 */
@Composable
private fun MyPlacesList(
    state: PlacesUiState,
    onShowShared: (SharedPlace) -> Unit,
    onShowPersonal: (PersonalPlace) -> Unit,
    onRemoveShared: (SharedPlace) -> Unit,
    onRemovePersonal: (PersonalPlace) -> Unit,
) {
    LazyColumn(modifier = Modifier.heightIn(max = PLACES_LIST_MAX_HEIGHT).fillMaxWidth()) {
        item {
            Text(
                text = stringResource(R.string.places_mine_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = AppText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        if (state.mine.isEmpty() && state.personal.isEmpty() && !state.loading) {
            item {
                Text(
                    text = stringResource(R.string.places_mine_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (state.personal.isNotEmpty()) {
            item { PlacesSectionHeading(stringResource(R.string.places_personal_section)) }
            items(state.personal, key = { "personal:${it.id}" }) { place ->
                PlaceRow(
                    title = place.label,
                    detail = place.name,
                    onClick = { onShowPersonal(place) },
                    onRemove = { onRemovePersonal(place) },
                )
            }
        }

        if (state.mine.isNotEmpty()) {
            item { PlacesSectionHeading(stringResource(R.string.places_shared_section)) }
            items(state.mine, key = { "shared:${it.id}" }) { place ->
                PlaceRow(
                    title = place.name,
                    detail = place.createdByName.orEmpty(),
                    onClick = { onShowShared(place) },
                    onRemove = { onRemoveShared(place) },
                )
            }
        }
    }
}

@Composable
private fun PlacesSectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AppTextMuted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun PlaceRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        HudPinIcon(tint = AppPrimary)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = AppText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(R.string.map_search_clear_symbol),
            color = AppTextMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

private val PlacesBarShape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)

/** Tall enough for a handful of rows without swallowing the map above it. */
private val PLACES_LIST_MAX_HEIGHT = 220.dp


/**
 * The two lists as pins the map can draw.
 *
 * ## Why the id encodes which list a pin came from
 *
 * [RiderMap] draws one flat list of [Waypoint]s and hands back the id of
 * whichever was tapped, so the id is the only channel there is. Shared places
 * keep their own id, which is a rowid and therefore positive; a personal place
 * becomes `-(id + 1)`, which is at most -2 and can never collide with one.
 *
 * `+ 1` rather than plain negation so that nothing maps to zero — a
 * distinguished value that means "neither" is worth more than the byte it
 * costs, and a rowid is never 0 to begin with.
 */
private fun placePins(state: PlacesUiState): List<Waypoint> {
    val shared = state.mine.map {
        Waypoint(
            id = sharedPin(it.id),
            name = it.name,
            lat = it.lat,
            lng = it.lng,
            type = Waypoint.TYPE_PLANNED,
            orderIndex = null,
        )
    }
    val personal = state.personal.map {
        Waypoint(
            id = personalPin(it.id),
            // The label, because that is what the rider calls the place — the
            // pin on the map should say "บ้าน", not the condo's marketing name.
            name = it.label,
            lat = it.lat,
            lng = it.lng,
            type = Waypoint.TYPE_PLANNED,
            orderIndex = null,
        )
    }
    return shared + personal
}

private fun sharedPin(id: Long): Long = id

private fun personalPin(id: Long): Long = -(id + 1L)

/** Which shared place this pin is, or null when it is one of the private ones. */
private fun sharedPinId(pin: Long): Long? = pin.takeIf { it > 0L }

/** Which private place this pin is, or null when it is a shared one. */
private fun personalPinId(pin: Long): Long? = if (pin < 0L) -pin - 1L else null
