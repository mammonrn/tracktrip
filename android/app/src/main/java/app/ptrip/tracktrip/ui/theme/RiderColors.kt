package app.ptrip.tracktrip.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours for rider markers and dots.
 *
 * Picked from a fixed palette rather than generated, so every colour stays
 * legible against the near-black map and distinct from its neighbours — a
 * genuinely random hue lands on muddy or invisible often enough to matter
 * when the whole point is telling riders apart at a glance.
 */
private val RIDER_PALETTE = listOf(
    Color(0xFF5FE3F0), // arc cyan
    Color(0xFFFFB53F), // instrument amber
    Color(0xFF7CFF9B), // signal green
    Color(0xFFFF7BD5), // magenta
    Color(0xFF9B8CFF), // violet
    Color(0xFFFF8A5A), // ember
    Color(0xFF6FA8FF), // ice blue
    Color(0xFFE8E45F), // sodium yellow
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
