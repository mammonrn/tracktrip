package app.ptrip.tracktrip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.Invite
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.HudAmber
import app.ptrip.tracktrip.ui.theme.HudCyan
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudEmpty
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudSectionHeader
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudText
import app.ptrip.tracktrip.ui.theme.HudTextDim
import app.ptrip.tracktrip.ui.theme.TracktripTheme

/**
 * The rider's trips, with any pending invitations pinned above them — an
 * invitation is the only thing on this screen that is waiting on the user, so
 * it goes first.
 */
@Composable
fun TripListScreen(
    state: TripsUiState,
    displayName: String?,
    onOpenTrip: (Trip) -> Unit,
    onCreateTrip: () -> Unit,
    onAcceptInvite: (Invite) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.trips_title).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = HudAmber,
                )
                Text(
                    text = displayName?.let { stringResource(R.string.signed_in_as, it) }
                        ?: stringResource(R.string.signed_in),
                    style = MaterialTheme.typography.labelSmall,
                    color = HudTextDim,
                )
            }
        }

        state.error?.let { HudError(it) }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
        ) {
            if (state.invites.isNotEmpty()) {
                item {
                    HudSectionHeader(
                        text = stringResource(R.string.invitations_title),
                        accent = HudCyan,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(state.invites, key = { "invite-${it.id}" }) { invite ->
                    InviteCard(
                        invite = invite,
                        accepting = state.acceptingInviteId == invite.id,
                        onAccept = { onAcceptInvite(invite) },
                    )
                }
            }

            item {
                HudSectionHeader(
                    text = stringResource(R.string.your_trips_title),
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }

            when {
                state.loading && state.trips.isEmpty() -> item { HudLoading() }
                state.trips.isEmpty() -> item {
                    HudEmpty(stringResource(R.string.no_trips_yet))
                }
                else -> items(state.trips, key = { "trip-${it.id}" }) { trip ->
                    TripCard(trip = trip, onClick = { onOpenTrip(trip) })
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onCreateTrip, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.new_trip))
            }
            OutlinedButton(onClick = onRefresh) {
                Text(stringResource(R.string.refresh))
            }
            TextButton(onClick = onSignOut) {
                Text(stringResource(R.string.sign_out))
            }
        }
    }
}

@Composable
private fun TripCard(trip: Trip, onClick: () -> Unit) {
    HudSurface(
        modifier = Modifier.clickable(onClick = onClick),
        accent = if (trip.isActive) HudAmber.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudDot(color = if (trip.isActive) HudCyan else HudTextDim)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = trip.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = HudText,
                )
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                if (trip.isActive) R.string.status_active else R.string.status_ended
                            )
                        )
                        append(" · ")
                        append(
                            stringResource(
                                if (trip.isOwner) R.string.role_owner else R.string.role_member
                            )
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = HudTextDim,
                )
            }
        }
    }
}

@Composable
private fun InviteCard(invite: Invite, accepting: Boolean, onAccept: () -> Unit) {
    HudSurface(accent = HudCyan.copy(alpha = 0.6f)) {
        Text(
            text = invite.tripName ?: stringResource(R.string.untitled_trip),
            style = MaterialTheme.typography.titleMedium,
            color = HudText,
        )
        Text(
            text = stringResource(R.string.invited_as, invite.email),
            style = MaterialTheme.typography.labelSmall,
            color = HudTextDim,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (accepting) {
                Box(modifier = Modifier.size(20.dp)) {
                    CircularProgressIndicator(color = HudCyan, strokeWidth = 2.dp)
                }
            } else {
                Button(onClick = onAccept) {
                    Text(stringResource(R.string.accept_invite))
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF04070B)
@Composable
private fun TripListPreview() {
    TracktripTheme {
        TripListScreen(
            state = TripsUiState(
                loading = false,
                trips = listOf(
                    Trip(1, "Chiang Mai loop", 1, "active", "owner"),
                    Trip(2, "Pai run", 2, "ended", "member"),
                ),
                invites = listOf(Invite(9, 3, "rider@gmail.com", "Mae Hong Son")),
            ),
            displayName = "Rider",
            onOpenTrip = {},
            onCreateTrip = {},
            onAcceptInvite = {},
            onRefresh = {},
            onSignOut = {},
        )
    }
}
