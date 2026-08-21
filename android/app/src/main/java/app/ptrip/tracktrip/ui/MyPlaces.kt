package app.ptrip.tracktrip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.PersonalPlace
import app.ptrip.tracktrip.data.SharedPlace
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppSurface
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudConfirmDialog
import app.ptrip.tracktrip.ui.theme.HudPinIcon
import app.ptrip.tracktrip.ui.theme.HudSecondaryButton
import app.ptrip.tracktrip.ui.theme.HudSurface
import java.util.Locale

/**
 * The places this rider has written down, in two sections, with the way to
 * look one over and the way to take one back off.
 *
 * ## Why it lives on Settings rather than under the map
 *
 * It used to sit directly under the map on **Map & places**, a cross at the
 * end of every row, with a full-bleed map above it that a finger is already
 * dragging. Somewhere a rider pans and pinches is the worst possible
 * neighbour for a column of delete buttons: the report was places going
 * missing, and the cause was a thumb landing an inch below where it was
 * aimed.
 *
 * Nothing about the list changed in the move — the same two sections in the
 * same order, the same rows, the same confirmation before anything goes. What
 * changed is that reaching it is now a deliberate act, which is the right
 * price for a screen whose only controls are destructive.
 *
 * The places themselves did not leave the map: they are still pins on it, and
 * tapping one still offers to remove it. Tapping a pin is aiming at the place
 * itself, which is a different gesture from brushing a list.
 *
 * ## Why the rows became cards
 *
 * They were bare rows in a column, and the only thing on one that could be
 * pressed was the cross that deleted it. A rider who wanted to check *which*
 * PTT this was before removing it had nowhere to go: the row showed a name and
 * one supporting line, and everything else the app knew about the place — where
 * it actually is, when it was written down, who by — was not on any screen.
 *
 * So the row is a [HudSurface] card, the same one the trip archive uses, and
 * tapping it opens what the app knows. Two consequences worth stating: the
 * whole card is now a target that does something harmless, so a mis-tap opens a
 * card instead of deleting a place; and the cross has an alternative next to
 * it, so pressing it is a choice between two things rather than the only thing
 * to press.
 *
 * ## Why the two sections are never merged
 *
 * Everything under "Shared with everyone" is visible to the whole server and
 * everything under "Only you" is visible to nobody else. A rider tidying up
 * has to know which is which without tapping anything, so the headings are the
 * whole reason this is two sections rather than one sorted list.
 */
@Composable
fun MyPlacesSection(
    personal: List<PersonalPlace>,
    shared: List<SharedPlace>,
    loading: Boolean,
    onRemovePersonal: (Long) -> Unit,
    onRemoveShared: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Which places are mid-delete, by id, so their card can say so and their
     * cross can stop being pressable. Two sets because the two id spaces are
     * unrelated — see [PlacesUiState].
     */
    removingShared: Set<Long> = emptySet(),
    removingPersonal: Set<Long> = emptySet(),
) {
    // Which row is waiting on an answer, and which is being read. Held here
    // rather than by the caller because the dialogs and the list are one
    // control: a screen that showed the cards would otherwise have to
    // remember to show the two questions too.
    var removingSharedPlace by remember { mutableStateOf<SharedPlace?>(null) }
    var removingPersonalPlace by remember { mutableStateOf<PersonalPlace?>(null) }
    var openShared by remember { mutableStateOf<SharedPlace?>(null) }
    var openPersonal by remember { mutableStateOf<PersonalPlace?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudPinIcon()
            Text(
                text = stringResource(R.string.places_mine_title),
                style = MaterialTheme.typography.titleMedium,
                color = AppText,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        if (personal.isEmpty() && shared.isEmpty() && !loading) {
            Text(
                text = stringResource(R.string.places_mine_empty),
                style = MaterialTheme.typography.bodySmall,
                color = AppTextMuted,
                modifier = Modifier.padding(start = 38.dp, bottom = 12.dp, end = 8.dp),
            )
        }

        if (personal.isNotEmpty()) {
            PlacesSectionHeading(stringResource(R.string.places_personal_section))
            personal.forEach { place ->
                PlaceCard(
                    title = place.label,
                    detail = place.name,
                    removing = place.id in removingPersonal,
                    onOpen = { openPersonal = place },
                    onRemove = { removingPersonalPlace = place },
                )
            }
        }

        if (shared.isNotEmpty()) {
            PlacesSectionHeading(stringResource(R.string.places_shared_section))
            shared.forEach { place ->
                PlaceCard(
                    title = place.name,
                    detail = place.createdByName.orEmpty(),
                    removing = place.id in removingShared,
                    onOpen = { openShared = place },
                    onRemove = { removingSharedPlace = place },
                )
            }
        }
    }

    openShared?.let { place ->
        PlaceDetailDialog(
            title = place.name,
            // No street address: a shared place is a name somebody typed over
            // a point they chose, and there is nothing on the server to
            // reverse-geocode it with. The coordinate is what it actually is.
            lines = listOfNotNull(
                stringResource(R.string.place_detail_coordinates) to coordinates(place.lat, place.lng),
                place.createdAt?.let { added ->
                    savedOn(added)?.let { stringResource(R.string.place_detail_added) to it }
                },
                place.createdByName?.takeIf { it.isNotBlank() }?.let {
                    stringResource(R.string.place_detail_added_by) to it
                },
                // Said outright rather than left to be inferred from which
                // heading the card was under: this is the screen a rider is on
                // when they decide whether to delete it.
                stringResource(R.string.place_detail_visibility) to
                    stringResource(R.string.places_shared_section),
            ),
            onDismiss = { openShared = null },
        )
    }

    openPersonal?.let { place ->
        PlaceDetailDialog(
            title = place.label,
            lines = listOfNotNull(
                // The label is the chip and the name is what the route says —
                // they are usually different, and the difference is the whole
                // reason a personal place has two of them.
                stringResource(R.string.place_detail_name) to place.name,
                stringResource(R.string.place_detail_coordinates) to coordinates(place.lat, place.lng),
                place.createdAt?.let { added ->
                    savedOn(added)?.let { stringResource(R.string.place_detail_added) to it }
                },
                // No "added by" line, and never one: this table has no join to
                // users because the owner is always the caller.
                stringResource(R.string.place_detail_visibility) to
                    stringResource(R.string.places_personal_section),
            ),
            onDismiss = { openPersonal = null },
        )
    }

    removingSharedPlace?.let { place ->
        HudConfirmDialog(
            title = stringResource(R.string.map_shared_place_remove_title),
            message = stringResource(R.string.map_shared_place_remove_message, place.name),
            confirmText = stringResource(R.string.map_remove_point),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                onRemoveShared(place.id)
                removingSharedPlace = null
                // The detail card, if this was reached from it, is about a
                // place that is on its way out. Closed rather than left
                // describing a row the list is about to lose.
                openShared = null
            },
            onDismiss = { removingSharedPlace = null },
        )
    }

    removingPersonalPlace?.let { place ->
        HudConfirmDialog(
            title = stringResource(R.string.map_personal_place_remove_title),
            message = stringResource(R.string.map_personal_place_remove_message, place.label),
            confirmText = stringResource(R.string.map_remove_point),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                onRemovePersonal(place.id)
                removingPersonalPlace = null
                openPersonal = null
            },
            onDismiss = { removingPersonalPlace = null },
        )
    }
}

