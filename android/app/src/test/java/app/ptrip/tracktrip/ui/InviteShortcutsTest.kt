package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.SuggestedInvitee
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Ridden with before": five names, a number, and a list that refills.
 *
 * The panel drew every past companion at once in a scrolling box, which for a
 * group that has ridden together for years is a wall of names above the three
 * buttons that actually add people. Five and a "+N more" is the shortcut it
 * was meant to be.
 *
 * The refilling is the other half, and the one that matters more: a chip is
 * now the invite, so after a name goes onto the trip it has to be *replaced*
 * by the next one rather than leaving a gap — a rider works down a group by
 * pressing the same corner of the screen until the names run out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class InviteShortcutsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun rider(id: Long, name: String) =
        SuggestedInvitee(
            userId = id,
            email = "$name@gmail.com",
            displayName = name,
            username = null,
            photoUrl = null,
            tripsTogether = 1,
        )

    private fun member(id: Long, name: String) =
        MemberPosition(
            userId = id,
            displayName = name,
            username = null,
            photoUrl = null,
            role = "member",
            isSharing = false,
            sharingUntil = null,
            lat = null,
            lng = null,
            speedMps = null,
            batteryPct = null,
            recordedAt = null,
        )

    /** Seventeen past companions, in the order the server ranked them. */
    private val everyone = (1L..17L).map { rider(it, "rider$it") }

    private fun state(
        suggestions: List<SuggestedInvitee> = everyone,
        invited: Set<Long> = emptySet(),
        members: List<MemberPosition> = emptyList(),
    ) = TripDetailUiState(
        loading = false,
        suggestions = suggestions,
        invitedThisSession = invited,
        members = members,
    )

    // --- the window -------------------------------------------------------

    @Test
    fun `five are drawn and the rest are counted`() {
        val window = state().suggestionWindow

        assertEquals(everyone.take(5), window.shown)
        assertEquals(12, window.moreCount)
        assertEquals(everyone, window.all)
    }

    @Test
    fun `a short list has no number at all`() {
        val window = state(suggestions = everyone.take(3)).suggestionWindow

        assertEquals(3, window.shown.size)
        assertEquals(0, window.moreCount)
    }

    @Test
    fun `exactly five is five, not four and a plus one`() {
        val window = state(suggestions = everyone.take(5)).suggestionWindow

        assertEquals(5, window.shown.size)
        assertEquals(0, window.moreCount)
    }

    // --- refilling --------------------------------------------------------

    @Test
    fun `an invited name is replaced by the next one, not left as a gap`() {
        val window = state(invited = setOf(1L)).suggestionWindow

        // The invited one drops out of the five and the sixth rises into its
        // place, so the window is still five names worth pressing.
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), window.shown.map { it.userId })
        // Sunk, not dropped: inviting the wrong Nut is easy, and a chip that
        // vanishes leaves no way to put it right.
        assertEquals(1L, window.all.last().userId)
    }

    @Test
    fun `it keeps refilling until the names run out`() {
        var invited = emptySet<Long>()
        val pressed = mutableListOf<Long>()

        // Press the first chip, over and over, the way a rider adding a group
        // actually does.
        repeat(17) {
            val next = state(invited = invited).suggestionWindow.shown.first()
            pressed += next.userId
            invited = invited + next.userId
        }

        assertEquals((1L..17L).toList(), pressed)
        // And then there is nothing left that has not been asked.
        assertEquals(
            emptyList<Long>(),
            state(invited = invited).suggestionWindow.all
                .filterNot { it.userId in invited }
                .map { it.userId },
        )
    }

    @Test
    fun `somebody who joined since the list was read is dropped, not sunk`() {
        // The server excludes members when it builds the list, so this only
        // catches the ones who accepted while the screen was open. They are on
        // the trip; there is nothing left to offer about them.
        val window = state(members = listOf(member(2L, "rider2"))).suggestionWindow

        assertEquals(listOf(1L, 3L, 4L, 5L, 6L), window.shown.map { it.userId })
        assertEquals(16, window.all.size)
    }

    // --- the chips on screen ---------------------------------------------

    @Test
    fun `the panel draws five chips and a plus-twelve, and opens the rest in place`() {
        val invited = mutableListOf<String>()
        compose.setContent {
            TracktripTheme {
                InvitePanel(
                    email = "",
                    pending = false,
                    sent = emptyList(),
                    error = null,
                    shortcuts = InviteShortcuts.window(everyone),
                    onEmailChange = {},
                    onSendInvites = {},
                    onInviteSuggestion = { invited += it.email },
                    onShowQr = {},
                    onShareLink = {},
                )
            }
        }

        compose.onNodeWithText("rider1").assertExists()
        compose.onNodeWithText("rider5").assertExists()
        compose.onNodeWithText("rider6").assertDoesNotExist()
        compose.onNodeWithText("+12 more").assertExists()

        // In place rather than on a screen of its own: a handful of chips is
        // not worth something to dismiss, and the box already scrolls.
        compose.onNodeWithText("+12 more").performClick()
        compose.onNodeWithText("rider6").assertExists()
        compose.onNodeWithText("rider17").assertExists()
        compose.onNodeWithText("+12 more").assertDoesNotExist()

        compose.onNodeWithText("rider17").performClick()
        assertEquals(listOf("rider17@gmail.com"), invited)
    }
}
