package app.ptrip.tracktrip.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The button that finds a rider on the map with no trip behind it.
 *
 * ## Why this exists
 *
 * It was reported as dead: pressed on a phone that was sharing its position in
 * another trip, and nothing happened. Reading the source said it should work,
 * which is what the source said the last time this screen was reported broken,
 * so this composes it instead and takes the chain one link at a time.
 *
 * Two of those links are here because they were the suspects and turned out to
 * be sound — a press does reach the callback through an `AndroidView` that
 * paints over the whole box, and an answer does move the camera. The third is
 * the one that was actually missing: between the press and the answer the
 * button said nothing at all, and a fix from a cold provider is allowed
 * fifteen seconds to arrive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PlacesCentreButtonTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        centringOnMe: Boolean = false,
        myLocation: LatLng? = LatLng(18.80, 98.98),
        centreOn: MapFocus? = null,
        onCenterOnMe: () -> Unit = {},
    ) {
        compose.setContent {
            TracktripTheme {
                PlacesMapScreen(
                    state = PlacesUiState(loading = false),
                    searchState = PlaceSearchState(),
                    myLocation = myLocation,
                    centreOn = centreOn,
                    centringOnMe = centringOnMe,
                    hasLocationPermission = true,
                    currentUserId = 1L,
                    onSearchQueryChanged = {},
                    onSearchCleared = {},
                    onAddShared = { _, _ -> true },
                    onAddPersonal = { _, _, _ -> true },
                    onRemoveShared = {},
                    onRemovePersonal = {},
                    onDismissError = {},
                    onCenterOnMe = onCenterOnMe,
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `a press over the map reaches the callback`() {
        var presses = 0
        show(onCenterOnMe = { presses += 1 })

        val button = compose.onNodeWithContentDescription("Centre on me")
        button.assertIsDisplayed()
        button.performClick()
        compose.waitForIdle()

        // The map is an `AndroidView` filling the same box, and the button is
        // composed over it. If the map ever took the touch instead, this is
        // where it would show up rather than on a phone.
        assertEquals(1, presses)
    }

    @Test
    fun `while it is finding you, the button says so and takes no more presses`() {
        var presses = 0
        show(centringOnMe = true, onCenterOnMe = { presses += 1 })

        // The description changes with the state, so a screen reader hears the
        // difference too — and so this test has something to look for that is
        // not a colour.
        compose.onNodeWithContentDescription("Centre on me").assertDoesNotExist()
        val busy = compose.onNodeWithContentDescription("Finding you…")
        busy.assertIsDisplayed()

        busy.performClick()
        compose.waitForIdle()

        // A second press would queue a second location request behind the
        // first, each with its own timeout, to answer a question already asked.
        assertEquals(0, presses)
    }

    @Test
    fun `the answer to a press moves the camera`() {
        var centreOn by mutableStateOf<MapFocus?>(null)
        compose.setContent {
            TracktripTheme {
                PlacesMapScreen(
                    state = PlacesUiState(loading = false),
                    searchState = PlaceSearchState(),
                    myLocation = LatLng(18.80, 98.98),
                    centreOn = centreOn,
                    centringOnMe = false,
                    hasLocationPermission = true,
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
        compose.waitForIdle()

        val map = findMap(compose.activity.window.decorView)
        assertNotNull("no osmdroid MapView in the composition", map)

        // Where a screen with a fix opens: on the rider, at SOLO_ZOOM. Worth
        // recording, because it is why a press can look like nothing — a rider
        // who has not panned is already looking at themselves.
        val before = map!!.mapCenter
        println("centre before = $before zoom=${map.zoomLevelDouble}")

        centreOn = MapFocus(13.7563, 100.5018, 1)
        compose.waitForIdle()

        val after = map.mapCenter
        println("centre after  = $after zoom=${map.zoomLevelDouble}")
        assertTrue(
            "the camera did not move to the answer: $after",
            Math.abs(after.latitude - 13.7563) < 0.01 && Math.abs(after.longitude - 100.5018) < 0.01,
        )
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findMap(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
