package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.SuggestedInvitee
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order of the invite chips.
 *
 * The server sends them most-ridden-with first; the screen's only job is to
 * sink the ones already invited in this sitting — without dropping them, since
 * inviting the wrong person is easy and a chip that vanishes leaves no way to
 * put it right.
 */
class SuggestionOrderTest {

    private fun invitee(id: Long, name: String) =
        SuggestedInvitee(
            userId = id,
            email = "$name@gmail.com",
            displayName = name,
            username = null,
            photoUrl = null,
            tripsTogether = 1,
        )

    private val regular = invitee(1, "regular")
    private val sometimes = invitee(2, "sometimes")
    private val once = invitee(3, "once")

    @Test
    fun `with nobody invited, the server's order is kept`() {
        val state = TripDetailUiState(suggestions = listOf(regular, sometimes, once))

        assertEquals(listOf(regular, sometimes, once), state.orderedSuggestions)
    }

    @Test
    fun `an invited rider moves to the end but stays on the list`() {
        val state = TripDetailUiState(
            suggestions = listOf(regular, sometimes, once),
            invitedThisSession = setOf(regular.userId),
        )

        assertEquals(listOf(sometimes, once, regular), state.orderedSuggestions)
    }

    @Test
    fun `several invited riders keep their relative order at the end`() {
        val state = TripDetailUiState(
            suggestions = listOf(regular, sometimes, once),
            invitedThisSession = setOf(regular.userId, sometimes.userId),
        )

        assertEquals(listOf(once, regular, sometimes), state.orderedSuggestions)
    }

    @Test
    fun `inviting everyone leaves the list intact`() {
        val state = TripDetailUiState(
            suggestions = listOf(regular, sometimes, once),
            invitedThisSession = setOf(regular.userId, sometimes.userId, once.userId),
        )

        assertEquals(listOf(regular, sometimes, once), state.orderedSuggestions)
    }
}
