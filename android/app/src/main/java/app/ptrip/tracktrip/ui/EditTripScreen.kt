package app.ptrip.tracktrip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.PersonalPlace
import app.ptrip.tracktrip.data.RouteLine
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.map.RoutePlan
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudSecondaryButton
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.TracktripTheme

/**
 * The trip itself, rather than who is on it.
 *
 * ## What was missing
 *
 * A trip was named once, on the way in, and never again. Everything else about
 * it could be changed afterwards — both ends, every stop, who is invited, when
 * it finishes — but the one field a rider reads to pick it out of a list of
 * eleven was fixed at whatever they typed in the four seconds before a ride.
 * "Trip 3" stayed "Trip 3". The server had no way to change it either, so this
 * arrives with a `name` on `PATCH /trips/:id`.
 *
 * ## Why the route is here rather than only on the map
 *
 * The route list started life as a sheet over the trip map, reached through the
 * magnifier in its header, and that is the right home for it *during* a ride: a
 * rider fixing a stop at a petrol station wants the map under their thumb. It
 * is the wrong home for planning. Somebody laying out next Sunday's ride on a
 * sofa was made to open a ride they are not on, find a button on a map they are
 * not reading, and edit through a card covering the thing it describes.
 *
 * So the same list is here too, and *the same list* is meant literally: this is
 * [RouteListSheet], driving the same draft on the same [TripMapViewModel]
 * instance the map uses, checked by the same [RouteSetupRules]. There is no
 * second implementation to keep in step, and a route half-edited on one screen
 * is the route the other screen opens on.
 *
 * The one thing that cannot come across is the long press: pointing at a spot
 * needs a map to point at. Everything else a picker can answer — a search, a
 * saved place, "use my current location" — works here exactly as it does over
 * the map.
 *
 * Owner-only, matching the server: `PATCH /trips/:id` is owner-only, and a
 * member offered a Save button would only ever be told 403.
 */
