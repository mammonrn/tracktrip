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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.Invite
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudEmpty
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudGearIcon
import app.ptrip.tracktrip.ui.theme.HudIconButton
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudScanIcon
import app.ptrip.tracktrip.ui.theme.HudSecondaryButton
import app.ptrip.tracktrip.ui.theme.HudSectionHeader
import app.ptrip.tracktrip.ui.theme.HudStatusBadge
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudTopBar
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
    sharingTripName: String?,
    onOpenTrip: (Trip) -> Unit,
    onCreateTrip: () -> Unit,
    onAcceptInvite: (Invite) -> Unit,
    onRefresh: () -> Unit,
    onScanQr: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        HudTopBar(
            title = stringResource(R.string.trips_title),
            subtitle = displayName?.let { stringResource(R.string.signed_in_as, it) }
                ?: stringResource(R.string.signed_in),
        ) {
            HudIconButton(
                onClick = onScanQr,
                contentDescription = stringResource(R.string.qr_scan_title),
                icon = { HudScanIcon() },
            )
            HudIconButton(
                onClick = onOpenSettings,
                contentDescription = stringResource(R.string.settings_title),
                icon = { HudGearIcon() },
            )
        }

        // Whether this phone is broadcasting, on the screen a rider opens
        // first. It answers the question they actually have — "is my location
        // going out right now?" — which the service is the only thing that
        // knows; the trip screen's own copy of this is now the start/stop
        // control there.
        HudStatusBadge(
            text = sharingTripName
                ?.let { stringResource(R.string.sharing_on_trip, it) }
                ?: stringResource(R.string.sharing_off),
            on = sharingTripName != null,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

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
                        accent = AppPrimary,
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
            HudPrimaryButton(
                text = stringResource(R.string.new_trip),
                onClick = onCreateTrip,
                modifier = Modifier.weight(1f),
            )
            HudSecondaryButton(
                text = stringResource(R.string.refresh),
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun TripCard(trip: Trip, onClick: () -> Unit) {
    HudSurface(
        modifier = Modifier.clickable(onClick = onClick),
        accent = if (trip.isActive) {
            AppPrimary.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.outline
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudDot(color = if (trip.isActive) AppPrimary else AppTextMuted)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = trip.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppText,
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
                    color = AppTextMuted,
                )
            }
        }
    }
}

@Composable
private fun InviteCard(invite: Invite, accepting: Boolean, onAccept: () -> Unit) {
    HudSurface(accent = AppPrimary.copy(alpha = 0.6f)) {
        Text(
            text = invite.tripName ?: stringResource(R.string.untitled_trip),
            style = MaterialTheme.typography.titleMedium,
            color = AppText,
        )
        Text(
            text = stringResource(R.string.invited_as, invite.email),
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (accepting) {
                Box(modifier = Modifier.size(20.dp)) {
                    CircularProgressIndicator(color = AppPrimary, strokeWidth = 2.dp)
                }
            } else {
                HudPrimaryButton(
                    text = stringResource(R.string.accept_invite),
                    onClick = onAccept,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F9FA)
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
            sharingTripName = "Chiang Mai loop",
            onOpenTrip = {},
            onCreateTrip = {},
            onAcceptInvite = {},
            onRefresh = {},
            onScanQr = {},
            onOpenSettings = {},
        )
    }
}
