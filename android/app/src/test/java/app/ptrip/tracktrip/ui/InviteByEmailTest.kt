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
 * That inviting somebody by email is a button rather than a form left open,
 * and that one press of it can carry more than one rider.
 *
 * ## Why it moved
 *
 * The field, its Invite button and a two-line note about what an invite
 * actually does sat permanently between the shortcut chips and the QR and
 * share buttons — the tallest thing on the panel, for the least-used of the
 * three ways onto a trip.
 *
 * ## Why one press now carries several
 *
 * Behind the button it was still one address and one Invite, so adding four
 * people meant opening it, typing, sending, watching it close and opening it
 * again — four rounds for one intention. The chips are a selection that is
 * held, the field takes a list, and [InviteRules.addresses] is what turns the
 * two into one request list.
 *
 * ## Why the dialog is not opened here
 *
 * A text field inside a dialog window never lets a Compose test reach idle
 * under Robolectric — the composition stays busy until the sixty-second
 * timeout — which is why `StopNameDialog`, the same shape, has never been
 * asserted on either. So the dialog is split: `InviteByEmailDialog` is
 * placement, and everything with behaviour in it is somewhere this can reach —
 * [InviteByEmailForm] for the chips, the field and its notes, and
 * [InviteRules] for what a press sends and when it may be pressed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class InviteByEmailTest {

    @get:Rule
    val compose = createComposeRule()

    private val typed = mutableListOf<String>()
    private val invited = mutableListOf<String>()
    private val sentTogether = mutableListOf<List<String>>()

    private val nut = SuggestedInvitee(4L, "nut@gmail.com", "Nut", null, null, 3)
    private val speedy = SuggestedInvitee(5L, "speedy@gmail.com", "Speedy", null, null, 2)

    private fun panel(
        email: String = "",
        sent: List<String> = emptyList(),
        suggestions: List<SuggestedInvitee> = emptyList(),
    ) {
        compose.setContent {
            TracktripTheme {
                InvitePanel(
                    email = email,
                    pending = false,
                    sent = sent,
                    error = null,
                    shortcuts = InviteShortcuts.window(suggestions),
                    onEmailChange = { typed += it },
                    onSendInvites = { sentTogether += it },
                    onInviteSuggestion = { invited += it.email },
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

        // The field, its button and its guidance are all behind the press.
        compose.onNodeWithText("Google account emails").assertDoesNotExist()
        compose.onNodeWithText("Invite").assertDoesNotExist()
    }

    @Test
    fun `an invite that landed is said on the panel, not inside the dialog`() {
        panel(sent = listOf("nut@gmail.com"))

        // The dialog closes on the server's answer, so the answer has to be
        // somewhere that survives it closing.
        compose.onNodeWithText("Invited nut@gmail.com").assertExists()
    }

    @Test
    fun `several invites in one press are confirmed as a count`() {
        panel(sent = listOf("nut@gmail.com", "speedy@gmail.com", "poom@gmail.com"))

        // Three addresses on one line is not a confirmation anybody reads.
        compose.onNodeWithText("Invited 3 riders").assertExists()
    }

    @Test
    fun `a chip on the panel is the invite, not a shortcut to the form`() {
        panel(suggestions = listOf(nut))

        compose.onNodeWithText("Nut").performClick()

        // It used to fill the address in and open the dialog on it. A name
        // from the rider's own past trips needs no form and no second
        // confirmation.
        assertEquals(listOf("nut@gmail.com"), invited)
        assertEquals(emptyList<List<String>>(), sentTogether)
    }

    @Test
    fun `the form is the chips, the field, and the reason a send was refused`() {
        val picked = mutableListOf<Set<Long>>()
        compose.setContent {
            TracktripTheme {
                InviteByEmailForm(
                    email = "not-an-address",
                    error = "already-there@gmail.com: that rider is already on this trip",
                    shortcuts = InviteShortcuts.window(listOf(nut, speedy)),
                    picked = setOf(nut.userId),
                    onPickedChange = { picked += it },
                    onEmailChange = { typed += it },
                )
            }
        }

        compose.onNodeWithText("Google account emails").assertExists()
        compose.onNodeWithText("Nut").assertExists()

        // The failure this move must not introduce: the screen's own error
        // line is behind the dialog, so a send refused with the dialog up
        // would otherwise fail in silence — and with several addresses in one
        // press it has to name which of them was refused.
        compose
            .onNodeWithText("already-there@gmail.com: that rider is already on this trip")
            .assertExists()

        compose.onNodeWithText("not-an-address").performTextReplacement("rider@gmail.com")
        assertEquals(listOf("rider@gmail.com"), typed)

        // A chip in the dialog is a choice held, not an invite sent — pressing
        // an already-picked one takes it back out.
        compose.onNodeWithText("Nut").performClick()
        assertEquals(listOf(emptySet<Long>()), picked)

        compose.onNodeWithText("Speedy").performClick()
        assertEquals(setOf(nut.userId, speedy.userId), picked.last())
    }

    @Test
    fun `one press carries the chips and the typed addresses together`() {
        assertEquals(
            listOf("nut@gmail.com", "poom@gmail.com", "ann@gmail.com"),
            InviteRules.addresses("poom@gmail.com, ann@gmail.com", listOf(nut)),
        )

        // Pasted out of a chat window: commas, semicolons, spaces, newlines.
        // A rider who separates three addresses with spaces has not made a
        // mistake worth an error message.
        assertEquals(
            listOf("a@gmail.com", "b@gmail.com", "c@gmail.com", "d@gmail.com"),
            InviteRules.addresses("a@gmail.com, b@gmail.com;c@gmail.com d@gmail.com", emptyList()),
        )

        // A chip and the same address typed is one invite, not one invite and
        // one "already invited" the rider caused by being thorough.
        assertEquals(
            listOf("nut@gmail.com"),
            InviteRules.addresses("NUT@gmail.com", listOf(nut)),
        )
    }

    @Test
    fun `Invite is pressable exactly when there is something to send`() {
        // Nothing chosen and nothing typed, or a send already out, are both
        // requests whose answer is already known, and a pressable button that
        // does nothing reads as a send that silently failed.
        assertFalse(InviteRules.canSend(typed = "", picked = emptyList(), pending = false))
        assertFalse(InviteRules.canSend(typed = "   ", picked = emptyList(), pending = false))
        assertFalse(InviteRules.canSend(typed = "rider@gmail.com", picked = emptyList(), pending = true))
        assertTrue(InviteRules.canSend(typed = "rider@gmail.com", picked = emptyList(), pending = false))

        // A chip alone is enough — that is the whole point of picking one.
        assertTrue(InviteRules.canSend(typed = "", picked = listOf(nut), pending = false))
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
                    onSendInvites = {},
                    onInviteSuggestion = {},
                    onShowQr = {},
                    onEditTrip = {},
                    onEndTrip = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Add by email").performScrollTo().assertExists()
        compose.onNodeWithText("Google account emails").assertDoesNotExist()
    }
}
