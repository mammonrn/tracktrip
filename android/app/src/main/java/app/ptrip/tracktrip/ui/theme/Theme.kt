package app.ptrip.tracktrip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The whole app's palette, in one file.
 *
 * A daylight map app: a near-white ground, white cards lifted by a soft
 * shadow, and one blue used only where something is actionable or live.
 * Nothing outside this file should name a colour — screens ask for
 * [MaterialTheme.colorScheme] or for one of the `App*` values below, so
 * re-tuning the look is an edit here and nowhere else.
 *
 * Always light, regardless of the system setting. A rider reads this screen
 * in sunlight with a helmet visor between them and it, and dark text on a
 * bright ground is what survives that; the previous dark instrument look did
 * not.
 */

/** The ground everything else sits on. Off-white, so white cards still read. */
val AppBackground = Color(0xFFF8F9FA)

/** Cards, panels, rows — one step *up* from the background, not down. */
val AppSurface = Color(0xFFFFFFFF)

/** A surface that needs to read as recessed: inputs, selected rows, chips. */
val AppSurfaceAlt = Color(0xFFF1F3F4)

/** The one accent: primary actions, active state, anything live. */
val AppPrimary = Color(0xFF1A73E8)

/** The same blue at low saturation, for tinted fills behind primary content. */
val AppPrimarySoft = Color(0xFFE8F0FE)

/** A disabled primary: still recognisably the button, plainly not pressable. */
val AppPrimaryDim = Color(0xFFA8C7FA)

/** Content on top of [AppPrimary]. */
val AppOnPrimary = Color(0xFFFFFFFF)

/** Body copy and headings. Near-black rather than black — 15:1 on the ground. */
val AppText = Color(0xFF202124)

/** Secondary copy: captions, labels, anything that supports the line above it. */
val AppTextMuted = Color(0xFF5F6368)

/** Warnings and destructive actions. */
val AppDanger = Color(0xFFD93025)

/**
 * It worked.
 *
 * Used by one thing — the success half of the app's snackbar — and defined
 * here anyway, because the rule this file states is that nothing outside it
 * names a colour. Google's own green rather than a brighter one: it sits at
 * the same weight as [AppDanger], so a success and a failure read as two
 * answers to the same question instead of one shout and one murmur.
 */
val AppSuccess = Color(0xFF188038)

/**
 * The route-progress bar's fill.
 *
 * ## Why this is not the primary blue
 *
 * The bar is drawn *on top of map tiles*, not on the app's own surfaces, and
 * OpenStreetMap's daylight style is already full of blue: every river, lake,
 * reservoir and coastal shelf. A blue bar over it disappeared on a real ride —
 * which is the bug this colour exists to fix — and dimming or thickening a
 * blue bar would not have helped, because the problem was hue, not weight.
 *
 * Orange is the complement of the theme's #1A73E8, so it is the one hue on
 * the wheel that OSM's palette has almost none of: its greens are vegetation,
 * its greys are roads and buildings, its beige is landuse. Kept clear of
 * [AppDanger]'s red as well — a progress bar that reads as an alarm is a
 * different wrong answer.
 */
val AppRouteProgress = Color(0xFFF57C00)

/**
 * The unfilled part of the bar. Near-black at low opacity rather than a grey:
 * it has to read over a pale field and over a dark forest alike, and a mid
 * grey vanished into both.
 */
val AppRouteProgressTrack = Color(0xFF202124)

/**
 * The white casing drawn behind the whole bar.
 *
 * The same trick every road atlas uses for a coloured route line: a white
 * outline underneath means the line never has to contrast with the map, only
 * with the outline. Without it there is always some tile — a beach, a car
 * park — that is close enough to the fill colour to swallow it.
 */
val AppRouteProgressCasing = Color(0xFFFFFFFF)

/** Hairlines, card edges and dividers. A real grey, not a tinted accent. */
val AppLine = Color(0xFFDADCE0)

/**
 * The corner radius everything shares.
 *
 * Cards and buttons alike: a standard rounded rectangle, not the full pill the
 * instrument look used. 12dp on a card, 12dp on a button, and the two sit
 * together without either one looking like a mistake.
 */
val AppCardShape: Shape = RoundedCornerShape(12.dp)

private val AppColorScheme = lightColorScheme(
    primary = AppPrimary,
    onPrimary = AppOnPrimary,
    primaryContainer = AppPrimarySoft,
    onPrimaryContainer = AppPrimary,
    secondary = AppPrimary,
    onSecondary = AppOnPrimary,
    secondaryContainer = AppPrimarySoft,
    onSecondaryContainer = AppText,
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceVariant = AppSurfaceAlt,
    onSurfaceVariant = AppTextMuted,
    outline = AppLine,
    outlineVariant = AppLine,
    error = AppDanger,
    onError = AppOnPrimary,
)

/**
 * Stock sans throughout, at the platform's own metrics.
 *
 * The old headings ran monospaced and widely tracked, which is what made the
 * app read as instrumentation — and also what made a long trip name hard to
 * take in at a glance. Only the weights are touched here: headings and titles
 * are heavier so the hierarchy survives losing the colour that used to carry
 * it.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/**
 * Values a rider reads back rather than reads: distances, percentages,
 * initials. Medium weight so a number holds its own next to the label above
 * it, and nothing else — no tracking, no second family.
 */
val AppReadoutStyle = TextStyle(fontWeight = FontWeight.Medium)

/**
 * A join code, and only a join code.
 *
 * Eight characters read out loud or copied by eye, so they get the one bit of
 * letter-spacing left in the app: the gap is what stops `K7M2` from being read
 * back as a word.
 */
val AppCodeStyle = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)

@Composable
fun TracktripTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content,
    )
}
