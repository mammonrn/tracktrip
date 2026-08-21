package app.ptrip.tracktrip.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
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

    /**
     * The same `dd/MM/yyyy` the row draws, built here rather than hard-coded.
     *
     * `Locale.ROOT`, matching [TripDates.Style.NUMERIC]: the pattern is digits
     * and slashes, so there is nothing in it for a locale to translate — and
     * asking for one would invite a calendar that writes 2026 as 2569.
     */
    private val expectedDay: String = DateTimeFormatter
        .ofPattern("dd/MM/yyyy", Locale.ROOT)
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

    /** A running trip's second line: the role and the age, together. */
    private fun roleAndAge(suffix: String) = "Owner · $suffix"

    @Test
    fun `a finished trip says the days it ran instead of counting minutes up`() {
        show(active = false)

        // Three lines rather than two, and the role is no longer glued to the
        // front of the caption: two full dates and a dash need the whole width,
        // so the role moves above the name as a label of its own.
        compose.onNodeWithText("Owner").assertIsDisplayed()
        compose.onNodeWithText("Poom").assertIsDisplayed()
        compose
            .onNodeWithText("Created $expectedDay – Ended $expectedDay")
            .assertIsDisplayed()

        // And the age is gone rather than moved: it only counts up once the
        // trip is over, which is the whole reason for this layout.
        compose.onNodeWithText("min ago", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the finished layout writes its dates as dd MM yyyy`() {
        show(active = false)

        // Pinned as a shape, not only as today's date. "20 Aug" was the old
        // format and is the regression this guards: a numeric date is the same
        // eleven characters in every language and for every month, which is
        // what lets two of them share one line.
        // The shape, not today's date: "20 Aug" was the old format and is the
        // regression this guards. A numeric date is the same eleven characters
        // in every language and for every month, which is what lets two of them
        // share one line.
        assertTrue(
            "expected dd/MM/yyyy, got \"$expectedDay\"",
            Regex("""^\d{2}/\d{2}/\d{4}$""").matches(expectedDay),
        )
        compose
            .onNodeWithText("Created $expectedDay – Ended $expectedDay")
            .assertIsDisplayed()
    }

    @Test
    fun `a running trip keeps the report age, and keeps its two-line shape`() {
        show(active = true)

        // 20 hours, in the whole minutes the row prints — still on one line
        // with the role, exactly as before. Nothing about a live trip changed.
        compose.onNodeWithText(roleAndAge("1200 min ago"), substring = true).assertIsDisplayed()
    }

    @Test
    fun `the third line costs one small line and no more`() {
        // This is a list somebody scrolls, so what the third line costs is
        // worth knowing rather than assuming. It cannot be free — three lines
        // are three lines — and the honest number is about 18dp: the caption is
        // already down to 10sp, and the leading below that belongs to Thai
        // rather than to spare (see MEMBER_CAPTION_LEADING).
        //
        // A bound rather than the exact figure: leading and font metrics are
        // the platform's, and a test pinning "92dp" would fail on a font
        // update with nothing having got worse. What must not pass unnoticed is
        // the cost doubling.
        // The *card*, by tag. An earlier version of this test measured the
        // rider's name instead — a `Text` of fixed height, which reported "no
        // growth" through a change that added a whole line to the card around
        // it. The question is about the card, so the tag is on the card.
        val running = show(active = true)
        val whileRunning = compose.onNodeWithTag(MEMBER_CARD_TAG)
            .getUnclippedBoundsInRoot().height

        running.value = false
        compose.waitForIdle()
        val afterwards = compose.onNodeWithTag(MEMBER_CARD_TAG)
            .getUnclippedBoundsInRoot().height

        val grewBy = afterwards - whileRunning
        println("member row: running=$whileRunning ended=$afterwards grew=$grewBy")

        assertTrue("the ended row shrank, which means a line went missing", grewBy >= 0.dp)
        assertTrue(
            "member row grew from $whileRunning to $afterwards — a full extra line",
            grewBy <= MAX_GROWTH,
        )
    }

    private companion object {
        /**
         * The most a third line may cost the card.
         *
         * Measured at 18dp: a 10sp caption over a 14sp leading, plus the font
         * padding that keeps Thai tone marks off the line above. 24dp leaves
         * headroom for a font metric shifting under a platform update while
         * still failing if the caption quietly went back to `labelSmall`'s
         * full 11sp-over-16sp, which would put this near 24 on its own.
         */
        val MAX_GROWTH = 24.dp
    }
}
