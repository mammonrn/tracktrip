package app.ptrip.tracktrip.map

/**
 * Where the route-progress bar starts and stops down the edge of the map.
 *
 * ## Why this is arithmetic and not a constant
 *
 * It used to be a constant: the bar was inset 76dp from the top of the map,
 * "just clear of the floating header, which is about 68dp tall". The header is
 * not about 68dp tall. It is a title, a subtitle, an optional error line and a
 * speed readout on a plate, and every one of those grows:
 *
 *  - a trip whose name does not fit on one line wraps to two;
 *  - the subtitle carries "Sent 3 min ago" while sharing, which wraps on a
 *    narrow phone;
 *  - an error puts a whole extra row under the bar;
 *  - and the system font scale — which a rider on a motorcycle is *more*
 *    likely than average to have turned up — multiplies all of it.
 *
 * Any one of those pushes the header past 76dp and the bar's label plate ends
 * up underneath it. So the header is measured and this works out the rest.
 *
 * ## Why it also decides whether to draw at all
 *
 * On a short screen with a tall header there is genuinely no room. A bar
 * squeezed into thirty dp is not a gauge — it is a smear that reads as a
 * rendering fault — and the number it qualifies is already on the header. So
 * below a floor the bar is not drawn, which is a decision the layout can make
 * honestly and a `weight(1f)` cannot.
 *
 * Everything here is in dp as plain floats, so the rules can be held to on a
 * laptop rather than only on a device.
 */
object ProgressBarLayout {

    /**
     * The gap between the bottom of the header and the top of the bar.
     *
     * Small on purpose: the header is measured, so this is breathing room
     * rather than a guess with a safety margin baked into it.
     */
    const val HEADER_GAP_DP = 8f

    /**
     * What to assume before the header has reported its height.
     *
     * One frame, at most — [androidx.compose.ui.layout.onSizeChanged] fires
     * after the first layout pass. The old constant, so the first frame looks
     * exactly like the build before this one rather than jumping.
     */
    const val FALLBACK_HEADER_DP = 76f

    /** How far the bar stops short of the bottom of the map. */
    const val BOTTOM_INSET_DP = 16f

    /**
     * The shortest the bar's column may be and still be worth drawing.
     *
     * The column is the label plate (two lines of small text, about 44dp) plus
     * its gap plus the gauge itself. Below about 120dp the gauge is shorter
     * than the plate above it, which reads as a stray mark rather than as a
     * measure of anything.
     */
    const val MIN_COLUMN_HEIGHT_DP = 120f

    /**
     * How far down the map the bar's column starts.
     *
     * [headerHeightDp] is what the header actually measured, or null before it
     * has. A zero or negative measurement is treated as "not measured yet" for
     * the same reason: it is what a view reports before layout, not a header
     * that genuinely has no height.
     */
    fun topInsetDp(headerHeightDp: Float?): Float {
        val header = headerHeightDp?.takeIf { it.isFinite() && it > 0f } ?: FALLBACK_HEADER_DP
        return header + HEADER_GAP_DP
    }

    /**
     * How tall the bar's column can be on a map [mapHeightDp] tall.
     *
     * Never negative: a header taller than the whole map is not a layout to
     * solve, it is a layout to skip, and [fits] is what says so.
     */
    fun columnHeightDp(mapHeightDp: Float, headerHeightDp: Float?): Float =
        (mapHeightDp - topInsetDp(headerHeightDp) - BOTTOM_INSET_DP).coerceAtLeast(0f)

    /**
     * Whether there is room for a bar worth looking at.
     *
     * False means draw nothing. Nothing is not a loss: the distance and the
     * "by road"/"direct" caption it qualifies are what a rider reads at speed,
     * and on a screen this cramped they are still on the header.
     */
    fun fits(mapHeightDp: Float, headerHeightDp: Float?): Boolean =
        columnHeightDp(mapHeightDp, headerHeightDp) >= MIN_COLUMN_HEIGHT_DP
}
