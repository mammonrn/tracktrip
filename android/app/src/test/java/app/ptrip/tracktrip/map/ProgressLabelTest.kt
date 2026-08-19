package app.ptrip.tracktrip.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the two labels on the route-progress bar stay short enough to read.
 *
 * ## What went wrong, and what this can and cannot catch
 *
 * The bar's column was pinned to the bar's own width — 18dp — and the labels,
 * being its children, inherited that as their maximum. Each one wrapped a
 * character per line, so "direct" appeared as six letters stacked down the
 * side of the map.
 *
 * The fix for that is structural and lives in the layout: the labels are no
 * longer inside the bar's width, and they carry `softWrap = false` so the
 * worst a future squeeze can do is an ellipsis. **This file does not test
 * that** — asserting how Compose lays something out needs an instrumentation
 * or Robolectric harness, and this project has neither.
 *
 * What it does test is the other half, the half that is not structural: that
 * the text put into those labels is short. A label is only as narrow as its
 * longest string, and a plate that grows across the map is the next version
 * of this bug even with the wrapping fixed. Character counts are a proxy for
 * width rather than a measurement of it — deliberately generous, and there to
 * catch a label that has grown by an order of magnitude, not one that gained
 * a letter.
 */
class ProgressLabelTest {

    /**
     * How many characters either label may run to.
     *
     * At `labelMedium` on a phone this is roughly 90dp of plate against a
     * ~360dp screen — a quarter of the width for a caption on a gauge, which
     * is as much as it can have before it stops being a caption.
     */
    private val maxChars = 16

    // --- the distance, which this app formats itself ----------------------

    @Test
    fun `no distance on Earth formats longer than the plate allows`() {
        // Half the planet's circumference is the furthest two points can be,
        // so this is the whole input range, not a sample of it.
        val furthestKm = 20_015.0
        var widest = ""

        var km = 0.0
        while (km <= furthestKm) {
            val formatted = RouteProgress.format(km) ?: ""
            if (formatted.length > widest.length) widest = formatted
            km += if (km < 10) 0.01 else 1.0
        }

        assertTrue(
            "widest distance label was \"$widest\" (${widest.length} chars)",
            widest.length <= maxChars,
        )
    }

    @Test
    fun `the formats it can produce are all short`() {
        // The three shapes, named so a change to any of them shows up here as
        // a diff rather than as a surprise on a phone.
        assertEquals("950 m", RouteProgress.format(0.95))
        assertEquals("7.8 km", RouteProgress.format(7.84))
        assertEquals("20015 km", RouteProgress.format(20_015.0))
    }

    // --- the caveat, which translators write ------------------------------

    @Test
    fun `the straight-line caveat is short in every language`() {
        // Thai is usually longer than English and is the one that would blow
        // this budget first. Both are read off the real resource files, so a
        // translation added later is covered without anyone remembering to.
        val offenders = localeDirectories().mapNotNull { dir ->
            val label = stringValue(File(dir, "strings.xml"), "map_progress_straight_line")
                ?: return@mapNotNull null
            "${dir.name}: \"$label\" (${label.length} chars)".takeIf { label.length > maxChars }
        }

        assertEquals("too long for the plate: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `the caveat is actually present in both languages`() {
        // Otherwise the test above passes by finding nothing to check.
        val found = localeDirectories().mapNotNull { dir ->
            stringValue(File(dir, "strings.xml"), "map_progress_straight_line")?.let { dir.name }
        }

        assertTrue("expected values and values-th, found $found", "values" in found)
        assertTrue("expected values and values-th, found $found", "values-th" in found)
    }

    private fun localeDirectories(): List<File> =
        resDir().listFiles()
            ?.filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            ?.filter { File(it, "strings.xml").isFile }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun stringValue(file: File, name: String): String? =
        Regex("""<string\s+name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            // `\'` in the XML is one character on screen, not two.
            ?.replace("\\'", "'")

    /**
     * Found by walking up from wherever the runner started, as
     * `StringCoverageTest` does — Gradle and an IDE disagree about the working
     * directory, and a test that only passes under one of them is a test
     * people learn to ignore.
     */
    private fun resDir(): File {
        val start = System.getProperty("user.dir") ?: "."
        var dir: File? = File(start)
        while (dir != null) {
            val candidate = File(dir, "src/main/res")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw IllegalStateException("no src/main/res from $start")
    }
}
