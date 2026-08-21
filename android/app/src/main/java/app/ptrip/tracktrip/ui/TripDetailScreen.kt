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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.ptrip.tracktrip.ui.theme.HudConfirmDialog
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
    /** Every address one press of Invite carries: chips picked, addresses typed. */
    onSendInvites: (List<String>) -> Unit,
    /** One past companion, added on one tap of their chip. */
    onInviteSuggestion: (SuggestedInvitee) -> Unit,
    onShowQr: () -> Unit,
    /**
     * Opens the trip's own details — its name, and the way through to the
     * route. Owner-only, so the button that calls it is too.
     */
    onEditTrip: () -> Unit,
    onEndTrip: () -> Unit,
    /** A member's way off the trip — see the button for why the owner has none. */
    onLeaveTrip: () -> Unit = {},
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
    var confirmingLeave by rememberSaveable { mutableStateOf(false) }

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

    // On a finished trip this is what the member rows say instead of a report
    // age. Read once for the whole list rather than per row: it is the same
    // sentence on every one of them.
    val tripDates = tripRanOn(trip, nowMs)

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
                MemberRow(
                    member = member,
                    // One of the two, never both and never neither: the age of
                    // this rider's last report while the trip is on, and the
                    // days the trip ran once it is over. See [tripRanOn].
                    fixAgeMinutes = if (tripDates == null) {
                        FixAge.minutesAgo(member.recordedAt, nowMs)
                    } else {
                        null
                    },
                    tripDates = tripDates,
                )
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
                        sent = state.inviteSent,
                        error = state.error,
                        shortcuts = state.suggestionWindow,
                        onEmailChange = onInviteEmailChange,
                        onSendInvites = onSendInvites,
                        onInviteSuggestion = onInviteSuggestion,
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
                } else if (trip.isActive) {
                    // A member's way off the trip, where the owner's End is.
                    //
                    // The two are not the same control renamed. Ending closes
                    // the ride for the whole group and only its owner may;
                    // leaving takes one rider off it and the owner may not,
                    // because a trip with no owner is one nobody can end,
                    // invite to or rename.
                    //
                    // It is here at all because one active trip per rider made
                    // it necessary: without a door of their own, a rider whose
                    // host went home without pressing End could not start a
                    // trip, could not accept another invitation, and had
                    // nothing they could do about it.
                    //
                    // Not offered on a finished trip: nothing is holding them
                    // there, and leaving would take them off a completed
                    // ride's roster and off its podium.
                    HudDivider(modifier = Modifier.padding(top = 4.dp))

                    HudDangerButton(
                        text = stringResource(R.string.leave_trip),
                        onClick = { confirmingLeave = true },
                        quiet = true,
                        loading = state.leavePending,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // A dialog rather than the inline confirm End uses, for the same reason
    // signing out has one: this is the rider's own irreversible act, and
    // getting back on takes somebody else issuing a fresh invitation.
    if (confirmingLeave) {
        HudConfirmDialog(
            title = stringResource(R.string.leave_trip_confirm_title),
            message = stringResource(R.string.leave_trip_confirm_message),
            confirmText = stringResource(R.string.leave_trip),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                confirmingLeave = false
                onLeaveTrip()
            },
            onDismiss = { confirmingLeave = false },
        )
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
 * The days a finished trip ran, as one line, or null while it is still on.
 *
 * Null for a running trip, for a trip that has not loaded, and for a trip
 * whose stamps this build cannot read — in every one of those the member rows
 * keep the report age they always showed, which is the useful number while
 * anybody is still reporting.
 *
 * The dates themselves are numeric and so carry no language, but the sentence
 * around them — "Created … – Ended …" — is a translated resource, and the
 * locale is still keyed on so the `remember` is invalidated when a rider
 * changes the app's language. It is read off the `Context` rather than from
 * `Locale.getDefault()` because the switch runs through `AppCompatDelegate`,
 * which puts the choice on the activity's configuration; the process default
 * would be a step behind.
 */
@Composable
private fun tripRanOn(trip: Trip?, nowMs: Long): String? {
    if (trip == null || trip.isActive) return null

    val locale = LocalContext.current.resources.configuration.locales[0]
    val span = remember(trip.createdAt, trip.endedAt, nowMs, locale) {
        TripDates.span(
            createdAtIso = trip.createdAt,
            endedAtIso = trip.endedAt,
            nowMs = nowMs,
            locale = locale,
            // Numeric here, and only here. Two dates share one line under the
            // rider's name, so what the row needs from a date is a width that
            // does not depend on which month it is or what language the phone
            // is in. See [TripDates.Style].
            style = TripDates.Style.NUMERIC,
        )
    } ?: return null

    return span.ended
        ?.let { stringResource(R.string.trip_dates_created_ended, span.created, it) }
        ?: stringResource(R.string.trip_dates_created, span.created)
}

/**
 * The tag `EndedTripDatesTest` measures the card by.
 *
 * On the card rather than on any text inside it, and that is the whole point:
 * the test this exists for asks whether the *card* grew when a third line
 * arrived, and the first version of it reached for the rider's name instead —
 * a `Text` of fixed height that could not have answered the question either
 * way. It reported "no growth" through a change that added a line.
 */
internal const val MEMBER_CARD_TAG = "member-card"

/**
 * The caption on a member row: the role above the name, and the dates below it.
 *
 * A member card on a finished trip stacks three lines where a running one
 * stacks two, so the two lines that are not the name come down from
 * `labelSmall`'s 11sp to 10sp. That is as far as the shrinking goes, and the
 * reason it stops there is Thai.
 *
 * ## Why the leading is not squeezed further
 *
 * The obvious next move — a tighter `lineHeight` with
 * `LineHeightStyle.Trim.Both` and `includeFontPadding = false` — was tried and
 * measured. It bought 3dp of the 18, and it put the app's other language at
 * risk to do it. Thai stacks up to two marks *above* the base character, and
 * both strings this caption draws have them: `เจ้าของทริป` carries a tone mark
 * and an upper vowel, `ยังไม่ได้แชร์ตำแหน่ง` carries four. The half-leading
 * above the first line is exactly where those marks live, and a clipped tone
 * mark is a different word — a worse bug than a taller card, and one that would
 * only ever appear on a Thai phone, which is most of them.
 *
 * So the leading stays at roughly 1.4x the size, which is what Thai needs; the
 * card grows by about one small line; and `EndedTripDatesTest` measures that
 * cost rather than pretending there is none.
 */
private val MEMBER_CAPTION_SIZE = 10.sp
private val MEMBER_CAPTION_LEADING = 14.sp

/**
 * One rider on the trip: their colour, name, role, and how their phone is
 * doing.
 *
 * [fixAgeMinutes] is here because of what went wrong without it. The battery
 * is not a live reading — it is whatever came up with this rider's last
 * position report — and a bare "37%" reads as "now". Saying when the number is
 * from turns a reading a rider might argue with into one they can act on.
 *
 * ## Two layouts, and which trip gets which
 *
 * A **running** trip draws the name, then a caption under it carrying the role
 * and the age of the last report. That age is the most useful thing on the row
 * while anybody is still reporting, it is three or four characters wide, and it
 * sits beside the role without crowding it.
 *
 * A **finished** trip — [tripDates] non-null — draws the role *above* the name
 * as its own small label, and the days the trip ran below it. Two full dates
 * and a dash are far too wide to share a line with a role, and moving the role
 * above rather than beside is what frees the whole width for them. It reads
 * better too: a label over a name is how a caption is normally worn, and on a
 * finished trip the row is a record of who was there rather than a live
 * readout.
 */
@Composable
private fun MemberRow(
    member: MemberPosition,
    fixAgeMinutes: Long? = null,
    tripDates: String? = null,
) {
    val caption = MaterialTheme.typography.labelSmall.copy(
        fontSize = MEMBER_CAPTION_SIZE,
        lineHeight = MEMBER_CAPTION_LEADING,
    )

    // The role, and the one thing that can qualify it: a rider who never
    // reported at all. True on a finished trip as much as on a running one —
    // it says the ride happened without them ever appearing on the map.
    val role = buildString {
        append(stringResource(if (member.isOwner) R.string.role_owner else R.string.role_member))
        if (!member.hasPosition) {
            append(" · ")
            append(stringResource(R.string.not_tracking_yet))
        }
    }

    HudSurface(modifier = Modifier.testTag(MEMBER_CARD_TAG)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudDot(color = riderColor(member.userId))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                if (tripDates != null) {
                    Text(
                        text = role,
                        style = caption,
                        color = AppTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = member.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = if (tripDates != null) {
                        tripDates
                    } else {
                        buildString {
                            append(role)
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
                        }
                    },
                    style = if (tripDates != null) caption else MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
                    // One line either way. This is a list somebody scrolls, and
                    // a caption that wrapped would make one row taller than the
                    // rest for the length of somebody's name.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            member.batteryPct?.let { HudBatteryReadout(percent = it) }
        }
    }
}

/**
 * The three ways onto a trip, and the shortcut above them.
 *
 * ## The email field is behind a button
 *
 * It was always on screen: a text field, a Send button and a line of guidance,
 * sitting between the shortcuts and the QR and share buttons. That is a lot of
 * panel for the least-used of the three ways in — the QR is for somebody
 * standing next to you and the link is for a chat window, and both are one
 * press. Typing a Google address from memory is the fallback, and a fallback
 * does not need to be the tallest thing on the screen.
 *
 * ## The shortcuts are five, and a number
 *
 * "Ridden with before" drew everybody, in a box that scrolled. For the groups
 * this app was written for that is a wall of names above the buttons that
 * actually add people. [InviteShortcuts] cuts it to five and puts the rest
 * behind "+N more", which opens them in place — no second screen, because a
 * list of chips is not worth a screen.
 *
 * ## A chip is now the invite, not a shortcut to a form
 *
 * Tapping a name used to fill the email field in and open the dialog on it.
 * A name from the rider's own past trips does not need a form and a second
 * confirmation, so a tap sends it. The chip then sinks past the window and the
 * sixth name rises into its place, which is what makes working down a group
 * the same tap repeated rather than four screens each time.
 *
 * Internal rather than private so `InviteShortcutsTest` can compose it on its
 * own. The screen around it runs two `while (true) { delay(...) }` effects —
 * the members poll and the fix-age tick — and a dialog opened over those never
 * lets a Compose test reach idle. The panel has no such effects.
 */
@Composable
internal fun InvitePanel(
    email: String,
    pending: Boolean,
    /** The addresses the last press actually got onto the trip. */
    sent: List<String>,
    /** The screen's error line, which a dialog would otherwise cover. */
    error: String?,
    shortcuts: InviteShortcuts.Window,
    onEmailChange: (String) -> Unit,
    onSendInvites: (List<String>) -> Unit,
    onInviteSuggestion: (SuggestedInvitee) -> Unit,
    onShowQr: () -> Unit,
    onShareLink: () -> Unit,
) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var panelExpanded by rememberSaveable { mutableStateOf(false) }

    // Who the dialog is holding. Here rather than inside the dialog because
    // the Invite button lives in the dialog's own confirm slot, which is a
    // sibling of the form rather than inside it — and because a selection that
    // died with the dialog would be lost to a rotation mid-choice.
    var picked by rememberSaveable { mutableStateOf(setOf<Long>()) }

    // Invites that landed close the dialog and clear the choice. Keyed on what
    // came back rather than on a flag flipped inside the press: the send is a
    // round trip, and only the server's answer is worth closing on — a press
    // where nothing landed leaves the rider in front of what they chose, with
    // the reason underneath it.
    LaunchedEffect(sent) {
        if (sent.isNotEmpty()) {
            typing = false
            picked = emptySet()
        }
    }

    HudSurface(accent = AppPrimary.copy(alpha = 0.4f)) {
        // Riders from past trips, one tap instead of an email typed from
        // memory. Absent for a first trip, which is when there is nobody to
        // suggest — so the row simply isn't there rather than being empty.
        if (shortcuts.all.isNotEmpty()) {
            Text(
                text = stringResource(R.string.invite_suggestions),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ShortcutChips(
                shortcuts = shortcuts,
                expanded = panelExpanded,
                onExpand = { panelExpanded = true },
                selected = emptySet(),
                onChipClick = onInviteSuggestion,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        InviteSentLine(sent)

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
            shortcuts = shortcuts,
            picked = picked,
            onPickedChange = { picked = it },
            onEmailChange = onEmailChange,
            onSend = {
                onSendInvites(
                    InviteRules.addresses(email, shortcuts.all.filter { it.userId in picked })
                )
            },
            onDismiss = { if (!pending) typing = false },
        )
    }
}

/**
 * What the last press got onto the trip.
 *
 * On the panel rather than in the dialog, because the dialog closes on the
 * server's answer and the answer has to outlive it. One name is drawn as the
 * name — which is the confirmation a rider is actually checking — and several
 * as a count, because five addresses on one line is not something anybody
 * reads.
 */
@Composable
private fun InviteSentLine(sent: List<String>) {
    if (sent.isEmpty()) return

    Text(
        text = sent.singleOrNull()
            ?.let { stringResource(R.string.invite_sent_to, it) }
            ?: stringResource(R.string.invite_sent_count, sent.size),
        style = MaterialTheme.typography.labelSmall,
        color = AppPrimary,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/**
 * The five chips, and the one that opens the rest.
 *
 * Shared by the panel and the dialog, which is the point: they are the same
 * shortcut and a rider should not have to learn it twice. What differs is what
 * a tap means — an invite out here, a choice held in there — so the caller
 * passes [selected] and [onChipClick] and this draws whichever it is told.
 *
 * "+N more" expands in place rather than opening a list of its own. A screen
 * for a handful of chips is a screen to dismiss, and the chips are already in
 * a box that scrolls when it needs to.
 */
@Composable
private fun ShortcutChips(
    shortcuts: InviteShortcuts.Window,
    expanded: Boolean,
    onExpand: () -> Unit,
    selected: Set<Long>,
    onChipClick: (SuggestedInvitee) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawn = if (expanded) shortcuts.all else shortcuts.shown

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = SUGGESTIONS_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        drawn.forEach { invitee ->
            HudChip(
                text = invitee.label,
                onClick = { onChipClick(invitee) },
                selected = invitee.userId in selected,
            )
        }
        if (!expanded && shortcuts.moreCount > 0) {
            HudChip(
                text = stringResource(R.string.invite_suggestions_more, shortcuts.moreCount),
                onClick = onExpand,
                accent = AppTextMuted,
            )
        }
    }
}

/**
 * The email invite, as the dialog it now is.
 *
 * A shell around [InviteByEmailForm], which is where everything that used to
 * sit open on the panel actually lives. Split in two because the form can be
 * composed and asserted on and this cannot: a text field inside a dialog
 * window never lets a Compose test reach idle under Robolectric, which is why
 * `StopNameDialog` has never been asserted on either. The shell is placement;
 * the form is the part with behaviour in it.
 */
@Composable
private fun InviteByEmailDialog(
    email: String,
    pending: Boolean,
    error: String?,
    shortcuts: InviteShortcuts.Window,
    picked: Set<Long>,
    onPickedChange: (Set<Long>) -> Unit,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.invite_by_email)) },
        text = {
            InviteByEmailForm(
                email = email,
                error = error,
                shortcuts = shortcuts,
                picked = picked,
                onPickedChange = onPickedChange,
                onEmailChange = onEmailChange,
            )
        },
        confirmButton = {
            HudPrimaryButton(
                text = stringResource(R.string.send_invite),
                onClick = onSend,
                // Nothing chosen and nothing typed, or a send already in
                // flight, are both requests whose answer is known, and a
                // pressable button that does nothing reads as a send that
                // silently failed.
                enabled = InviteRules.canSend(
                    typed = email,
                    picked = shortcuts.all.filter { it.userId in picked },
                    pending = pending,
                ),
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
 * The chips, the field, and the two things that belong under it.
 *
 * ## Several riders, one press
 *
 * The dialog used to be one address and one Invite, so adding four people was
 * four rounds of open, type, send, watch it close — for what is one intention.
 * Both halves are plural now: the chips are a selection that is held rather
 * than acted on, and the field takes a list. One press of Invite sends
 * everything, and [InviteRules.addresses] is what turns the two into one.
 *
 * The chips are the same five-and-a-number the panel draws, for the same
 * reason they are on the panel at all — the addresses a rider wants are
 * usually already known to the app, and typing them again from memory is the
 * thing this is here to avoid.
 *
 * ## Why the error is drawn here too
 *
 * [error] is here rather than only on the screen behind, because a dialog
 * covers the screen behind. An invite refused with the dialog up — a malformed
 * address, a rider already on the trip — would otherwise fail in silence,
 * which is the one thing this must not introduce. With several addresses in
 * one press it also has to name *which* were refused, which is why the view
 * model builds the line per address rather than passing one message on.
 */
@Composable
internal fun InviteByEmailForm(
    email: String,
    error: String?,
    shortcuts: InviteShortcuts.Window,
    picked: Set<Long>,
    onPickedChange: (Set<Long>) -> Unit,
    onEmailChange: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column {
        if (shortcuts.all.isNotEmpty()) {
            Text(
                text = stringResource(R.string.invite_suggestions),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ShortcutChips(
                shortcuts = shortcuts,
                expanded = expanded,
                onExpand = { expanded = true },
                selected = picked,
                onChipClick = { invitee ->
                    onPickedChange(
                        if (invitee.userId in picked) picked - invitee.userId
                        else picked + invitee.userId
                    )
                },
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.invite_email_label)) },
            // Not single-line any more: several addresses is the ordinary case
            // now, and a rider pasting three of them out of a chat window
            // should be able to see all three.
            singleLine = false,
            maxLines = 3,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done,
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
 * What one press of Invite sends, and when it may be pressed.
 *
 * Written down on its own because it is the half of the dialog a Compose test
 * cannot reach — see [InviteByEmailDialog] — and because it is now the rule
 * that turns two controls into one request list.
 */
internal object InviteRules {

    /**
     * Every address one press carries, in the order the rider chose them.
     *
     * Chips first, then whatever was typed: the chips were picked deliberately
     * from a list the app offered, and if a rate limit stops the run halfway
     * it should stop after the certain ones rather than before them.
     *
     * The field is split on commas, semicolons and any whitespace, because
     * those are what an address list arrives as when it is pasted out of a
     * chat window or an email client, and a rider who separates three
     * addresses with spaces has not made a mistake worth an error message.
     *
     * Deduplicated case-insensitively, since a chip and a typed address are
     * easily the same person and the backend would answer the second one with
     * "already invited" — a refusal the rider caused by being thorough.
     */
    fun addresses(typed: String, picked: List<SuggestedInvitee>): List<String> {
        val fromChips = picked.map { it.email }
        val fromField = typed.split(*SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }

        val seen = mutableSetOf<String>()
        return (fromChips + fromField).filter { seen.add(it.lowercase()) }
    }

    /** Nothing chosen and nothing typed, or a send already out, are both answers already known. */
    fun canSend(typed: String, picked: List<SuggestedInvitee>, pending: Boolean): Boolean =
        !pending && addresses(typed, picked).isNotEmpty()

    private val SEPARATORS = arrayOf(",", ";", " ", "\n", "\t", "\r")
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
            onSendInvites = {},
            onInviteSuggestion = {},
            onShowQr = {},
            onEditTrip = {},
            onEndTrip = {},
            onBack = {},
        )
    }
}
