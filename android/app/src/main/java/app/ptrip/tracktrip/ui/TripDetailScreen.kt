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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.ptrip.tracktrip.data.LiveCadence
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.SuggestedInvitee
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.map.FixAge
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppSurface
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudBatteryReadout
import app.ptrip.tracktrip.ui.theme.HudChip
import app.ptrip.tracktrip.ui.theme.HudDangerButton
import app.ptrip.tracktrip.ui.theme.HudDivider
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudSecondaryButton
import app.ptrip.tracktrip.ui.theme.HudSectionHeader
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import app.ptrip.tracktrip.ui.theme.riderColor
import kotlinx.coroutines.delay

/**
 * How often the ages on the member rows are recomputed.
 *
 * Twenty seconds: the numbers are whole minutes, so anything faster redraws
 * the same text, and anything slower lets a row sit a minute behind itself.
 */
private const val MEMBER_AGE_TICK_MS = 20_000L

@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    currentUserId: Long?,
    sharing: Boolean,
    onStartSharing: (SharingDuration) -> Unit,
    onStopSharing: () -> Unit,
    onShareInviteLink: () -> Unit,
    onOpenMap: () -> Unit,
    onInviteEmailChange: (String) -> Unit,
    onSendInvite: () -> Unit,
    onUseSuggestion: (SuggestedInvitee) -> Unit,
    onShowQr: () -> Unit,
    /**
     * Opens the trip's own details — its name, and the way through to the
     * route. Owner-only, so the button that calls it is too.
     */
    onEditTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onBack: () -> Unit,
    /**
     * Re-read the trip and its members. Called on a slow beat while this
     * screen is on top, and defaulted to nothing for the previews.
     *
     * Driven from here rather than from the view model for the same reason the
     * map's poll is: a view model outlives the screen — it is scoped to the
     * activity and keyed by trip id — so a loop started inside one would go on
     * fetching from behind three other screens. Started here, it stops when
     * this screen is no longer composed.
     */
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val trip = state.trip
    var confirmingEnd by remember { mutableStateOf(false) }

    var choosingDuration by remember { mutableStateOf(false) }

    // The poll that keeps this screen from disagreeing with the map.
    //
    // Both read `battery_pct` off `GET /trips/:id/positions`; the map polls it
    // and folds live socket frames in on top, and this screen used to ask
    // exactly once per app launch. A rider who opened the members list, rode
    // for two hours with the phone on a charger, and came back read 37% here
    // against 76% on the map — one number, two ages, and nothing on screen to
    // say which was which. See TripDetailViewModel.refresh.
    //
    // Twenty seconds, the same beat the map uses without a socket. There is no
    // socket on this screen: it is a list somebody reads for a few seconds,
    // not a map they ride behind, and a second WebSocket for it would be a
    // connection per screen for a number that moves one per cent an hour.
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(LiveCadence.POLL_MS)
        }
    }

    // The wall clock, on its own beat, so the ages below count up between
    // polls instead of sitting still and then jumping.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MEMBER_AGE_TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }

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
            subtitleColor = if (trip?.isActive == true) AppPrimary else AppTextMuted,
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
            items(state.members, key = { it.userId }) { member ->
                MemberRow(member, fixAgeMinutes = FixAge.minutesAgo(member.recordedAt, nowMs))
            }

            // Inviting is owner-only on the backend, so a member is not shown
            // a control that would only ever answer 403.
            if (trip != null && trip.isOwner && trip.isActive) {
                item {
                    HudSectionHeader(
                        text = stringResource(R.string.invite_title),
                        accent = AppPrimary,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                }
                item {
                    InvitePanel(
                        email = state.inviteEmail,
                        pending = state.invitePending,
                        sentTo = state.inviteSentTo,
                        error = state.error,
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
            // Four controls, in two rows of two rather than one column of
            // four.
            //
            // The column was the problem twice over. Stacked, the four read as
            // one slab of identical bars — nothing said which two belonged
            // together — and the fix for that was spacing, which only made the
            // slab taller. Side by side, the pairing *is* the layout: the two
            // rows are the two questions this screen answers.
            //
            // Top row, the ride: look at the map, and put yourself on it.
            // Bottom row, below the divider and drawn quiet, the trip itself:
            // its name, and its end. Both are the owner's, and neither is
            // something a rider who came here to start sharing should have
            // shouting at them.
            //
            // A row with one button in it is a full-width button — the
            // weights see to that — so a finished trip and a member's view do
            // not leave a half-button hanging in space. Nothing moved behind a
            // menu: every control is still one press away, as it was.
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The map is the point of the trip while it is running,
                    // and the record of it afterwards — so it is offered
                    // either way, and the wording changes rather than the
                    // button disappearing.
                    HudSecondaryButton(
                        text = stringResource(
                            if (trip.isActive) R.string.start_sharing
                            else R.string.view_final_positions
                        ),
                        onClick = onOpenMap,
                        modifier = Modifier.weight(1f),
                    )

                    // Sharing is the thing a rider comes to this screen to
                    // start, so it keeps the loud treatment and the place
                    // beside the map rather than under the owner's controls.
                    if (trip.isActive) {
                        if (sharing) {
                            HudDangerButton(
                                text = stringResource(R.string.sharing_stop),
                                onClick = onStopSharing,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            HudPrimaryButton(
                                text = stringResource(R.string.sharing_start),
                                onClick = { choosingDuration = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Owner-only, matching `PATCH /trips/:id`. Editing is offered
                // on a finished trip as well as a running one, unlike ending
                // it — naming a ride afterwards is when people do it, and the
                // server allows it for the same reason.
                if (trip.isOwner) {
                    HudDivider(modifier = Modifier.padding(top = 4.dp))

                    if (confirmingEnd) {
                        // The row becomes the question while it is being
                        // asked. Two buttons of equal width, so neither is the
                        // one a thumb finds by accident.
                        Text(
                            text = stringResource(R.string.end_trip_confirm),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextMuted,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HudSecondaryButton(
                                text = stringResource(R.string.edit_trip),
                                onClick = onEditTrip,
                                quiet = true,
                                modifier = Modifier.weight(1f),
                            )
                            if (trip.isActive) {
                                HudDangerButton(
                                    text = stringResource(R.string.end_trip),
                                    onClick = { confirmingEnd = true },
                                    quiet = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
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
        containerColor = AppSurface,
        titleContentColor = AppText,
        textContentColor = AppTextMuted,
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
                        color = AppText,
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

/**
 * One rider on the trip: their colour, name, role, and how their phone is
 * doing.
 *
 * [fixAgeMinutes] is here because of what went wrong without it. The battery
 * is not a live reading — it is whatever came up with this rider's last
 * position report — and a bare "37%" reads as "now". Saying when the number is
 * from turns a reading a rider might argue with into one they can act on.
 */
@Composable
private fun MemberRow(member: MemberPosition, fixAgeMinutes: Long? = null) {
    HudSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudDot(color = riderColor(member.userId))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = member.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppText,
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
                        // How old everything to the right of this row is —
                        // the battery included, since it arrives with the
                        // position rather than on its own.
                        fixAgeMinutes?.let { minutes ->
                            append(" · ")
                            append(
                                if (minutes < 1) {
                                    stringResource(R.string.map_fix_age_now)
                                } else {
                                    stringResource(R.string.map_fix_age_minutes, minutes)
                                }
                            )
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
                )
            }
            member.batteryPct?.let { HudBatteryReadout(percent = it) }
        }
    }
}

/**
 * The three ways onto a trip, and a note about the one that used to take up
 * most of the panel.
 *
 * ## Why the email field is behind a button now
 *
 * It was always on screen: a text field, a Send button, and a line of guidance
 * under it, sitting between the suggestions and the QR and share buttons. That
 * is a lot of panel for the least-used of the three ways in — the QR is for
 * somebody standing next to you and the link is for a chat window, and both
 * are one press. Typing a Google address from memory is the fallback, and a
 * fallback does not need to be the tallest thing on the screen.
 *
 * So the field, its button and its guidance moved into [InviteByEmailDialog],
 * behind "Add by email". Nothing about the invite changed: the same field,
 * the same [onSend], the same rule about when it can be pressed.
 *
 * The suggestion chips stay out here, and tapping one still fills the address
 * in — it opens the dialog with the address already there, which is what the
 * chip was always shorthand for.
 *
 * Internal rather than private so `InviteByEmailTest` can compose it on its
 * own. The screen around it runs two `while (true) { delay(...) }` effects —
 * the members poll and the fix-age tick — and a dialog opened over those never
 * lets a Compose test reach idle. The panel has no such effects.
 */
@Composable
internal fun InvitePanel(
    email: String,
    pending: Boolean,
    sentTo: String?,
    /** The screen's error line, which a dialog would otherwise cover. */
    error: String?,
    suggestions: List<SuggestedInvitee>,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onUseSuggestion: (SuggestedInvitee) -> Unit,
    onShowQr: () -> Unit,
    onShareLink: () -> Unit,
) {
    var typing by rememberSaveable { mutableStateOf(false) }

    // An invite that landed closes the dialog. Keyed on the address rather
    // than on a flag flipped inside onSend: the send is a round trip, and only
    // the server's answer is worth closing on — a failed one leaves the rider
    // in front of the address they typed with the reason underneath it.
    LaunchedEffect(sentTo) {
        if (sentTo != null) typing = false
    }

    HudSurface(accent = AppPrimary.copy(alpha = 0.4f)) {
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
                color = AppTextMuted,
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
                    HudChip(
                        text = invitee.label,
                        onClick = {
                            // Straight into the dialog with the address
                            // already filled in. The chip was always shorthand
                            // for typing it, and it still is.
                            onUseSuggestion(invitee)
                            typing = true
                        },
                    )
                }
            }
        }

        sentTo?.let {
            Text(
                text = stringResource(R.string.invite_sent_to, it),
                style = MaterialTheme.typography.labelSmall,
                color = AppPrimary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        // Three ways in, all the same size now that none of them is a form.
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        HudSecondaryButton(
            text = stringResource(R.string.invite_by_email),
            onClick = { typing = true },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }

    if (typing) {
        InviteByEmailDialog(
            email = email,
            pending = pending,
            error = error,
            onEmailChange = onEmailChange,
            onSend = onSend,
            onDismiss = { if (!pending) typing = false },
        )
    }
}

/**
 * The email invite, as the dialog it now is.
 *
 * A shell around [InviteByEmailForm], which is where everything that used to
 * sit open on the panel actually lives. Split in two because the form can be
 * composed and asserted on and this cannot: a text field inside a dialog
 * window never lets a Compose test reach idle under Robolectric, which is why
 * `StopNameDialog` has never been asserted on either. The shell is four lines
 * of placement; the form is the part with behaviour in it.
 */
@Composable
private fun InviteByEmailDialog(
    email: String,
    pending: Boolean,
    error: String?,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.invite_by_email)) },
        text = { InviteByEmailForm(email, error, onEmailChange) },
        confirmButton = {
            HudPrimaryButton(
                text = stringResource(R.string.send_invite),
                onClick = onSend,
                // The same rule as before the move: nothing typed, or a send
                // already in flight, are both requests whose answer is known,
                // and a pressable button that does nothing reads as a send
                // that silently failed.
                enabled = InviteRules.canSend(email, pending),
                loading = pending,
            )
        },
        dismissButton = {
            HudSecondaryButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
                // Not while a send is out: dismissing would leave the rider
                // with no way to see how it went.
                enabled = !pending,
            )
        },
        containerColor = AppSurface,
        titleContentColor = AppText,
        textContentColor = AppTextMuted,
    )
}

/**
 * The field, and the two things that belong under it.
 *
 * Everything that used to sit permanently on the panel: the address, the note
 * about what an invite actually does — nothing is emailed, it appears in their
 * trip list — and, new, the reason a send was refused.
 *
 * [error] is here rather than only on the screen behind, because a dialog
 * covers the screen behind. An invite refused with the dialog up — a malformed
 * address, a rider already on the trip — would otherwise fail in silence,
 * which is the one thing this move must not introduce.
 */
@Composable
internal fun InviteByEmailForm(
    email: String,
    error: String?,
    onEmailChange: (String) -> Unit,
) {
    Column {
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
        Text(
            text = stringResource(R.string.invite_hint),
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted,
            modifier = Modifier.padding(top = 10.dp),
        )
        error?.let { HudError(it, modifier = Modifier.padding(top = 10.dp)) }
    }
}

/**
 * When the invite can be sent.
 *
 * One line, written down on its own because it is the half of the dialog a
 * Compose test cannot reach — see [InviteByEmailDialog] — and because it is
 * the rule the move must not have changed.
 */
internal object InviteRules {

    /** Nothing typed, or a send already out, are both answers already known. */
    fun canSend(email: String, pending: Boolean): Boolean = email.isNotBlank() && !pending
}

/** Roughly four rows of chips before the box starts scrolling. */
private val SUGGESTIONS_MAX_HEIGHT = 180.dp

@Preview(showBackground = true, backgroundColor = 0xFFF8F9FA)
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
                        speedMps = 24.0,
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
                        speedMps = null,
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
            onOpenMap = {},
            onInviteEmailChange = {},
            onSendInvite = {},
            onUseSuggestion = {},
            onShowQr = {},
            onEditTrip = {},
            onEndTrip = {},
            onBack = {},
        )
    }
}
