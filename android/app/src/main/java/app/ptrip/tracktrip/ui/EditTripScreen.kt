package app.ptrip.tracktrip.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudSecondaryButton
import app.ptrip.tracktrip.ui.theme.HudSectionHeader
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import java.util.Locale

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
 * ## Why the route is a signpost here and not a second editor
 *
 * The route already has a home: the list behind the magnifier on the trip map,
 * which is where both ends and every stop are edited, with the map underneath
 * to point at. Rebuilding any of that here would give the same two fields two
 * editors that have to agree, and the one without a map would be the worse of
 * them.
 *
 * So this screen shows what the route currently is — read-only, because seeing
 * it is what tells a rider whether they need the other screen at all — and a
 * button through to the place that edits it. That is also the answer to what
 * "Edit trip" means: the name is edited here, the route is edited on the map,
 * and this screen is where both facts are visible at once.
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
    onEditRoute: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        HudTopBar(
            title = stringResource(R.string.edit_trip),
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
            subtitle = trip?.name,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (trip == null) {
            // The trip is read from the list this screen was opened over, so
            // it is normally already there. Not always: the activity can be
            // rebuilt straight onto this screen from saved state, with the
            // first fetch still in flight.
            HudLoading()
            return@Column
        }

        // Keyed on the trip's own name so that a rebuild — a rotation, a
        // language change — starts from what is stored rather than from an
        // empty field, and so that a rename that landed is what the field
        // shows if the rider comes straight back.
        var typed by remember(trip.name) { mutableStateOf(trip.name) }
        val trimmed = typed.trim()

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
                    onClick = { onSave(trimmed) },
                    // Nothing typed, nothing changed, or a save already in
                    // flight: all three are requests whose answer is already
                    // known, and a pressable button that does nothing reads as
                    // a save that silently failed.
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

            HudSectionHeader(
                text = stringResource(R.string.edit_trip_route_title),
                modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
            )

            HudSurface {
                RouteEndReadout(
                    label = stringResource(R.string.map_origin),
                    endpoint = trip.origin,
                )
                RouteEndReadout(
                    label = stringResource(R.string.map_destination),
                    endpoint = trip.destination,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = stringResource(R.string.edit_trip_route_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
                    modifier = Modifier.padding(top = 12.dp),
                )
                HudSecondaryButton(
                    text = stringResource(R.string.edit_trip_open_route),
                    onClick = onEditRoute,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
    }
}

/**
 * One end of the route, said in words.
 *
 * A label when the endpoint has one, coordinates when it does not — a point
 * dropped on the map often has no name, and "18.7883, 98.9853" is still an
 * answer to "is the start set?", which is the question this readout exists to
 * answer. Nothing set at all says so rather than showing a blank.
 */
@Composable
private fun RouteEndReadout(
    label: String,
    endpoint: TripEndpoint?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AppTextMuted,
        )
        Text(
            text = endpoint?.let { end ->
                end.label?.takeIf { named -> named.isNotBlank() }
                    ?: stringResource(
                        R.string.edit_trip_route_point,
                        coordinate(end.lat),
                        coordinate(end.lng),
                    )
            } ?: stringResource(R.string.edit_trip_route_unset),
            style = MaterialTheme.typography.bodyMedium,
            color = if (endpoint == null) AppTextMuted else AppText,
        )
    }
}

/**
 * One coordinate, to five places — about a metre, which is as precise as a
 * point somebody dropped with a fingertip deserves to be read back.
 *
 * Formatted here rather than by the resource so the placeholder stays a plain
 * `%1$s`: the translation test reads placeholders to check the two languages
 * agree, and it does not recognise a precision spec — a string it cannot parse
 * is a string it cannot guard.
 *
 * [Locale.US] rather than the rider's own: a latitude is not prose, and a Thai
 * locale would render `18,78832` in some formats and Thai digits in others,
 * neither of which anybody can paste into a map.
 */
private fun coordinate(value: Double): String = String.format(Locale.US, "%.5f", value)

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
                origin = TripEndpoint(18.7883, 98.9853, "Chiang Mai"),
                destination = null,
            ),
            saving = false,
            error = null,
            onSave = {},
            onEditRoute = {},
            onBack = {},
        )
    }
}
