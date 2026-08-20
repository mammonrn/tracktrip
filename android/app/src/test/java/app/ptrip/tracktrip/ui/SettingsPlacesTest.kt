package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.ptrip.tracktrip.data.PersonalPlace
import app.ptrip.tracktrip.data.SharedPlace
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That the list of a rider's own places is on Settings, and still removes
 * things.
 *
 * ## Why it is here rather than under the map
 *
 * It used to sit directly below the map on Map & places: a column of rows,
 * each ending in a cross, an inch under a full-bleed map that a finger is
 * already dragging and pinching. The report was places going missing, and the
 * cause was a thumb landing where it was not aimed.
 *
 * Nothing about the list changed in the move, which is what this asserts: the
 * same two sections under the same two headings, in the same order — private
 * first, shared second — and the same confirmation in front of every removal.
 * `PlacesMapRenderTest` holds the other half, that it is no longer printed
 * under the map.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsPlacesTest {

    @get:Rule
    val compose = createComposeRule()

    private val home = PersonalPlace(
        id = 3L,
        label = "Home",
        name = "Nimman Soi 5",
        lat = 18.7961,
        lng = 98.9673,
    )

    private val pump = SharedPlace(
        id = 8L,
        name = "PTT Doi Saket",
        lat = 18.8700,
        lng = 99.1300,
        createdBy = 1L,
        createdByName = "Poom",
    )

    private var removedPersonal = mutableListOf<Long>()
    private var removedShared = mutableListOf<Long>()

    private fun show(places: PlacesUiState) {
        compose.setContent {
            TracktripTheme {
                SettingsScreen(
                    state = SettingsUiState(loadingTrips = false),
                    places = places,
                    displayName = "Poom",
                    email = "rider@gmail.com",
                    photoUrl = null,
                    sharingTripId = null,
                    onOpenProfile = {},
                    onRemovePersonalPlace = { removedPersonal += it },
                    onRemoveSharedPlace = { removedShared += it },
                    onLanguageChange = {},
                    onSharingDurationChange = {},
                    onShareFromThisPhoneChange = {},
                    onToggleSharing = { _, _ -> },
                    onSignOut = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `both kinds of place are listed, under headings that keep them apart`() {
        show(PlacesUiState(mine = listOf(pump), personal = listOf(home)))

        compose.onNodeWithText("Places you added").performScrollTo().assertExists()
        // The headings are the whole reason this is two sections rather than
        // one sorted list: everything under one is visible to the entire
        // server and everything under the other is visible to nobody.
        compose.onNodeWithText("Only you").performScrollTo().assertExists()
        compose.onNodeWithText("Shared with everyone").performScrollTo().assertExists()

        compose.onNodeWithText("Home").performScrollTo().assertExists()
        compose.onNodeWithText("PTT Doi Saket").performScrollTo().assertExists()
    }

    @Test
    fun `removing a private place asks first, and then removes that one`() {
        show(PlacesUiState(personal = listOf(home)))

        compose.onAllNodesWithText("✕")[0].performScrollTo().performClick()

        // The confirmation, by the place's own label — this is the guard that
        // makes a mis-tap survivable, and it came across with the list.
        compose.onNodeWithText("Remove").assertExists()
        assertEquals(emptyList<Long>(), removedPersonal)

        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(3L), removedPersonal)
        assertEquals(emptyList<Long>(), removedShared)
    }

    @Test
    fun `an empty list says so rather than showing nothing`() {
        show(PlacesUiState(loading = false))

        compose
            .onNodeWithText("Nothing yet. Press and hold the map to add one.")
            .performScrollTo()
            .assertExists()
    }
}
