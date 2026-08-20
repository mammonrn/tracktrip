package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The controls at the foot of the member list, measured.
 *
 * ## Why this exists
 *
 * An owner riding a live trip is offered four of them — open the map, stop
 * sharing, edit the trip, end it — and reported them as one slab: four bars of
 * identical width and weight down the edge of the screen, with nothing saying
 * which two belonged together.
 *
 * The first answer was spacing, and it was reported as worse. It was: a rhythm
 * between four things does not group them, it only makes the slab taller. What
 * groups them is the layout. They are two rows of two now, and the pairing is
 * the point — the ride on top, the trip's own controls below the divider — so
 * these tests assert the pairing rather than the gaps.
 *
 * Measured rather than described, because a fifth button with its own
 * `fillMaxWidth()` would quietly put the column back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TripActionsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun trip(active: Boolean = true, owner: Boolean = true) = Trip(
        id = 1L,
        name = "Sunday run",
        ownerId = 7L,
        status = if (active) Trip.STATUS_ACTIVE else "ended",
        role = if (owner) Trip.ROLE_OWNER else "member",
    )

    private fun show(
        trip: Trip = trip(),
        sharing: Boolean = true,
    ) {
        compose.setContent {
            TracktripTheme {
                TripDetailScreen(
                    state = TripDetailUiState(loading = false, trip = trip),
                    currentUserId = 7L,
                    sharing = sharing,
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
    }

    private fun bounds(text: String): DpRect =
        compose.onNodeWithText(text).getUnclippedBoundsInRoot()

    @Test
    fun `no control is taller than a button has to be`() {
        show()

        val heights = listOf("Tracking", "Stop sharing", "Edit trip", "End trip")
            .associateWith { bounds(it).height }
        println("button heights = $heights")

        heights.forEach { (label, height) ->
            // 40dp is Material's minimum height for a button, and with a
            // labelLarge line inside it that is what 10dp of vertical padding
            // comes to. Two buttons side by side make this matter more than it
            // did in a column: a label that wrapped would push one of a pair
            // taller than the other, which reads as a mistake. The labels are
            // short enough not to, and `maxLines = 1` keeps them that way.
            assertTrue("$label is $height, taller than a 40dp button", height <= 41.dp)
            // Not a licence to shrink them into something nobody can hit with
            // a glove on: this fails just as loudly the other way.
            assertTrue("$label is $height, too small to press", height >= 38.dp)
        }
    }

    @Test
    fun `the ride is one pair and the trip's own controls are another`() {
        show()

        val tracking = bounds("Tracking")
        val stopSharing = bounds("Stop sharing")
        val editTrip = bounds("Edit trip")
        val endTrip = bounds("End trip")

        // Side by side, not stacked: same line, one left and one right.
        assertPair("the riding controls", tracking, stopSharing)
        assertPair("the trip's own controls", editTrip, endTrip)

        // And the two pairs are on different lines, in the order the screen
        // means them: what the ride needs first, the owner's controls under a
        // divider below it.
        assertTrue(
            "the owner's controls are not below the riding pair",
            editTrip.top > tracking.bottom,
        )

        // The break between the pairs is wider than the gap inside one — it
        // has the divider in it — so the grouping survives being glanced at.
        val betweenPairs = editTrip.top - stopSharing.bottom
        val withinPair = stopSharing.left - tracking.right
        println("between pairs = $betweenPairs, within a pair = $withinPair")
        assertTrue(
            "the pairs are not set apart: $betweenPairs against $withinPair inside one",
            betweenPairs >= withinPair + 12.dp,
        )
    }

    @Test
    fun `a pair splits the width evenly`() {
        show()

        val tracking = bounds("Tracking")
        val stopSharing = bounds("Stop sharing")

        // Equal weights, so neither of a pair reads as the bigger decision by
        // being the bigger button.
        val difference = (tracking.width - stopSharing.width).value
        assertTrue(
            "the riding pair is lopsided: ${tracking.width} vs ${stopSharing.width}",
            kotlin.math.abs(difference) <= 1f,
        )
    }

    @Test
    fun `a lone control still fills its row`() {
        // A finished trip has nothing to share and nothing to end, so both
        // rows are down to one button. A half-width button with empty space
        // beside it would look like something failed to load.
        show(trip = trip(active = false), sharing = false)

        val map = bounds("Final positions")
        val edit = bounds("Edit trip")
        println("lone widths: map=${map.width} edit=${edit.width}")

        assertTrue("the map button did not fill its row: ${map.width}", map.width >= 340.dp)
        assertTrue("the edit button did not fill its row: ${edit.width}", edit.width >= 340.dp)
    }

    /** The narrowest phone anybody still rides with. */
    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun `the pairing survives a narrow phone`() {
        show()

        val tracking = bounds("Tracking")
        val stopSharing = bounds("Stop sharing")
        println("320dp: tracking=${tracking.width} stopSharing=${stopSharing.width}")

        // Still two columns rather than a wrap back into a stack, and still
        // one line each: a label forced onto a second line would take its
        // button past 40dp and leave the pair mismatched.
        assertPair("the riding controls at 320dp", tracking, stopSharing)
        assertTrue("Tracking grew to ${tracking.height}", tracking.height <= 41.dp)
        assertTrue("Stop sharing grew to ${stopSharing.height}", stopSharing.height <= 41.dp)

        // Whether the *words* fit is not something this can answer: Robolectric
        // measures every glyph at a fraction of its real width, so a label that
        // would be clipped on a phone measures comfortably here. The headroom
        // is bought in the layout instead — see BUTTON_HORIZONTAL_PADDING,
        // which leaves a half-width button on a 320dp screen more room for its
        // label than the longest of these needs.
    }

    @Test
    fun `every control is still one press away`() {
        show()

        // The pairing is a layout change, not a reorganisation: nothing moved
        // behind a menu, an overflow or a second screen.
        listOf("Tracking", "Stop sharing", "Edit trip", "End trip").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    private fun assertPair(what: String, left: DpRect, right: DpRect) {
        assertTrue(
            "$what are not on the same line: ${left.top} vs ${right.top}",
            abs(left.top - right.top) <= 1.dp,
        )
        assertTrue(
            "$what are not side by side: ${left.right} then ${right.left}",
            right.left >= left.right,
        )
    }

    private fun abs(value: Dp): Dp = if (value.value < 0) Dp(-value.value) else value
}