@Composable
private fun PlacesSectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AppTextMuted,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/**
 * One place: a card that opens it, and the cross that removes it.
 *
 * The same [HudSurface] the trip archive draws its cards with, for the same
 * reason — a list of things a rider taps to look at reads as a list of cards,
 * and having the two lists in the app disagree about that was the only thing
 * making this one look like a settings screen rather than a list of places.
 *
 * The cross keeps its own padding and sits outside the card's clickable area
 * in *meaning* though not in layout: pressing it opens the confirmation rather
 * than deleting anything, so the two targets next to each other do a harmless
 * thing and a reversible one.
 *
 * [removing] is the delete already in flight. The card dims and the cross
 * becomes a spinner, which is the difference between "nothing happened" and
 * "this is happening" — the gap a rider used to fill by tapping again.
 */
@Composable
private fun PlaceCard(
    title: String,
    detail: String,
    removing: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    HudSurface(modifier = Modifier.clickable(enabled = !removing, onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
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

            if (removing) {
                Box(
                    modifier = Modifier.size(REMOVE_TARGET),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = AppPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.map_search_clear_symbol),
                    color = AppPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Everything the app knows about one place, on one tap of its card.
 *
 * Read-only, and one button. Deleting is on the card behind it, deliberately:
 * a dialog whose only two controls are "Close" and "Delete" is a confirmation,
 * and this is not one — a rider opening it is checking which place this is,
 * which is the opposite of having decided.
 *
 * [lines] is a list of pairs rather than named parameters because the two
 * kinds of place have different things to say — a shared one has an author and
 * a private one never will — and a dialog with four nullable fields would
 * leave every caller working out which gaps to close up.
 */
@Composable
private fun PlaceDetailDialog(
    title: String,
    lines: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        titleContentColor = AppText,
        textContentColor = AppTextMuted,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.forEach { (label, value) ->
                    Column {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextMuted,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppText,
                        )
                    }
                }
            }
        },
        confirmButton = {
            HudSecondaryButton(text = stringResource(R.string.close), onClick = onDismiss)
        },
    )
}

/**
 * A point, written out.
 *
 * [Locale.US] on purpose and for the same reason every coordinate this app
 * sends is: a Thai phone formatting 18.7883 as "18,7883" would produce a
 * four-part string that reads as two coordinates and is neither.
 */
private fun coordinates(lat: Double, lng: Double): String =
    String.format(Locale.US, "%.5f, %.5f", lat, lng)

/**
 * The day a place was written down, or null when the stamp is unusable.
 *
 * Runs through the same [TripDates] that dates a finished trip, so the one
 * date format in the app is one piece of code — and so a place saved in an
 * earlier year says which, without every place saved this month carrying a
 * year nobody needed.
 */
@Composable
private fun savedOn(createdAtIso: String): String? {
    val locale = LocalContext.current.resources.configuration.locales[0]
    return remember(createdAtIso, locale) {
        TripDates.span(
            createdAtIso = createdAtIso,
            endedAtIso = null,
            // Read once per card rather than per recomposition: the clock is
            // only consulted to decide whether the year is needed, and that
            // answer does not change while a dialog is open.
            nowMs = System.currentTimeMillis(),
            locale = locale,
        )?.created
    }
}

/** As wide as the cross it stands in for, so the row does not shift mid-delete. */
private val REMOVE_TARGET = 44.dp
