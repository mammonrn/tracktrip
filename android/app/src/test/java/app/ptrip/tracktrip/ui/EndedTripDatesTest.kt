package app.ptrip.tracktrip.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a member row says under the rider's name once the trip is over.
 *
 * ## The bug
 *
 * Every row carries the age of that rider's last position report, which is the
 * most useful number on the screen while anybody is still reporting. On a
 * finished trip nobody is, so the figure only counts up: "Owner · 1206 min
 * ago" on a ride that ended yesterday, growing a digit every few days, saying
 * nothing except that time passes.
 *
 * What replaces it is the days the trip actually ran — in the line it already
 * occupied, on a card that does not get taller. Both halves are asserted here,
 * because the fix would be a different bug if the card grew: these rows are a
 * list somebody scrolls.
 *
 * `TripDatesTest` holds the wording rules themselves. This holds that the
 * right one of the two things is on the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EndedTripDatesTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A day recently enough ago that the year rule leaves the year off, and a
     * report old enough that the old wording would be unmistakable.
     *
     * Built off the wall clock rather than a fixed date because the screen
     * reads the real one — `TripDates` decides on the year by comparing with
     * now, and a hard-coded 2026 would start printing a year of its own the
     * following January and fail this test on a date nobody changed anything.
     */
    private val ranOn: Instant = Instant.now().minusSeconds(2 * 24 * 60 * 60)

    private val expectedDay: String = DateTimeFormatter
        .ofPattern("d MMM", Locale.ENGLISH)
        .format(ranOn.atZone(ZoneId.systemDefault()))

    private fun trip(active: Boolean) = Trip(
        id = 1L,
        name = "Sunday run",
        ownerId = 7L,
        status = if (active) Trip.STATUS_ACTIVE else "ended",
        role = Trip.ROLE_OWNER,
        createdAt = ranOn.toString(),
        endedAt = ranOn.plusSeconds(6 * 60 * 60).toString(),
    )

    private val owner = MemberPosition(
        userId = 7L,
        displayName = "Poom",
        username = null,
        photoUrl = null,
        role = Trip.ROLE_OWNER,
        isSharing = false,
        sharingUntil = null,
        lat = 18.79,
        lng = 98.98,
        speedMps = null,
        batteryPct = 64,
        // Twenty hours ago, which is what used to be printed as "1206 min ago".
        recordedAt = Instant.now().minusSeconds(20 * 60 * 60).toString(),
    )

    /**
     * The screen, with "has this trip ended" held in state rather than baked
     * in — the height test needs both answers, and a Compose rule only lets
     * one `setContent` happen per test.
     */
    private fun show(active: Boolean): MutableState<Boolean> {
        val running = mutableStateOf(active)
        compose.setContent {
            TracktripTheme {
                TripDetailScreen(
                    state = TripDetailUiState(
                        loading = false,
                        trip = trip(running.value),
                        members = listOf(owner),
                    ),
                    currentUserId = 7L,
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
        return running
    }

    /** The member row's second line, whichever of the two it is carrying. */
    private fun roleLine(suffix: String) = "Owner · $suffix"

    @Test
    fun `a finished trip says the days it ran instead of counting minutes up`() {
        show(active = false)

        compose
            .onNodeWithText(roleLine("Created $expectedDay – Ended $expectedDay"))
            .assertIsDisplayed()
    }

    @Test
    fun `a running trip keeps the report age, which is what it is for`() {
        show(active = true)

        // 20 hours, in the whole minutes the row prints.
        compose.onNodeWithText(roleLine("1200 min ago"), substring = true).assertIsDisplayed()
    }

    @Test
    fun `the dates do not make the card taller than the age did`() {
        // The rule the change had to keep: this is a list somebody scrolls,
        // and a second line on every row of a finished trip would be a worse
        // screen than the number it replaced.
        val running = show(active = true)
        val whileRunning = compose.onNodeWithText("Poom").getUnclippedBoundsInRoot().height

        running.value = false
        compose.waitForIdle()
        val afterwards = compose.onNodeWithText("Poom").getUnclippedBoundsInRoot().height

        assertTrue(
            "member row grew from $whileRunning to $afterwards once the trip ended",
            afterwards <= whileRunning,
        )
    }
}
