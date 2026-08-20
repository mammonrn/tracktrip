package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.map.Arrival
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The podium on a trip card: three faces, and not a pixel more card.
 *
 * ## The constraint that shapes it
 *
 * This is the first screen of the app, and the archive beside it exists to
 * keep it short. A podium that made every card taller would take back exactly
 * what the archive just bought, so the strip lives inside the row the card
 * already had and is shorter than the two lines of text it sits beside.
 *
 * The last case is the one to read: two cards side by side, one with a podium
 * and one without, measured against each other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TripPodiumTest {

    @get:Rule
    val compose = createComposeRule()

    private val finished = Trip(1L, "Pai run", 1L, "ended", Trip.ROLE_OWNER)
    private val plain = Trip(2L, "Mae run", 1L, "ended", Trip.ROLE_OWNER)

    private val podium = listOf(
        Arrival(userId = 5L, place = 1, label = "Poom", photoUrl = null),
        Arrival(userId = 6L, place = 2, label = "Ann", photoUrl = null),
        Arrival(userId = 7L, place = 3, label = "Nut", photoUrl = null),
    )

    private fun show(podiums: Map<Long, List<Arrival>>) {
        compose.setContent {
            TracktripTheme {
                TripListScreen(
                    state = TripsUiState(
                        loading = false,
                        trips = listOf(finished, plain),
                        podiums = podiums,
                    ),
                    displayName = "Poom",
                    sharingTripName = null,
                    onOpenTrip = {},
                    onCreateTrip = {},
                    onAcceptInvite = {},
                    onRefresh = {},
                    onScanQr = {},
                    onOpenMap = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    /**
     * The faces carry their own semantics, but the card is clickable and
     * therefore merges everything under it into one node — so the ranking is
     * read off the unmerged tree, which is where each face's own description
     * lives.
     */
    private fun face(place: Int, name: String) = compose.onNodeWithContentDescription(
        "$place. $name reached the finish",
        useUnmergedTree = true,
    )

    @Test
    fun `the three who finished are named, in order, to a screen reader`() {
        show(mapOf(1L to podium))

        // The numeral is on the badge and the order is left to right; this is
        // the same ranking said out loud for anyone who has neither.
        face(1, "Poom").assertExists()
        face(2, "Ann").assertExists()
        face(3, "Nut").assertExists()
    }

    @Test
    fun `first place is drawn before third, left to right`() {
        show(mapOf(1L to podium))

        val first = face(1, "Poom").getUnclippedBoundsInRoot()
        val third = face(3, "Nut").getUnclippedBoundsInRoot()

        assertTrue("the podium is not in podium order: $first then $third", first.left < third.left)
    }

    @Test
    fun `the podium goes on the trip whose podium it is`() {
        show(mapOf(1L to podium))

        // Keyed by trip id, so a card whose trip nobody finished stays plain
        // even with a finished trip directly above it.
        val face = face(1, "Poom").getUnclippedBoundsInRoot()
        val withPodium = compose.onNodeWithText("Pai run").getUnclippedBoundsInRoot()
        val without = compose.onNodeWithText("Mae run").getUnclippedBoundsInRoot()

        assertTrue("the podium is not on the finished trip's card", face.top >= withPodium.top)
        assertTrue("the podium is not on the finished trip's card", face.bottom <= withPodium.bottom)
        assertTrue("the podium landed on the wrong card", face.top !in without.top..without.bottom)
    }

    @Test
    fun `a trip nobody has finished shows nothing at all`() {
        show(emptyMap())

        // Not an empty state. A card with no podium is the card exactly as it
        // was before this feature existed.
        face(1, "Poom").assertDoesNotExist()
        compose.onNodeWithText("Pai run").assertExists()
    }

    @Test
    fun `the podium does not make the card any taller`() {
        show(mapOf(1L to podium))

        // Both nodes are whole cards — the click merges each card's contents
        // into one — so this is card against card, one with a podium and one
        // without, in the same composition.
        val withPodium = compose.onNodeWithText("Pai run").getUnclippedBoundsInRoot()
        val without = compose.onNodeWithText("Mae run").getUnclippedBoundsInRoot()

        assertTrue(
            "the podium grew the card: $withPodium vs $without",
            (withPodium.height - without.height).value in -0.5f..0.5f,
        )
    }
}
