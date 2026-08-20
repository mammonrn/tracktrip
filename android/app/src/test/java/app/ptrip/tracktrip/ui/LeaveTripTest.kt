package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A member's way off a trip.
 *
 * One active trip per rider is enforced on the server now, and the only way
 * off a trip used to be its owner ending it — so a rider whose host went home
 * without pressing End could not start a trip of their own, could not accept
 * another invitation, and had nothing they could do about it. The rule needed
 * a door the person it constrains can open.
 *
 * These hold who is offered it, who is not, and that it asks first. Ending and
 * leaving are not the same control renamed: ending closes the ride for the
 * whole group and is the owner's, leaving takes one rider off it and is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class LeaveTripTest {

    @get:Rule
    val compose = createComposeRule()

    private val left = mutableListOf<Unit>()
    private val ended = mutableListOf<Unit>()

    private fun trip(role: String, status: String = Trip.STATUS_ACTIVE) =
        Trip(1L, "Chiang Mai loop", 9L, status, role)

    private fun screen(trip: Trip, leavePending: Boolean = false) {
        left.clear()
        ended.clear()
        compose.setContent {
            TracktripTheme {
                TripDetailScreen(
                    state = TripDetailUiState(
                        loading = false,
                        trip = trip,
                        leavePending = leavePending,
                    ),
                    currentUserId = 1L,
                    sharing = false,
                    onStartSharing = {},
                    onStopSharing = {},
                    onShareInviteLink = {},
                    onOpenMap = {},
                    onInviteEmailChange = {},
                    onSendInvites = {},
                    onInviteSuggestion = {},
                    onShowQr = {},
                    onEditTrip = {},
                    onEndTrip = { ended += Unit },
                    onLeaveTrip = { left += Unit },
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `a member on a running trip is offered the way out`() {
        screen(trip("member"))

        compose.onNodeWithText("Leave trip").assertExists()
        // Ending closes the ride for the whole group; that is the owner's.
        compose.onNodeWithText("End trip").assertDoesNotExist()
        compose.onNodeWithText("Edit trip").assertDoesNotExist()
    }

    @Test
    fun `the owner is not offered it, and still has End`() {
        screen(trip(Trip.ROLE_OWNER))

        // A trip with no owner is one nobody can end, invite to or rename — so
        // the owner's door is the one marked End, and there is no second one.
        compose.onNodeWithText("Leave trip").assertDoesNotExist()
        compose.onNodeWithText("End trip").assertExists()
    }

    @Test
    fun `a finished trip offers neither`() {
        // Nothing is holding the rider there — the rule counts only active
        // trips — and leaving would take them off a completed ride's roster
        // and off its podium.
        screen(trip("member", status = "ended"))

        compose.onNodeWithText("Leave trip").assertDoesNotExist()
        compose.onNodeWithText("End trip").assertDoesNotExist()
    }

    @Test
    fun `it asks before it does it`() {
        screen(trip("member"))

        compose.onNodeWithText("Leave trip").performClick()

        // Nothing has happened yet: the press opens the question.
        assertEquals(emptyList<Unit>(), left)
        compose.onNodeWithText("Leave this trip?").assertExists()
        compose
            .onNodeWithText(
                "You'll stop sharing your location with the group and drop off their map. " +
                    "Getting back on needs a new invitation from the trip's owner."
            )
            .assertExists()
    }

    @Test
    fun `cancelling the question leaves the rider on the trip`() {
        screen(trip("member"))

        compose.onNodeWithText("Leave trip").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList<Unit>(), left)
        compose.onNodeWithText("Leave this trip?").assertDoesNotExist()
    }

    @Test
    fun `confirming leaves once`() {
        screen(trip("member"))

        compose.onNodeWithText("Leave trip").performClick()
        // The dialog's confirm carries the same words as the button that
        // opened it, so the row and the answer cannot read as different acts.
        compose.onAllNodesWithText("Leave trip")[1].performClick()

        assertEquals(1, left.size)
        assertEquals(emptyList<Unit>(), ended)
        // And the question closes behind it rather than waiting for a second
        // press on a request already in flight.
        compose.onNodeWithText("Leave this trip?").assertDoesNotExist()
    }
}
