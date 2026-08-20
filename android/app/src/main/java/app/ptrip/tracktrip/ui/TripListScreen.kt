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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.Invite
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.map.Arrival
import app.ptrip.tracktrip.ui.theme.AppOnPrimary
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudAvatar
import app.ptrip.tracktrip.ui.theme.HudChip
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudEmpty
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudGearIcon
import app.ptrip.tracktrip.ui.theme.HudIconButton
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudPinIcon
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
    /**
     * The map with no trip behind it — for looking a place up, and for writing
     * one down, without first having a ride to attach it to.
     *
     * On this screen rather than inside a trip because that is when a rider
     * has the thought: the petrol station they could not find last weekend is
     * worth writing down on a Tuesday evening, and until now the only way in
     * was to open a ride and pretend to plan it.
     */
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * Whether to offer the "every trip" switch. True only for a super user —
     * and only as an offer: the server decides what the switch actually
     * returns, so a build that got this wrong would simply show the rider
     * their own trips under a wider heading.
     */
    isSuperuser: Boolean = false,
    onShowAllTrips: (Boolean) -> Unit = {},
    /** Opens or closes everything older than the newest [TripListRules.RECENT]. */
    onToggleArchive: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        HudTopBar(
            title = stringResource(R.string.trips_title),
            subtitle = displayName?.let { stringResource(R.string.signed_in_as, it) }
                ?: stringResource(R.string.signed_in),
        ) {
            HudIconButton(
                onClick = onOpenMap,
                contentDescription = stringResource(R.string.places_title),
                icon = { HudPinIcon() },
            )
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
                    text = if (state.showingAllTrips) {
                        stringResource(R.string.all_trips_title)
                    } else {
                        stringResource(R.string.your_trips_title)
                    },
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }

            // The one thing a super user can do that nobody else can, and the
            // only place in the app that says so. Off by default every time
            // the screen is built: being promoted should not change what this
            // rider's own app opens on.
            if (isSuperuser) {
                item {
                    HudChip(
                        text = if (state.showingAllTrips) {
                            stringResource(R.string.trips_show_mine)
                        } else {
                            stringResource(R.string.trips_show_all)
                        },
                        onClick = { onShowAllTrips(!state.showingAllTrips) },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }

            // The newest few, and everything else behind one tap. See
            // [TripListRules] for why the first screen of the app answers
            // "what am I riding" before "what have I ridden".
            val archived = TripListRules.archived(state.trips)

            when {
                state.loading && state.trips.isEmpty() -> item { HudLoading() }
                state.trips.isEmpty() -> item {
                    HudEmpty(
                        if (state.showingAllTrips) {
                            stringResource(R.string.no_trips_at_all)
                        } else {
                            stringResource(R.string.no_trips_yet)
                        }
                    )
                }
                else -> items(
                    TripListRules.recent(state.trips),
                    key = { "trip-${it.id}" },
                ) { trip ->
                    TripCard(
                        trip = trip,
                        podium = state.podiums[trip.id].orEmpty(),
                        onClick = { onOpenTrip(trip) },
                    )
                }
            }

            if (archived.isNotEmpty()) {
                // Above the trips it opens rather than below them, so the
                // control a rider just pressed is still under their thumb
                // afterwards instead of seven cards up the screen.
                item {
                    HudChip(
                        text = if (state.archiveOpen) {
                            stringResource(R.string.trips_archive_hide)
                        } else {
                            stringResource(R.string.trips_archive_show, archived.size)
                        },
                        onClick = { onToggleArchive(!state.archiveOpen) },
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }

                if (state.archiveOpen) {
                    items(archived, key = { "trip-${it.id}" }) { trip ->
                        TripCard(
                            trip = trip,
                            podium = state.podiums[trip.id].orEmpty(),
                            onClick = { onOpenTrip(trip) },
                        )
                    }
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
private fun TripCard(trip: Trip, podium: List<Arrival>, onClick: () -> Unit) {
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Inside the row the card already had, and shorter than the two
            // lines beside it, so the card is exactly the height it was. A
            // trip nobody has finished has no strip at all — see [ArrivalBoard]
            // for why that is not an empty state.
            if (podium.isNotEmpty()) {
                PodiumStrip(podium, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/**
 * The first three to the finish, as a row of faces.
 *
 * ## Why faces and not names
 *
 * The card cannot get taller — it is one of three above the fold, and the
 * whole point of the archive next to it is that this screen stays short — so
 * the podium has one row of the card's existing height to live in, at the end
 * of a row that already holds a name and a status line. Three names would not
 * fit across it; three 26dp faces do, with room to spare under the two lines
 * they sit beside.
 *
 * Left to right is first to third, which is how every podium anybody has seen
 * is read, and the numeral on each face says it outright for anyone it is not
 * obvious to. Colour is never the only carrier: the tints are decoration on
 * top of a numeral and a position.
 *
 * Each face's content description is the rank and the rider's name in words,
 * so a screen reader gets the ranking as a ranking rather than three
 * unexplained avatars.
 */
@Composable
private fun PodiumStrip(podium: List<Arrival>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        podium.forEach { arrival ->
            val tint = podiumTint(arrival.place)
            val spoken = stringResource(R.string.trip_podium_place, arrival.place, arrival.label)

            Box(
                modifier = Modifier
                    .size(PODIUM_FACE)
                    .semantics { contentDescription = spoken },
                contentAlignment = Alignment.BottomEnd,
            ) {
                HudAvatar(
                    name = arrival.label,
                    photoUrl = arrival.photoUrl,
                    diameter = PODIUM_FACE,
                    accent = tint,
                )
                Box(
                    modifier = Modifier
                        .size(PODIUM_BADGE)
                        .background(tint, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = arrival.place.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        lineHeight = 8.sp,
                        color = AppOnPrimary,
                    )
                }
            }
        }
    }
}

/**
 * Gold, silver, bronze.
 *
 * Decoration rather than information: the numeral on the badge and the
 * left-to-right order both say the same thing, so nothing is lost to anyone
 * who cannot tell these three apart.
 */
private fun podiumTint(place: Int): Color = when (place) {
    1 -> Color(0xFFF9A825)
    2 -> Color(0xFF9AA0A6)
    else -> Color(0xFFA1662F)
}

/** As tall as the podium gets — shorter than the two lines it sits beside. */
private val PODIUM_FACE = 26.dp
private val PODIUM_BADGE = 12.dp

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
            onOpenMap = {},
            onOpenSettings = {},
        )
    }
}
