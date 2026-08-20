package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
 * sharing, edit the trip, end it — and reported them as one slab: every button
 * the same width, the same weight and 44dp tall, with gaps of 12dp, 12dp and
 * then 4dp. The last of those is the one that shows: "End this trip" all but
 * touching "Edit trip", which are the two controls least alike on the screen.
 *
 * Spacing is the kind of thing that drifts back the moment somebody adds a
 * fifth button with its own `padding(top = ...)`, so it is asserted here
 * rather than described in a comment. The numbers are the design: a button no
 * taller than Material's own minimum, one rhythm between them, and a wider
 * break where the owner's controls begin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TripActionsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val ownerOnALiveTrip = Trip(
        id = 1L,
        name = "Sunday run",
        ownerId = 7L,
        status = Trip.STATUS_ACTIVE,
        role = Trip.ROLE_OWNER,
    )

    private fun show(sharing: Boolean = true) {
        compose.setContent {
            TracktripTheme {
                TripDetailScreen(
                    state = TripDetailUiState(loading = false, trip = ownerOnALiveTrip),
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

    private fun bounds(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot()

    @Test
    fun `no control is taller than a button has to be`() {
        show()

        val heights = listOf(
            "Open live tracking",
            "Stop sharing",
            "Edit trip",
            "End this trip",
        ).associateWith { bounds(it).height }
        println("button heights = $heights")

        heights.forEach { (label, height) ->
            // 40dp is Material's minimum height for a button, and with a
            // labelLarge line inside it that is what 10dp of vertical padding
            // comes to. 12dp used to make it 44.
            assertTrue("$label is $height, taller than a 40dp button", height <= 41.dp)
            // Not a licence to shrink them into something nobody can hit with
            // a glove on: this fails just as loudly the other way.
            assertTrue("$label is $height, too small to press", height >= 38.dp)
        }
    }

    @Test
    fun `the owner's controls are set apart, and never crowded together`() {
        show()

        val openMap = bounds("Open live tracking")
        val stopSharing = bounds("Stop sharing")
        val editTrip = bounds("Edit trip")
        val endTrip = bounds("End this trip")

        val betweenRiding = stopSharing.top - openMap.bottom
        val intoOwnerGroup = editTrip.top - stopSharing.bottom
        val betweenOwner = endTrip.top - editTrip.bottom
        println("gaps: riding=$betweenRiding, into owner group=$intoOwnerGroup, owner=$betweenOwner")

        // One rhythm, from the Column rather than from each button's own
        // modifier — which is how the 4dp got in.
        //
        // 20dp between what is *drawn*, not the 12dp the Column asks for:
        // Material gives every button a 48dp touch target and centres its
        // 40dp of paint in it, so 4dp of each gap is the slot rather than the
        // spacing. The old block measured 16dp here and 8dp between "Edit
        // trip" and "End this trip".
        assertGap("between the riding controls", betweenRiding, 20.dp)
        assertGap("between the owner's controls", betweenOwner, 20.dp)

        // And a wider break, with a divider in it, where the trip's own
        // controls end and its owner's begin.
        assertTrue(
            "the owner's controls are not set apart: $intoOwnerGroup",
            intoOwnerGroup >= betweenOwner + 12.dp,
        )
    }

    private fun assertGap(what: String, actual: Dp, expected: Dp) {
        assertTrue(
            "$what is $actual, not the $expected rhythm",
            actual >= expected - 1.dp && actual <= expected + 1.dp,
        )
    }
}