@Composable
fun EditTripScreen(
    trip: Trip?,
    saving: Boolean,
    error: String?,
    onSave: (String) -> Unit,
    /**
     * The draft this screen edits, and everything the list needs to draw it.
     *
     * Passed in rather than owned here because it belongs to the trip map's
     * view model — which is the point. See the note above about there being one
     * editor rather than two.
     */
    routeDraft: RouteDraft,
    routePlan: RoutePlan?,
    routeLine: RouteLine?,
    routePreviewLoading: Boolean,
    searchState: PlaceSearchState,
    personalPlaces: List<PersonalPlace>,
    myLocation: LatLng?,
    hasLocationPermission: Boolean,
    currentUserId: Long?,
    /** Seeds the draft from the trip as it is stored. */
    onOpenRouteSetup: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchCleared: () -> Unit,
    onPickRoutePoint: (RouteField, RoutePoint) -> Unit,
    onRemoveRouteRow: (Int) -> Unit,
    onMoveRoutePoint: (Int, Int) -> Unit,
    onConfirmRoute: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which row the picker that is open is filling, or null when none is. The
    // same one value the map's flow turns on: it is set by tapping a row, and
    // it is what a search result, a saved place and "use my current location"
    // all land in — so none of the three has to ask what the point is for.
    var picking by rememberSaveable { mutableStateOf<RouteField?>(null) }

    // A stop chosen with no name on it, waiting for one. The server refuses an
    // unnamed waypoint, so this is the one thing a picker still has to ask.
    var namingStop by remember { mutableStateOf<RoutePoint?>(null) }

    val takePicked: (RouteField, RoutePoint) -> Unit = { field, chosen ->
        if (field == RouteField.STOP && !RouteSetupRules.isStopNameValid(chosen.label)) {
            namingStop = chosen
        } else {
            onPickRoutePoint(field, chosen)
        }
        picking = null
        onSearchCleared()
    }

    // Seeded from the trip as stored, once the trip is known.
    //
    // Keyed on the id rather than run once: this screen can be arrived at
    // before the first fetch has landed, and seeding from a trip that is still
    // null would leave a rider looking at two blank rows over a route that is
    // set — the same bug the map's own effect exists to avoid.
    LaunchedEffect(trip?.id) {
        if (trip != null) onOpenRouteSetup()
    }

    // Back steps out of the picker rather than off the screen. A rider two taps
    // into choosing a stop pressing back means "not that one", not "throw away
    // the name I am editing".
    BackHandler(enabled = picking != null) {
        picking = null
        onSearchCleared()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            HudTopBar(
                title = stringResource(R.string.edit_trip),
                onBack = onBack,
                backContentDescription = stringResource(R.string.back),
                subtitle = trip?.name,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (trip == null) {
                // The trip is read from the list this screen was opened over,
                // so it is normally already there. Not always: the activity can
                // be rebuilt straight onto this screen from saved state, with
                // the first fetch still in flight.
                HudLoading()
                return@Column
            }

            // Keyed on the trip's own name so that a rebuild — a rotation, a
            // language change — starts from what is stored rather than from an
            // empty field, and so that a rename that landed is what the field
            // shows if the rider comes straight back.
            var typed by remember(trip.name) { mutableStateOf(trip.name) }
            val trimmed = typed.trim()

            /**
             * The name the rider last pressed Save on, or null before they
             * have.
             *
             * Save used to answer for itself by leaving the screen — that was
             * the feedback, and it was also the bug: leaving threw the route
             * draft away, so a rider who saved a name watched their route go
             * back to two empty rows. Save stays put now, which leaves it with
             * nothing to say unless the screen says it.
             *
             * Deliberately not a flag raised and lowered around [saving]. That
             * only works if a composition happens to fall inside the window
             * where the flag is up, and this app has already shipped one
             * button whose whole feedback was invisible for exactly that
             * reason — see `CentreOnMe`. This asks the question that has a
             * lasting answer instead: the save landed when [trip] comes back
             * carrying the name that was sent.
             */
            var submitted by remember(trip.id) { mutableStateOf<String?>(null) }

            // Still true, rather than true once: the rider typing anything
            // else makes it a claim about a name that is no longer in the box.
            val savedAndUnchanged = submitted != null &&
                submitted == trip.name &&
                typed == trip.name &&
                error == null

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                HudSurface {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = {
                            if (it.length <= TripNameRules.MAX_LENGTH) typed = it
                        },
                        label = { Text(stringResource(R.string.trip_name_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${trimmed.length}/${TripNameRules.MAX_LENGTH}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                error?.let { HudError(it, modifier = Modifier.padding(top = 12.dp)) }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HudPrimaryButton(
                        text = stringResource(R.string.edit_trip_save),
                        onClick = {
                            submitted = trimmed
                            onSave(trimmed)
                        },
                        // Nothing typed, nothing changed, or a save already in
                        // flight: all three are requests whose answer is
                        // already known, and a pressable button that does
                        // nothing reads as a save that silently failed.
                        enabled = TripNameRules.canSave(trip.name, typed, saving),
                        loading = saving,
                        modifier = Modifier.weight(1f),
                    )
                    HudSecondaryButton(
                        text = stringResource(R.string.cancel),
                        onClick = onBack,
                        enabled = !saving,
                    )
                }

                if (savedAndUnchanged) {
                    Text(
                        text = stringResource(R.string.edit_trip_saved),
                        style = MaterialTheme.typography.labelMedium,
                        color = AppPrimary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                // No heading over it: the sheet already says "Your route" in
                // its own corner, and a section header repeating that would be
                // the same word twice in a column an inch tall.
                //
                // The route's own Confirm is inside this, separate from the
                // name's Save on purpose: they are two writes to two endpoints,
                // and one button over both would make a rider who only wanted
                // to fix a typo re-send a route they never looked at.
                RouteListSheet(
                    draft = routeDraft,
                    plan = routePlan,
                    route = routeLine,
                    loading = routePreviewLoading,
                    canEditEnds = RouteSetupRules.canEditEnds(trip.isOwner),
                    canAddStops = RouteSetupRules.canAddStops(trip.isActive),
                    currentUserId = currentUserId,
                    onPick = { tapped ->
                        picking = tapped
                        onSearchCleared()
                    },
                    onRemoveRow = onRemoveRouteRow,
                    onMoveRow = onMoveRoutePoint,
                    onConfirm = onConfirmRoute,
                    // The cross in the sheet's own corner. Over the map it
                    // closes a card; here the card *is* the screen, so it means
                    // the same thing the back arrow does.
                    onDismiss = onBack,
                    showLongPressHint = false,
                    modifier = Modifier.padding(top = 28.dp, bottom = 24.dp),
                )
            }
        }

        // The picker, over everything. Last in the box on purpose: it is a
        // screen rather than a panel, and anything drawn after it would be
        // drawn on top of a search a rider is typing into.
        picking?.let { field ->
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
                // No name is prefilled, the same as over the map: "My location"
                // as a label is a name a rider has to delete before typing the
                // one they meant, and it means nothing an hour later.
                onUseCurrentLocation = { here -> takePicked(field, RoutePoint(here)) },
                currentUserId = currentUserId,
                // Deliberately nothing. "This is not on the map" arms the map
                // to be pointed at, and there is no map on this screen to arm —
                // offering the row here would be offering a gesture the rider
                // cannot make. Writing down a place no geocoder knows stays a
                // thing you do on the places map or during a ride.
                onAddPlace = {},
                onRemoveSharedPlace = {},
                personalPlaces = personalPlaces,
                onPickPersonal = { saved ->
                    // Straight into the row the rider came here to fill. No
                    // request: the coordinate arrived with the chip.
                    takePicked(field, RoutePoint(saved.point, saved.name))
                },
            )
        }
    }

    namingStop?.let { stop ->
        StopNameDialog(
            point = stop.point,
            suggestedName = stringResource(
                R.string.map_route_stop_default_name,
                RouteSetupRules.nextStopNumber(routeDraft),
            ),
            onName = { name ->
                onPickRoutePoint(RouteField.STOP, stop.copy(label = name.trim()))
                namingStop = null
            },
            onDismiss = { namingStop = null },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F9FA)
@Composable
private fun EditTripPreview() {
    TracktripTheme {
        EditTripScreen(
            trip = Trip(
                id = 1L,
                name = "Trip 3",
                ownerId = 1L,
                status = "active",
                role = "owner",
            ),
            saving = false,
            error = null,
            onSave = {},
            routeDraft = RouteDraft(),
            routePlan = null,
            routeLine = null,
            routePreviewLoading = false,
            searchState = PlaceSearchState(),
            personalPlaces = emptyList(),
            myLocation = null,
            hasLocationPermission = false,
            currentUserId = 1L,
            onOpenRouteSetup = {},
            onSearchQueryChanged = {},
            onSearchCleared = {},
            onPickRoutePoint = { _, _ -> },
            onRemoveRouteRow = {},
            onMoveRoutePoint = { _, _ -> },
            onConfirmRoute = {},
            onBack = {},
        )
    }
}
