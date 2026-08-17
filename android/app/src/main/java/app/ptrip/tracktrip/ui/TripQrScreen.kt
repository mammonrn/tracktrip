package app.ptrip.tracktrip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.JoinCode
import app.ptrip.tracktrip.ui.theme.AppCodeStyle
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.AppTextMuted
import app.ptrip.tracktrip.ui.theme.HudError
import app.ptrip.tracktrip.ui.theme.HudLoading
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.HudQrCode
import app.ptrip.tracktrip.ui.theme.HudSurface
import app.ptrip.tracktrip.ui.theme.HudTopBar
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay

private const val MILLIS_PER_SECOND = 1000L

/**
 * The QR code someone else scans to join this trip.
 *
 * The code is short-lived, so the countdown is not decoration: it is the
 * difference between holding up a screen that works and one that quietly
 * stopped working a minute ago.
 */
@Composable
fun TripQrScreen(
    joinCode: JoinCode?,
    loading: Boolean,
    error: String?,
    onGenerate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expiresAtMillis = remember(joinCode) { joinCode?.expiresAt?.let(::parseInstantMillis) }

    // Ticks once a second while a code is on screen, and stops when it lapses.
    var now by remember(joinCode) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMillis) {
        while (expiresAtMillis != null && System.currentTimeMillis() < expiresAtMillis) {
            now = System.currentTimeMillis()
            delay(MILLIS_PER_SECOND)
        }
        now = System.currentTimeMillis()
    }

    val remainingMillis = expiresAtMillis?.minus(now) ?: 0L
    val live = joinCode != null && remainingMillis > 0

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HudTopBar(
            title = stringResource(R.string.qr_invite_title),
            onBack = onBack,
            backContentDescription = stringResource(R.string.back),
        )

        error?.let { HudError(it) }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                loading && joinCode == null -> HudLoading()

                joinCode == null -> Text(
                    text = stringResource(R.string.qr_invite_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )

                else -> HudSurface(accent = if (live) AppPrimary.copy(alpha = 0.5f) else AppTextMuted) {
                    if (live) {
                        HudQrCode(
                            content = joinLinkFor(joinCode.code),
                            contentDescription = stringResource(R.string.qr_invite_title),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.qr_expired),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        )
                    }

                    Text(
                        text = spacedCode(joinCode.code),
                        style = MaterialTheme.typography.headlineSmall.merge(AppCodeStyle),
                        color = if (live) AppText else AppTextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                    Text(
                        text = if (live) {
                            stringResource(R.string.qr_expires_in, formatRemaining(remainingMillis))
                        } else {
                            stringResource(R.string.qr_expired_short)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }

        HudPrimaryButton(
            text = stringResource(
                if (joinCode == null) R.string.qr_generate else R.string.qr_generate_again
            ),
            onClick = onGenerate,
            loading = loading,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        )
    }
}

/** `ABCD2345` reads back more reliably over the phone as `ABCD 2345`. */
private fun spacedCode(code: String): String =
    if (code.length == 8) "${code.take(4)} ${code.drop(4)}" else code

private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * The server's timestamp, in millis. Null if it isn't parseable — which shows
 * up as an already-expired code rather than a crash, and the rider's next
 * move (generate another) is the right one either way.
 */
private fun parseInstantMillis(iso: String): Long? = try {
    Instant.parse(iso).toEpochMilli()
} catch (e: DateTimeParseException) {
    null
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F9FA)
@Composable
private fun TripQrPreview() {
    TracktripTheme {
        TripQrScreen(
            joinCode = JoinCode(
                tripId = 1,
                code = "K7M2QRX9",
                expiresAt = Instant.now().plusSeconds(600).toString(),
            ),
            loading = false,
            error = null,
            onGenerate = {},
            onBack = {},
        )
    }
}
