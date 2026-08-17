package app.ptrip.tracktrip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A heads-up-display palette: near-black glass, amber instrumentation, and a
 * cold cyan for anything live.
 *
 * Always dark, regardless of the system setting — a light variant of this
 * look would be a different design, not a recolour, and a rider's screen in
 * daylight is better served by brightness than by a white background.
 */
val HudBlack = Color(0xFF04070B)
val HudPanel = Color(0xFF0B1219)
val HudPanelRaised = Color(0xFF121C25)

/** Instrument amber — primary actions, headings, active state. */
val HudAmber = Color(0xFFFFB53F)
val HudAmberDim = Color(0xFF8A5F1E)

/** Arc-reactor cyan — anything live: sharing, tracking, a fresh fix. */
val HudCyan = Color(0xFF5FE3F0)
val HudCyanDim = Color(0xFF1F5B63)

val HudText = Color(0xFFDCE6EE)
val HudTextDim = Color(0xFF7A8B99)
val HudDanger = Color(0xFFFF6B5A)

/** Hairlines and panel edges. */
val HudLine = Color(0xFF1E2C38)

private val HudColorScheme = darkColorScheme(
    primary = HudAmber,
    onPrimary = HudBlack,
    primaryContainer = HudAmberDim,
    onPrimaryContainer = HudBlack,
    secondary = HudCyan,
    onSecondary = HudBlack,
    secondaryContainer = HudCyanDim,
    onSecondaryContainer = HudText,
    background = HudBlack,
    onBackground = HudText,
    surface = HudPanel,
    onSurface = HudText,
    surfaceVariant = HudPanelRaised,
    onSurfaceVariant = HudTextDim,
    outline = HudLine,
    outlineVariant = HudLine,
    error = HudDanger,
    onError = HudBlack,
)

/**
 * Wide letter-spacing on labels and headings is most of what makes this read
 * as instrumentation rather than as a stock Material app.
 */
private val HudTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Light,
            letterSpacing = 3.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.Light,
            letterSpacing = 2.sp,
        ),
        titleMedium = base.titleMedium.copy(letterSpacing = 1.sp),
        labelLarge = base.labelLarge.copy(letterSpacing = 1.5.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 1.5.sp),
    )
}

/** Monospaced digits, for readouts that shouldn't jitter as they update. */
val HudReadoutStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Light,
    letterSpacing = 1.sp,
)

@Composable
fun TracktripTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = HudColorScheme,
        typography = HudTypography,
        content = content,
    )
}
