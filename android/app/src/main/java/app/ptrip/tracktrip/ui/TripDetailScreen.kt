package app.ptrip.tracktrip.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.HudAmber
import app.ptrip.tracktrip.ui.theme.HudCyan
import app.ptrip.tracktrip.ui.theme.HudDanger
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudSectionHeader
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudText
import app.ptrip.tracktrip.ui.theme.HudTextDim
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import app.ptrip.tracktrip.ui.theme.riderColor

@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    onInviteEmailChange: (String) -> Unit,
    onSendInvite: () -> Unit,
    onEndTrip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trip = state.trip
    var confirmingEnd by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = trip?.name ?: stringResource(R.string.untitled_trip),
                    style = MaterialTheme.typography.titleMedium,
                    color = HudAmber,
                )
                if (trip != null) {
                    Text(
                        text = buildString {
                            append(
                                stringResource(
                                    if (trip.isActive) R.string.status_active
                                    else R.string.status_ended
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
                        color = if (trip.isActive) HudCyan else HudTextDim,
                    )
                }
            }
        }

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
                        onEmailChange = onInviteEmailChange,
                        onSend = onSendInvite,
                    )
                }
            }
        }

        if (trip != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
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
                            Button(
                                onClick = { confirmingEnd = false; onEndTrip() },
                                enabled = !state.endPending,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.endPending) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Text(stringResource(R.string.end_trip_yes))
                                }
                            }
                            TextButton(onClick = { confirmingEnd = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    } else {
                        TextButton(
                            onClick = { confirmingEnd = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) {
                            Text(stringResource(R.string.end_trip), color = HudDanger)
                        }
                    }
                }
            }
        }
    }
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
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    HudSurface(accent = HudCyan.copy(alpha = 0.4f)) {
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
            Button(onClick = onSend, enabled = email.isNotBlank() && !pending) {
                if (pending) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(stringResource(R.string.send_invite))
                }
            }
        }
        Text(
            text = stringResource(R.string.invite_hint),
            style = MaterialTheme.typography.labelSmall,
            color = HudTextDim,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF04070B)
@Composable
private fun TripDetailPreview() {
    TracktripTheme {
        TripDetailScreen(
            state = TripDetailUiState(
                loading = false,
                trip = Trip(1, "Chiang Mai loop", 1, "active", "owner"),
                members = listOf(
                    MemberPosition(1, "Owner", null, "owner", true, 18.79, 98.98, 91, "now"),
                    MemberPosition(2, "Friend", null, "member", true, null, null, null, null),
                ),
            ),
            onInviteEmailChange = {},
            onSendInvite = {},
            onEndTrip = {},
            onBack = {},
        )
    }
}
