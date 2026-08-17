package app.ptrip.tracktrip.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours for rider markers and dots.
 *
 * Picked from a fixed palette rather than generated, so every colour stays
 * legible and distinct from its neighbours — a genuinely random hue lands on
 * muddy or invisible often enough to matter when the whole point is telling
 * riders apart at a glance.
 *
 * Eight vivid hues, one per rider, deliberately outside the rest of the
 * palette's restraint: this is the one place in the app where colour carries
 * identity rather than meaning. They are pitched for a light map — each one
 * clears 3:1 against white, which the previous neon set (tuned for a
 * near-black ground) no longer did.
 */
private val RIDER_PALETTE = listOf(
    Color(0xFF1A73E8), // blue
    Color(0xFFD93025), // red
    Color(0xFF1E8E3E), // green
    Color(0xFFE8710A), // orange
    Color(0xFF9334E6), // violet
    Color(0xFF00838F), // teal
    Color(0xFFC2185B), // magenta
    Color(0xFF8D6E00), // ochre
)

/**
 * The colour for a rider, derived from their user id so it is the same on the
 * map, in the member list, and across restarts — a marker that changed colour
 * between screens would be worse than useless.
 */
fun riderColor(userId: Long): Color {
    val index = ((userId % RIDER_PALETTE.size) + RIDER_PALETTE.size) % RIDER_PALETTE.size
    return RIDER_PALETTE[index.toInt()]
}
