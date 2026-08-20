package app.ptrip.tracktrip.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this file exists for: the route-progress bar ran into the header.
 *
 * The bar was inset a flat 76dp from the top of the map, on the strength of a
 * comment saying the floating header "is about 68dp tall". It is only about
 * 68dp tall when the trip has a short name, the rider is not sharing, nothing
 * has gone wrong, and the phone is on the default font scale. Change any one
 * of those and the header grows past the guess, and the bar's label plate ends
 * up underneath it.
 *
 * These hold the replacement to the promise the fix is supposed to keep: the
 * bar starts below the header at *every* header height, and stops being drawn
 * rather than being squeezed into a smear when there is genuinely no room.
 */
class ProgressBarLayoutTest {

    /** A 640dp-tall map — a fairly ordinary phone with the member list under it. */
    private val ordinaryMap = 640f

    @Test
    fun `the bar starts below the header, whatever the header measured`() {
        for (header in listOf(48f, 68f, 76f, 92f, 140f, 210f)) {
            val top = ProgressBarLayout.topInsetDp(header)
            assertTrue(
                "a $header dp header must not be overlapped, got a top inset of $top",
                top >= header,
            )
        }
    }

    @Test
    fun `a taller header pushes the bar further down, not the same distance`() {
        // The old constant did exactly this and only this: the same number
        // whatever was above it.
        val short = ProgressBarLayout.topInsetDp(60f)
        val tall = ProgressBarLayout.topInsetDp(160f)
        assertTrue(tall > short)
        assertEquals(100f, tall - short, 0.001f)
    }

    @Test
    fun `an error banner and a wrapped subtitle still leave the bar clear`() {
        // The realistic worst case on a phone in use: a long trip name on two
        // lines, "Sent 4 min ago" wrapping, and a failed poll's error row.
        val header = 68f + 22f + 30f
        assertTrue(ProgressBarLayout.topInsetDp(header) > header)
        assertTrue(ProgressBarLayout.fits(ordinaryMap, header))
    }

    @Test
    fun `a raised font scale still leaves the bar clear`() {
        // Android's accessibility font scale goes to 2x, and a rider reading a
        // phone through a visor at arm's length is more likely than average to
        // have turned it up.
        val header = 68f * 2f
        assertTrue(ProgressBarLayout.topInsetDp(header) > header)
    }

    @Test
    fun `an unmeasured header falls back to what the old build assumed`() {
        // One frame at most, before onSizeChanged fires. Matching the old
        // constant means the first frame looks like the previous build rather
        // than jumping into place.
        assertEquals(
            ProgressBarLayout.FALLBACK_HEADER_DP + ProgressBarLayout.HEADER_GAP_DP,
            ProgressBarLayout.topInsetDp(null),
            0.001f,
        )
    }

    @Test
    fun `a zero or nonsense measurement is treated as not measured yet`() {
        // Zero is what a view reports before layout, not a header with no
        // height — and a negative or infinite one is not a measurement at all.
        val fallback = ProgressBarLayout.topInsetDp(null)
        assertEquals(fallback, ProgressBarLayout.topInsetDp(0f), 0.001f)
        assertEquals(fallback, ProgressBarLayout.topInsetDp(-12f), 0.001f)
        assertEquals(fallback, ProgressBarLayout.topInsetDp(Float.NaN), 0.001f)
    }

    @Test
    fun `the column never overlaps the bottom margin either`() {
        val header = 90f
        val column = ProgressBarLayout.columnHeightDp(ordinaryMap, header)
        assertEquals(
            ordinaryMap - ProgressBarLayout.topInsetDp(header) - ProgressBarLayout.BOTTOM_INSET_DP,
            column,
            0.001f,
        )
    }

    @Test
    fun `a header taller than the whole map leaves no column and draws nothing`() {
        assertEquals(0f, ProgressBarLayout.columnHeightDp(200f, 400f), 0.001f)
        assertFalse(ProgressBarLayout.fits(200f, 400f))
    }

    @Test
    fun `a bar too short to read is not drawn at all`() {
        // Squeezing the gauge into thirty dp reads as a rendering fault, and
        // the number it qualifies is already on the header.
        val header = 300f
        assertTrue(ProgressBarLayout.columnHeightDp(440f, header) < ProgressBarLayout.MIN_COLUMN_HEIGHT_DP)
        assertFalse(ProgressBarLayout.fits(440f, header))
    }

    @Test
    fun `an ordinary phone with an ordinary header still gets a long bar`() {
        // The fix must not quietly shorten the bar on the case it was already
        // right for — it was made nearly full-height on purpose.
        val column = ProgressBarLayout.columnHeightDp(ordinaryMap, 68f)
        assertTrue("bar was only $column dp", column > ordinaryMap * 0.8f)
        assertTrue(ProgressBarLayout.fits(ordinaryMap, 68f))
    }

    @Test
    fun `every screen height either clears the header or draws nothing`() {
        // The promise in one test: across every plausible map height and every
        // plausible header height, the bar is either below the header with room
        // to spare, or absent. There is no combination that overlaps.
        for (map in 200..1200 step 20) {
            for (header in 40..320 step 10) {
                val mapDp = map.toFloat()
                val headerDp = header.toFloat()
                if (!ProgressBarLayout.fits(mapDp, headerDp)) continue

                val top = ProgressBarLayout.topInsetDp(headerDp)
                assertTrue("map=$mapDp header=$headerDp overlaps", top >= headerDp)
                assertTrue(
                    "map=$mapDp header=$headerDp runs off the bottom",
                    top + ProgressBarLayout.columnHeightDp(mapDp, headerDp) +
                        ProgressBarLayout.BOTTOM_INSET_DP <= mapDp + 0.001f,
                )
            }
        }
    }
}
