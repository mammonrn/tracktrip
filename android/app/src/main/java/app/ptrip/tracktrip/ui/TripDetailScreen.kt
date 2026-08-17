package app.ptrip.tracktrip.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.SuggestedInvitee
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.HudChip
import app.ptrip.tracktrip.ui.theme.HudAmber
import app.ptrip.tracktrip.ui.theme.HudCyan
import app.ptrip.tracktrip.ui.theme.HudDangerButton
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPanel
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudSecondaryButton
import app.ptrip.tracktrip.ui.theme.HudSectionHeader
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudText
import app.ptrip.tracktrip.ui.theme.HudTextDim
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import app.ptrip.tracktrip.ui.theme.riderColor

@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    currentUserId: Long?,
    sharing: Boolean,
    onStartSharing: (SharingDuration) -> Unit,
    onStopSharing: () -> Unit,
    onShareInviteLink: () -> Unit,
    onInviteEmailChange: (String) -> Unit,
    onSendInvite: () -> Unit,
    onUseSuggestion: (SuggestedInvitee) -> Unit,
    onShowQr: () -> Unit,
    onEndTrip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = state.trip
    var confirmingEnd by remember { mutableStateOf(false) }

    var choosingDuration by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        HudTopBar(
            title = trip?.name ?: stringResource(R.string.untitled_trip),
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
            subtitle = trip?.let {
                buildString {
                    append(
                        stringResource(
                            if (it.isActive) R.string.status_active else R.string.status_ended
                        )
                    )
                    append(" · ")
                    append(
                        stringResource(
                            if (it.isOwner) R.string.role_owner else R.string.role_member
                        )
                    )
                }
            },
            subtitleColor = if (trip?.isActive == true) HudCyan else HudTextDim,
        )

        state.error?.let { HudError(it) }

        if (state.loading && trip == null) {
            HudLoading()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
        ) {
            item {
                HudSectionHeader(
                    text = stringResource(R.string.members_title),
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(state.members, key = { it.userId }) { member -> MemberRow(member) }

            // Inviting is owner-only on the backend, so a member is not shown
            // a control that would only ever answer 403.
            if (trip != null && trip.isOwner && trip.isActive) {
                item {
                    HudSectionHeader(
                        text = stringResource(R.string.invite_title),
                        accent = HudCyan,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                }
                item {
                    InvitePanel(
                        email = state.inviteEmail,
                        pending = state.invitePending,
                        sentTo = state.inviteSentTo,
                        suggestions = state.orderedSuggestions,
                        onEmailChange = onInviteEmailChange,
                        onSend = onSendInvite,
                        onUseSuggestion = onUseSuggestion,
                        onShowQr = onShowQr,
                        onShareLink = onShareInviteLink,
                    )
                }
            }
        }

        if (trip != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                // Sharing is the thing a rider comes to this screen to start,
                // so it sits above the owner-only controls rather than below.
                if (trip.isActive) {
                    if (sharing) {
                        HudDangerButton(
                            text = stringResource(R.string.sharing_stop),
                            onClick = onStopSharing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        HudPrimaryButton(
                            text = stringResource(R.string.sharing_start),
                            onClick = { choosingDuration = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (trip.isOwner && trip.isActive) {
                    if (confirmingEnd) {
                        Text(
                            text = stringResource(R.string.end_trip_confirm),
                            style = MaterialTheme.typography.bodySmall,
                            color = HudTextDim,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HudDangerButton(
                                text = stringResource(R.string.end_trip_yes),
                                onClick = { confirmingEnd = false; onEndTrip() },
                                loading = state.endPending,
                                modifier = Modifier.weight(1f),
                            )
                            HudSecondaryButton(
                                text = stringResource(R.string.cancel),
                                onClick = { confirmingEnd = false },
                            )
                        }
                    } else {
                        HudDangerButton(
                            text = stringResource(R.string.end_trip),
                            onClick = { confirmingEnd = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (choosingDuration) {
        SharingDurationDialog(
            onPick = { duration ->
                choosingDuration = false
                onStartSharing(duration)
            },
            onDismiss = { choosingDuration = false },
        )
    }
}

/**
 * How long to share for.
 *
 * The four the server accepts, no more: a free-form duration would make "how
 * long could this rider still be broadcasting?" unanswerable without reading
 * the row, which is the question the whole feature turns on.
 */
@Composable
private fun SharingDurationDialog(
    onPick: (SharingDuration) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HudPanel,
        titleContentColor = HudAmber,
        textContentColor = HudText,
        shape = RoundedCornerShape(4.dp),
        title = {
            Text(
                text = stringResource(R.string.sharing_choose_duration).uppercase(),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                SharingDuration.entries.forEach { duration ->
                    Text(
                        text = stringResource(duration.durationLabelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HudText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(duration) }
                            .padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            HudSecondaryButton(text = stringResource(R.string.cancel), onClick = onDismiss)
        },
    )
}

@get:StringRes
private val SharingDuration.durationLabelRes: Int
    get() = when (this) {
        SharingDuration.THIRTY_MINUTES -> R.string.duration_30_minutes
        SharingDuration.ONE_HOUR -> R.string.duration_1_hour
        SharingDuration.FOUR_HOURS -> R.string.duration_4_hours
        SharingDuration.UNTIL_STOPPED -> R.string.duration_until_stopped
    }

@Composable
private fun MemberRow(member: MemberPosition) {
    HudSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudDot(color = riderColor(member.userId))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = member.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = HudText,
                )
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                if (member.isOwner) R.string.role_owner else R.string.role_member
                            )
                        )
                        if (!member.hasPosition) {
                            append(" · ")
                            append(stringResource(R.string.not_tracking_yet))
                        }
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
}

@Composable
private fun InvitePanel(
    email: String,
    pending: Boolean,
    sentTo: String?,
    suggestions: List<SuggestedInvitee>,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onUseSuggestion: (SuggestedInvitee) -> Unit,
    onShowQr: () -> Unit,
    onShareLink: () -> Unit,
) {
    HudSurface(accent = HudCyan.copy(alpha = 0.4f)) {
        // Riders from past trips, one tap instead of an email typed from
        // memory. Absent for a first trip, which is when there is nobody to
        // suggest — so the row simply isn't there rather than being empty.
        //
        // The whole list, ordered by how often you have ridden together, in a
        // box that scrolls once it grows past a few rows. Capping it hid
        // exactly the regulars a long-standing group is looking for.
        if (suggestions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.invite_suggestions),
                style = MaterialTheme.typography.labelSmall,
                color = HudTextDim,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = SUGGESTIONS_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { invitee ->
                    HudChip(text = invitee.label, onClick = { onUseSuggestion(invitee) })
                }
            }
        }

        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.invite_email_label)) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Send,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sentTo?.let {
                Text(
                    text = stringResource(R.string.invite_sent_to, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = HudCyan,
                    modifier = Modifier.weight(1f),
                )
            }
            HudPrimaryButton(
                text = stringResource(R.string.send_invite),
                onClick = onSend,
                enabled = email.isNotBlank(),
                loading = pending,
            )
        }
        Text(
            text = stringResource(R.string.invite_hint),
            style = MaterialTheme.typography.labelSmall,
            color = HudTextDim,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Alongside email, not instead of it: an email invite reaches someone
        // who isn't here, a QR reaches someone who is, and a shared link
        // reaches someone in a chat window.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HudSecondaryButton(
                text = stringResource(R.string.qr_invite_action),
                onClick = onShowQr,
                modifier = Modifier.weight(1f),
            )
            HudSecondaryButton(
                text = stringResource(R.string.invite_share_link),
                onClick = onShareLink,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Roughly four rows of chips before the box starts scrolling. */
private val SUGGESTIONS_MAX_HEIGHT = 180.dp

@Preview(showBackground = true, backgroundColor = 0xFF0B0E1A)
@Composable
private fun TripDetailPreview() {
    TracktripTheme {
        TripDetailScreen(
            state = TripDetailUiState(
                loading = false,
                trip = Trip(1, "Chiang Mai loop", 1, "active", "owner"),
                members = listOf(
                    MemberPosition(
                        userId = 1,
                        displayName = "Owner",
                        username = "poom",
                        photoUrl = null,
                        role = "owner",
                        isSharing = true,
                        sharingUntil = null,
                        lat = 18.79,
                        lng = 98.98,
                        batteryPct = 91,
                        recordedAt = "2026-05-01T08:00:00.000Z",
                    ),
                    MemberPosition(
                        userId = 2,
                        displayName = "Friend",
                        username = null,
                        photoUrl = null,
                        role = "member",
                        isSharing = false,
                        sharingUntil = null,
                        lat = null,
                        lng = null,
                        batteryPct = null,
                        recordedAt = null,
                    ),
                ),
                suggestions = listOf(
                    SuggestedInvitee(4, "friend@gmail.com", "Friend", "speedy", null, 5),
                    SuggestedInvitee(5, "nut@gmail.com", "Nut", null, null, 2),
                ),
            ),
            currentUserId = 1,
            sharing = true,
            onStartSharing = {},
            onStopSharing = {},
            onShareInviteLink = {},
            onInviteEmailChange = {},
            onSendInvite = {},
            onUseSuggestion = {},
            onShowQr = {},
            onEndTrip = {},
            onBack = {},
        )
    }
}
