package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
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
 * A saved place is a card now, and the card opens.
 *
 * ## Why the card had to exist
 *
 * The rows on this list showed a name and one supporting line, and the only
 * thing on one that could be pressed was the cross that deleted it. A rider
 * with two PTTs written down had no way to tell which was which before
 * removing one: where the place actually is, when it was added and who by were
 * all known to the app and on no screen.
 *
 * So the whole row is a target that does something harmless — it opens what
 * the app knows — and the cross is one of two things to press rather than the
 * only one. `SettingsPlacesTest` holds what did not change: the two headings,
 * their order, and the confirmation in front of every removal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsPlaceCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val pump = SharedPlace(
        id = 8L,
        name = "PTT Doi Saket",
        lat = 18.8700,
        lng = 99.1300,
        createdBy = 1L,
        createdByName = "Poom",
        createdAt = "2026-08-19T04:15:00Z",
    )

    private val home = PersonalPlace(
        id = 3L,
        label = "Home",
        name = "Nimman Soi 5",
        lat = 18.7961,
        lng = 98.9673,
        createdAt = "2026-08-19T04:15:00Z",
    )

    private val removedShared = mutableListOf<Long>()
    private val removedPersonal = mutableListOf<Long>()

    private fun show(
        shared: List<SharedPlace> = listOf(pump),
        personal: List<PersonalPlace> = listOf(home),
        removingShared: Set<Long> = emptySet(),
    ) {
        compose.setContent {
            TracktripTheme {
                SettingsScreen(
                    state = SettingsUiState(loadingTrips = false),
                    places = PlacesUiState(
                        mine = shared,
                        personal = personal,
                        removingShared = removingShared,
                    ),
                    displayName = "Poom",
                    email = "rider@gmail.com",
                    photoUrl = null,
                    sharingTripId = null,
                    sharingTripName = null,
                    onOpenProfile = {},
                    onRemovePersonalPlace = { removedPersonal += it },
                    onRemoveSharedPlace = { removedShared += it },
                    onLanguageChange = {},
                    onSharingDurationChange = {},
                    onShareFromThisPhoneChange = {},
                    onSignOut = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `tapping a shared place opens what the app knows about it`() {
        show()

        compose.onNodeWithText("PTT Doi Saket").performScrollTo().assertHasClickAction().performClick()

        // The coordinate, because a shared place has no address — it is a name
        // somebody typed over a point they chose, and there is nothing on the
        // server to reverse-geocode it with.
        compose.onNodeWithText("18.87000, 99.13000").assertIsDisplayed()
        compose.onNodeWithText("19 Aug").assertIsDisplayed()
        // Who wrote it down: the one thing this list has to say that a map
        // company's result does not.
        compose.onNodeWithText("Added by").assertIsDisplayed()
        // And who can see it, said outright on the screen a rider is on when
        // they decide whether to delete it.
        compose.onNodeWithText("Visible to").assertIsDisplayed()
    }

    @Test
    fun `a private shortcut shows both of its names and no author`() {
        show(shared = emptyList())

        compose.onNodeWithText("Home").performScrollTo().performClick()

        // The label is the chip and the name is what a route calls it. They
        // are usually different, and the difference is the whole reason a
        // personal place carries two. Two nodes, not one: the card underneath
        // shows the name as its supporting line and the dialog is over it.
        compose.onNodeWithText("Called on a route").assertIsDisplayed()
        compose.onAllNodesWithText("Nimman Soi 5").assertCountEquals(2)
        compose.onNodeWithText("18.79610, 98.96730").assertIsDisplayed()
        // Never an author line: this table has no join to users, because the
        // owner is always the caller.
        compose.onAllNodesWithText("Added by").assertCountEquals(0)
    }

    @Test
    fun `opening a place is not a step towards deleting it`() {
        show()

        compose.onNodeWithText("PTT Doi Saket").performScrollTo().performClick()
        compose.onNodeWithText("Close").performClick()

        assertEquals(emptyList<Long>(), removedShared)
        assertEquals(emptyList<Long>(), removedPersonal)
    }

    @Test
    fun `the cross still asks before anything goes, and then removes that one`() {
        show(personal = emptyList())

        compose.onAllNodesWithText("✕")[0].performScrollTo().performClick()

        // The confirmation, by the place's own name. The guard that makes a
        // mis-tap survivable, and it survived the move to cards.
        compose.onNodeWithText("Remove this place?").assertIsDisplayed()
        assertEquals(emptyList<Long>(), removedShared)

        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(8L), removedShared)
    }

    @Test
    fun `a delete already in flight takes its own cross away`() {
        // The row stays on screen for the whole round trip. Until the spinner
        // existed there was nothing to tell "this is happening" from "nothing
        // happened", and a rider's only move was to press the cross again.
        show(personal = emptyList(), removingShared = setOf(8L))

        compose.onNodeWithText("PTT Doi Saket").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("✕").assertCountEquals(0)
    }
}
