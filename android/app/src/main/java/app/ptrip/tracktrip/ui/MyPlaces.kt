package app.ptrip.tracktrip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudConfirmDialog
import app.ptrip.tracktrip.ui.theme.HudPinIcon

/**
 * The places this rider has written down, in two sections, with the way to
 * take one back off.
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
) {
    // Which row is waiting on an answer. Held here rather than by the caller
    // because the dialog and the list are one control: a screen that showed
    // the rows would otherwise have to remember to show the question too.
    var removingShared by remember { mutableStateOf<SharedPlace?>(null) }
    var removingPersonal by remember { mutableStateOf<PersonalPlace?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
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
                PlaceRow(
                    title = place.label,
                    detail = place.name,
                    onRemove = { removingPersonal = place },
                )
            }
        }

        if (shared.isNotEmpty()) {
            PlacesSectionHeading(stringResource(R.string.places_shared_section))
            shared.forEach { place ->
                PlaceRow(
                    title = place.name,
                    detail = place.createdByName.orEmpty(),
                    onRemove = { removingShared = place },
                )
            }
        }
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

@Composable
private fun PlacesSectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AppTextMuted,
        modifier = Modifier.padding(start = 38.dp, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * One place, and the cross that removes it.
 *
 * The row itself does nothing when tapped, which is the point: there is no map
 * on this screen to be taken to, and the only thing here that can be pressed
 * is the one thing worth being sure about.
 */
@Composable
private fun PlaceRow(title: String, detail: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 38.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(top = 10.dp, bottom = 10.dp)) {
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
            color = AppPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
