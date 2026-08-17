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
 * The whole app's palette, in one file.
 *
 * A heads-up display: deep navy glass, amber instrumentation, and a cold cyan
 * for anything live. Nothing outside this file should name a colour — screens
 * ask for [MaterialTheme.colorScheme] or for one of the `Hud*` values below,
 * so re-tuning the look is an edit here and nowhere else.
 *
 * Always dark, regardless of the system setting — a light variant of this look
 * would be a different design, not a recolour, and a rider's screen in
 * daylight is better served by brightness than by a white background.
 */

/** Deep navy, near-black. The ground everything else sits on. */
val HudBlack = Color(0xFF0B0E1A)

/** One step up from the background: cards, panels, rows. */
val HudPanel = Color(0xFF12172B)

/** A panel that needs to read as raised — inputs, selected rows. */
val HudPanelRaised = Color(0xFF1A2140)

/** Instrument amber — primary actions, headings, active state. */
val HudAmber = Color(0xFFFFB627)
val HudAmberDim = Color(0xFF7A5410)

/** Arc-reactor cyan — secondary actions, links, anything live. */
val HudCyan = Color(0xFF4FD8EB)
val HudCyanDim = Color(0xFF1E5C66)

val HudText = Color(0xFFE2E9F5)
val HudTextDim = Color(0xFF7C88A8)

/** Warnings and destructive actions: ember, not fire-engine red. */
val HudDanger = Color(0xFFFF6B4A)

/**
 * Hairlines, panel edges and dividers.
 *
 * Translucent accent rather than a grey: a HUD's lines are light drawn over
 * the scene, and at a quarter opacity they separate rows without ever
 * competing with the content inside them.
 */
val HudLine = HudCyan.copy(alpha = 0.25f)

/** The amber counterpart, for edges that belong to a primary element. */
val HudLineAmber = HudAmber.copy(alpha = 0.25f)

private val HudColorScheme = darkColorScheme(
    primary = HudAmber,
    onPrimary = HudBlack,
    primaryContainer = HudAmberDim,
    onPrimaryContainer = HudText,
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
 * Headings run monospaced and widely tracked, body copy stays in the default
 * sans. That split is most of what makes the app read as instrumentation
 * rather than as a stock Material app, and it needs no bundled font — both
 * families are already on the device.
 */
private val HudTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            letterSpacing = 4.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            letterSpacing = 3.sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Light,
            letterSpacing = 2.sp,
        ),
        titleMedium = base.titleMedium.copy(letterSpacing = 1.sp),
        labelLarge = base.labelLarge.copy(letterSpacing = 1.5.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 1.5.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 1.sp),
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
