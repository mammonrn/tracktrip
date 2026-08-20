package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the places screen actually puts on the glass.
 *
 * ## Why this exists
 *
 * A tester photographed this screen showing an empty white pill where the
 * search bar should be — no magnifier, no placeholder — and reading the source
 * could not explain it. Every explanation the code offered was wrong: the
 * strings are there in both languages, the tint is #5F6368 on #FFFFFF at
 * 6.05:1, and nothing in the modifier chain clips or hides a thing.
 *
 * Reading harder was not going to settle it. This composes the screen for real
 * — Robolectric puts an actual Android framework and the merged resources
 * behind a JVM test — and asks the composition where things ended up.
 *
 * It cannot photograph the bug: the map is an `AndroidView` around osmdroid,
 * which draws nothing without tiles to draw. What it can do is separate the
 * two explanations that were left. Either the bar's contents were never
 * composed, or they were composed, laid out, and then covered by something
 * drawn after them. The first would fail here; the second cannot.
 */
@RunWith(RobolectricTestRunner::class)
// targetSdk is 37 and Robolectric ships no framework jar that new, so the
// composition is measured against 34. Nothing on this screen is version
// dependent — it is a Column of Compose nodes, and Compose lays them out the
// same on every release.
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PlacesMapRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            TracktripTheme {
                PlacesMapScreen(
                    state = PlacesUiState(loading = false),
                    searchState = PlaceSearchState(),
                    myLocation = null,
                    centreOn = null,
                    centringOnMe = false,
                    hasLocationPermission = false,
                    currentUserId = 1L,
                    onSearchQueryChanged = {},
                    onSearchCleared = {},
                    onAddShared = { _, _ -> true },
                    onAddPersonal = { _, _, _ -> true },
                    onRemoveShared = {},
                    onRemovePersonal = {},
                    onDismissError = {},
                    onCenterOnMe = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `the search bar composes its placeholder, at a readable size`() {
        show()

        // The exact string from values/strings.xml, resolved through the real
        // resource table rather than a stub that answers "".
        val placeholder = compose.onNodeWithText("Search for a place")
        placeholder.assertExists()

        val bounds = placeholder.getUnclippedBoundsInRoot()
        println("placeholder bounds = $bounds")

        // Not a zero-sized node hiding behind a background: it has real width
        // and a real line of text.
        assertTrue("placeholder has no width: $bounds", bounds.width > 40.dp)
        assertTrue("placeholder has no height: $bounds", bounds.height > 8.dp)
    }

    @Test
    fun `the bar sits under the header and above the map, in that order`() {
        show()

        val title = compose.onNodeWithText("Map & places").getUnclippedBoundsInRoot()
        val hint = compose
            .onNodeWithText("Press and hold anywhere on the map to save a place.")
            .getUnclippedBoundsInRoot()
        val placeholder = compose.onNodeWithText("Search for a place").getUnclippedBoundsInRoot()
        val listHeading = compose.onNodeWithText("Places you added").getUnclippedBoundsInRoot()

        println("title       = $title")
        println("hint        = $hint")
        println("placeholder = $placeholder")
        println("list        = $listHeading")

        // The order the screenshot shows, which is also the order the Column
        // declares. If the bar were laid out *inside* the map's slot this
        // would be where it showed up.
        assertTrue("title is not above the hint", title.top < hint.top)
        assertTrue("hint is not above the search bar", hint.bottom <= placeholder.top)
        assertTrue("search bar is not above the list", placeholder.bottom < listHeading.top)

        // And the gap between the bar and the list is the map's slot: if the
        // map were being given a slot that started at the search bar, this
        // would be zero or negative.
        val mapSlot = listHeading.top - placeholder.bottom
        println("map slot between bar and list = $mapSlot")
        assertTrue("the map has no room of its own: $mapSlot", mapSlot > 100.dp)
    }
}
