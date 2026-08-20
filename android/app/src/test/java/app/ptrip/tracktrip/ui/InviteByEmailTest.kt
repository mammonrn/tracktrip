package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.ptrip.tracktrip.data.SuggestedInvitee
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That inviting somebody by email is a button rather than a form left open.
 *
 * ## Why it moved
 *
 * The field, its Invite button and a two-line note about what an invite
 * actually does sat permanently between the suggestion chips and the QR and
 * share buttons — the tallest thing on the panel, for the least-used of the
 * three ways onto a trip. The QR is for somebody standing next to you and the
 * link is for a chat window; typing a Google address from memory is the
 * fallback, and a fallback does not need to be the tallest thing on screen.
 *
 * Nothing about the invite itself changed, and that is what these assert.
 *
 * ## Why the dialog is not opened here
 *
 * A text field inside a dialog window never lets a Compose test reach idle
 * under Robolectric — the composition stays busy until the sixty-second
 * timeout — which is why `StopNameDialog`, the same shape, has never been
 * asserted on either. So the dialog is split: `InviteByEmailDialog` is four
 * lines of placement, and everything with behaviour in it is somewhere this
 * can reach — [InviteByEmailForm] for the field and its two notes, and
 * [InviteRules] for when the button may be pressed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class InviteByEmailTest {

    @get:Rule
    val compose = createComposeRule()

    private val typed = mutableListOf<String>()
    private val used = mutableListOf<String>()

    private fun panel(
        email: String = "",
        sentTo: String? = null,
        suggestions: List<SuggestedInvitee> = emptyList(),
    ) {
        compose.setContent {
            TracktripTheme {
                InvitePanel(
                    email = email,
                    pending = false,
                    sentTo = sentTo,
                    error = null,
                    suggestions = suggestions,
                    onEmailChange = { typed += it },
                    onSend = {},
                    onUseSuggestion = { used += it.email },
                    onShowQr = {},
                    onShareLink = {},
                )
            }
        }
    }

    @Test
    fun `the panel offers a button, not a field`() {
        panel()

        compose.onNodeWithText("Add by email").assertExists()

        // The field, its button and its guidance are all behind the press now.
        compose.onNodeWithText("Google account email").assertDoesNotExist()
        compose.onNodeWithText("Invite").assertDoesNotExist()
        compose
            .onNodeWithText(
                "They'll see the invitation in their own trip list next time they open the app. " +
                    "Nothing is emailed."
            )
            .assertDoesNotExist()
    }

    @Test
    fun `an invite that landed is said on the panel, not inside the dialog`() {
        panel(sentTo = "nut@gmail.com")

        // The dialog closes on the server's answer, so the answer has to be
        // somewhere that survives it closing.
        compose.onNodeWithText("Invited nut@gmail.com").assertExists()
    }

    @Test
    fun `a suggestion still fills the address in`() {
        panel(suggestions = listOf(SuggestedInvitee(4L, "nut@gmail.com", "Nut", null, null, 3)))

        compose.onNodeWithText("Nut").performClick()

        // The chip was always shorthand for typing the address, and it still
        // is — it fills it in, and opens the dialog on it.
        assertEquals(listOf("nut@gmail.com"), used)
    }

    @Test
    fun `the form is the field, the note, and the reason a send was refused`() {
        compose.setContent {
            TracktripTheme {
                InviteByEmailForm(
                    email = "not-an-address",
                    error = "a valid email is required",
                    onEmailChange = { typed += it },
                )
            }
        }

        compose.onNodeWithText("Google account email").assertExists()
        compose
            .onNodeWithText(
                "They'll see the invitation in their own trip list next time they open the app. " +
                    "Nothing is emailed."
            )
            .assertExists()

        // The failure this move must not introduce: the screen's own error
        // line is behind the dialog, so a send refused with the dialog up
        // would otherwise fail in silence.
        compose.onNodeWithText("a valid email is required").assertExists()

        compose.onNodeWithText("not-an-address").performTextReplacement("rider@gmail.com")
        assertEquals(listOf("rider@gmail.com"), typed)
    }

    @Test
    fun `Invite is pressable exactly when it was before`() {
        // Nothing typed, or a send already out, are both requests whose answer
        // is already known, and a pressable button that does nothing reads as
        // a send that silently failed.
        assertFalse(InviteRules.canSend(email = "", pending = false))
        assertFalse(InviteRules.canSend(email = "   ", pending = false))
        assertFalse(InviteRules.canSend(email = "rider@gmail.com", pending = true))
        assertTrue(InviteRules.canSend(email = "rider@gmail.com", pending = false))
    }

    @Test
    fun `the panel is what the trip screen shows an owner`() {
        compose.setContent {
            TracktripTheme {
                TripDetailScreen(
                    state = TripDetailUiState(
                        loading = false,
                        trip = Trip(1L, "Pai run", 1L, Trip.STATUS_ACTIVE, Trip.ROLE_OWNER),
                    ),
                    currentUserId = 1L,
                    sharing = false,
                    onStartSharing = {},
                    onStopSharing = {},
                    onShareInviteLink = {},
                    onOpenMap = {},
                    onInviteEmailChange = {},
                    onSendInvite = {},
                    onUseSuggestion = {},
                    onShowQr = {},
                    onEditTrip = {},
                    onEndTrip = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Add by email").performScrollTo().assertExists()
        compose.onNodeWithText("Google account email").assertDoesNotExist()
    }
}
