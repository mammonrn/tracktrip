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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import android.os.Build
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.location.PhoneVendor
import app.ptrip.tracktrip.location.VendorBatteryAdvice
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.AppLine
import app.ptrip.tracktrip.ui.theme.AppOnPrimary
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppSurfaceAlt
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudAvatar
import app.ptrip.tracktrip.ui.theme.HudChevronIcon
import app.ptrip.tracktrip.ui.theme.HudClockIcon
import app.ptrip.tracktrip.ui.theme.HudConfirmDialog
import app.ptrip.tracktrip.ui.theme.HudDangerButton
import app.ptrip.tracktrip.ui.theme.HudDivider
import app.ptrip.tracktrip.ui.theme.HudDot
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudGlobeIcon
import app.ptrip.tracktrip.ui.theme.HudPinIcon
import app.ptrip.tracktrip.ui.theme.HudPowerIcon
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.TracktripTheme

/**
 * The sentence to show this phone's owner about their manufacturer's own
 * battery killer, or null when there is nothing extra to say.
 *
 * A written path rather than a deep link, deliberately — see
 * [VendorBatteryAdvice] for why a button here would work on one skin version
 * and throw on the next.
 */
private fun vendorAdviceRes(manufacturer: String?): Int? =
    when (VendorBatteryAdvice.forManufacturer(manufacturer)) {
        PhoneVendor.SAMSUNG -> R.string.settings_battery_vendor_samsung
        PhoneVendor.XIAOMI -> R.string.settings_battery_vendor_xiaomi
        PhoneVendor.OPPO -> R.string.settings_battery_vendor_oppo
        PhoneVendor.REALME -> R.string.settings_battery_vendor_realme
        PhoneVendor.VIVO -> R.string.settings_battery_vendor_vivo
        PhoneVendor.ONEPLUS -> R.string.settings_battery_vendor_oneplus
        PhoneVendor.HUAWEI -> R.string.settings_battery_vendor_huawei
        PhoneVendor.OTHER -> null
    }

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
    sharingTripId: Long?,
    onOpenProfile: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onSharingDurationChange: (SharingDuration) -> Unit,
    onToggleSharing: (Trip, Boolean) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openSection by rememberSaveable { mutableStateOf(SECTION_NONE) }
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }

    // Read here rather than passed in: it is a system setting, not app state,
    // and it can change while this screen is open — a rider can grant it from
    // Android's own settings and come straight back.
    val battery = rememberBatteryExemption()

    fun toggle(section: String) {
        openSection = if (openSection == section) SECTION_NONE else section
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        HudTopBar(
            title = stringResource(R.string.settings_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
        )

        state.error?.let { HudError(it) }

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

            // Android throttles a backgrounded app even with a foreground
            // service running, which on a long ride shows up as a rider's own
            // pin going stale. The row is here permanently, and not only in
            // the one-off prompt at the start of sharing, because a rider who
            // said no — or a phone that had the exemption revoked by a
            // system update — otherwise has nowhere to fix it from.
            SettingRow(
                // Tinted like every other row's icon: the shared default is
                // the danger red it wears on the sign-out button.
                icon = { HudPowerIcon(tint = AppText) },
                label = stringResource(R.string.settings_battery),
                value = stringResource(
                    if (battery.isExempt) {
                        R.string.settings_battery_unrestricted
                    } else {
                        R.string.settings_battery_optimised
                    }
                ),
                expanded = false,
                onClick = battery.ask,
            )
            Text(
                text = stringResource(
                    if (battery.isExempt) {
                        R.string.settings_battery_unrestricted_hint
                    } else {
                        R.string.settings_battery_optimised_hint
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // The manufacturer's own killer, which Android's exemption above
            // does not touch. Shown on the phones that have one and on no
            // others: a section that appeared on every phone with generic
            // advice would teach riders to skip it.
            vendorAdviceRes(Build.MANUFACTURER)?.let { adviceRes ->
                Text(
                    text = stringResource(R.string.settings_battery_vendor_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppText,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                Text(
                    text = stringResource(adviceRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            HudDivider()

            SharingSection(
                trips = state.activeTrips,
                loading = state.loadingTrips,
                sharingTripId = sharingTripId,
                pendingTripId = state.pendingTripId,
                onToggle = onToggleSharing,
            )

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

/**
 * Location sharing, one switch per running trip.
 *
 * A list rather than a single switch because nothing stops a rider being on
 * two running trips at once — the schema has no such constraint, and a
 * weekend group ride during a longer tour is the ordinary case. Only one can
 * be shared to at a time, though: the phone has one location, and the service
 * reports to the trip it was started for.
 */
@Composable
private fun SharingSection(
    trips: List<Trip>,
    loading: Boolean,
    sharingTripId: Long?,
    pendingTripId: Long?,
    onToggle: (Trip, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudPinIcon()
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.settings_sharing),
                style = MaterialTheme.typography.titleMedium,
                color = AppText,
            )
        }

        when {
            loading && trips.isEmpty() -> HudLoading()

            trips.isEmpty() -> Text(
                text = stringResource(R.string.sharing_no_active_trips),
                style = MaterialTheme.typography.bodySmall,
                color = AppTextMuted,
                modifier = Modifier.padding(start = 38.dp, bottom = 12.dp, end = 8.dp),
            )

            else -> trips.forEach { trip ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 38.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = trip.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (trip.id == sharingTripId) AppText else AppTextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    if (trip.id == pendingTripId) {
                        CircularProgressIndicator(
                            color = AppPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Switch(
                            checked = trip.id == sharingTripId,
                            onCheckedChange = { on -> onToggle(trip, on) },
                            enabled = pendingTripId == null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AppOnPrimary,
                                checkedTrackColor = AppPrimary,
                                uncheckedThumbColor = AppTextMuted,
                                uncheckedTrackColor = AppSurfaceAlt,
                                uncheckedBorderColor = AppLine,
                            ),
                        )
                    }
                }
            }
        }
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
                color = AppText,
            )
            Text(
                text = email ?: stringResource(R.string.settings_profile),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
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
            color = AppText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = AppPrimary,
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
        HudDot(color = if (selected) AppPrimary else AppTextMuted.copy(alpha = 0.4f))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) AppText else AppTextMuted,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@get:StringRes
private val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.SYSTEM -> R.string.language_system
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

@Preview(showBackground = true, backgroundColor = 0xFFF8F9FA)
@Composable
private fun SettingsPreview() {
    TracktripTheme {
        SettingsScreen(
            state = SettingsUiState(
                loadingTrips = false,
                activeTrips = listOf(Trip(1, "Chiang Mai loop", 1, "active", "owner")),
            ),
            displayName = "Poom",
            email = "rider@gmail.com",
            photoUrl = null,
            sharingTripId = 1,
            onOpenProfile = {},
            onLanguageChange = {},
            onSharingDurationChange = {},
            onToggleSharing = { _, _ -> },
            onSignOut = {},
            onBack = {},
        )
    }
}
