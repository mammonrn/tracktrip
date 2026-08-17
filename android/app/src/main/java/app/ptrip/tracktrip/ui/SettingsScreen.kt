package app.ptrip.tracktrip.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.ui.theme.HudAvatar
import app.ptrip.tracktrip.ui.theme.HudChevronIcon
import app.ptrip.tracktrip.ui.theme.HudClockIcon
import app.ptrip.tracktrip.ui.theme.HudConfirmDialog
import app.ptrip.tracktrip.ui.theme.HudCyan
import app.ptrip.tracktrip.ui.theme.HudDangerButton
import app.ptrip.tracktrip.ui.theme.HudDivider
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudGlobeIcon
import app.ptrip.tracktrip.ui.theme.HudText
import app.ptrip.tracktrip.ui.theme.HudTextDim
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.TracktripTheme

/** Which expandable row is open. Empty means none — only one opens at a time. */
private const val SECTION_NONE = ""
private const val SECTION_LANGUAGE = "language"
private const val SECTION_SHARING = "sharing"

/**
 * Everything that isn't a trip: who the rider is signed in as, the two
 * preferences the app has so far, and the way out.
 *
 * Signing out lives here rather than on the trip list — it is the one
 * destructive control in the app, and a screen the rider has to deliberately
 * open is the right place for it.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    displayName: String?,
    email: String?,
    photoUrl: String?,
    onOpenProfile: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onSharingDurationChange: (SharingDuration) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openSection by rememberSaveable { mutableStateOf(SECTION_NONE) }
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }

    fun toggle(section: String) {
        openSection = if (openSection == section) SECTION_NONE else section
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        HudTopBar(
            title = stringResource(R.string.settings_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
        )

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            HudDivider()

            ProfileRow(
                displayName = displayName,
                email = email,
                photoUrl = photoUrl,
                onClick = onOpenProfile,
            )

            HudDivider()

            SettingRow(
                icon = { HudGlobeIcon() },
                label = stringResource(R.string.settings_language),
                value = stringResource(state.language.labelRes),
                expanded = openSection == SECTION_LANGUAGE,
                onClick = { toggle(SECTION_LANGUAGE) },
            )
            AnimatedVisibility(visible = openSection == SECTION_LANGUAGE) {
                Column {
                    AppLanguage.entries.forEach { language ->
                        OptionRow(
                            label = stringResource(language.labelRes),
                            selected = state.language == language,
                            onClick = { onLanguageChange(language) },
                        )
                    }
                }
            }

            HudDivider()

            SettingRow(
                icon = { HudClockIcon() },
                label = stringResource(R.string.settings_sharing_duration),
                value = stringResource(state.defaultSharingDuration.labelRes),
                expanded = openSection == SECTION_SHARING,
                onClick = { toggle(SECTION_SHARING) },
            )
            AnimatedVisibility(visible = openSection == SECTION_SHARING) {
                Column {
                    SharingDuration.entries.forEach { duration ->
                        OptionRow(
                            label = stringResource(duration.labelRes),
                            selected = state.defaultSharingDuration == duration,
                            onClick = { onSharingDurationChange(duration) },
                        )
                    }
                }
            }

            HudDivider()
        }

        HudDangerButton(
            text = stringResource(R.string.sign_out),
            onClick = { confirmingSignOut = true },
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp),
        )
    }

    // Signing out drops the stored tokens, so the way back is a full Google
    // sign-in. Worth one tap of confirmation.
    if (confirmingSignOut) {
        HudConfirmDialog(
            title = stringResource(R.string.sign_out_confirm_title),
            message = stringResource(R.string.sign_out_confirm_message),
            confirmText = stringResource(R.string.sign_out),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                confirmingSignOut = false
                onSignOut()
            },
            onDismiss = { confirmingSignOut = false },
        )
    }
}

/** Who is signed in, and the way into editing it. */
@Composable
private fun ProfileRow(
    displayName: String?,
    email: String?,
    photoUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudAvatar(name = displayName, photoUrl = photoUrl)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = displayName ?: stringResource(R.string.signed_in),
                style = MaterialTheme.typography.titleMedium,
                color = HudText,
            )
            Text(
                text = email ?: stringResource(R.string.settings_profile),
                style = MaterialTheme.typography.labelSmall,
                color = HudTextDim,
            )
        }
        HudChevronIcon()
    }
}

/** A settings row: icon, label, current value, and a chevron that turns when open. */
@Composable
private fun SettingRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val chevronTurn by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevron",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = HudText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = HudCyan,
        )
        HudChevronIcon(modifier = Modifier.padding(start = 8.dp).rotate(chevronTurn))
    }
}

/** One choice under an expanded row. */
@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 38.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudDot(color = if (selected) HudCyan else HudTextDim.copy(alpha = 0.4f))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) HudText else HudTextDim,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@get:StringRes
private val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.THAI -> R.string.language_thai
        AppLanguage.ENGLISH -> R.string.language_english
    }

@get:StringRes
private val SharingDuration.labelRes: Int
    get() = when (this) {
        SharingDuration.THIRTY_MINUTES -> R.string.duration_30_minutes
        SharingDuration.ONE_HOUR -> R.string.duration_1_hour
        SharingDuration.FOUR_HOURS -> R.string.duration_4_hours
        SharingDuration.UNTIL_STOPPED -> R.string.duration_until_stopped
    }

@Preview(showBackground = true, backgroundColor = 0xFF0B0E1A)
@Composable
private fun SettingsPreview() {
    TracktripTheme {
        SettingsScreen(
            state = SettingsUiState(),
            displayName = "Poom",
            email = "rider@gmail.com",
            photoUrl = null,
            onOpenProfile = {},
            onLanguageChange = {},
            onSharingDurationChange = {},
            onSignOut = {},
            onBack = {},
        )
    }
}
