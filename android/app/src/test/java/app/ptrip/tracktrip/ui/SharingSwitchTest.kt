package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isToggleable
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That Settings has a switch for *this phone*, reachable when no trip is.
 *
 * ## The report
 *
 * A rider's trip had ended. They opened Settings > Location sharing to turn
 * the toggle on for it and could not: the section was a list of running trips
 * with a switch on each row, the ended trip was filtered out before the list
 * was drawn, and there was no other control on the screen. What they read
 * instead was "Sharing needs a running trip", where a privacy setting should
 * have been.
 *
 * The section answers two questions now, and these hold the line between them:
 * the switch at the top is the phone's own answer and is always pressable;
 * the rows below are which trip a fix goes to, and are subordinate to it.
 *
 * `DeviceSharingTest` holds the decisions; this holds that the control exists
 * and is reachable in the state the report was made in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SharingSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private val switched = mutableListOf<Boolean>()
    private val toggled = mutableListOf<Pair<Long, Boolean>>()

    private val running = Trip(1L, "Chiang Mai loop", 1L, Trip.STATUS_ACTIVE, Trip.ROLE_OWNER)

    private fun settings(state: SettingsUiState) {
        switched.clear()
        toggled.clear()
        compose.setContent {
            TracktripTheme {
                SettingsScreen(
                    state = state,
                    places = PlacesUiState(loading = false),
                    displayName = "Poom",
                    email = "rider@gmail.com",
                    photoUrl = null,
                    sharingTripId = null,
                    onOpenProfile = {},
                    onRemovePersonalPlace = {},
                    onRemoveSharedPlace = {},
                    onLanguageChange = {},
                    onSharingDurationChange = {},
                    onShareFromThisPhoneChange = { switched += it },
                    onToggleSharing = { trip, on -> toggled += trip.id to on },
                    onSignOut = {},
                    onBack = {},
                )
            }
        }
    }

    /** The section's own switch: the first toggleable thing on the screen. */
    private fun deviceSwitch() = compose.onAllNodes(isToggleable())[0]

    @Test
    fun `the switch is there and pressable with every trip finished`() {
        // The repro. No running trips at all — which is what an ended trip
        // leaves behind — and the rider still gets to answer the question
        // about their own phone.
        settings(SettingsUiState(loadingTrips = false, activeTrips = emptyList()))

        deviceSwitch().performScrollTo().assertIsEnabled().assertIsOn().performClick()

        assertEquals(listOf(false), switched)
    }

    @Test
    fun `an off switch can be turned back on with no trip running`() {
        settings(
            SettingsUiState(
                loadingTrips = false,
                activeTrips = emptyList(),
                shareFromThisPhone = false,
            )
        )

        deviceSwitch().performScrollTo().assertIsEnabled().assertIsOff().performClick()

        assertEquals(listOf(true), switched)
    }

    @Test
    fun `on with nowhere to send says so, rather than reading as sharing`() {
        // "On" is permission, not transmission, so the middle state has to be
        // sayable: willing, with nowhere to send. A switch alone cannot tell a
        // rider which of the two they are looking at.
        settings(SettingsUiState(loadingTrips = false, activeTrips = emptyList()))

        compose
            .onNodeWithText(
                "On. Nothing is being sent yet — pick a trip below, or start sharing from a trip."
            )
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun `off says it covers every trip, not just the ones on screen`() {
        settings(
            SettingsUiState(
                loadingTrips = false,
                activeTrips = emptyList(),
                shareFromThisPhone = false,
            )
        )

        compose
            .onNodeWithText("Off. This phone sends nothing, whatever trips are running.")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun `the no-trips line no longer stands where the control should be`() {
        settings(SettingsUiState(loadingTrips = false, activeTrips = emptyList()))

        // Still said, because it is true and useful — but as an explanation
        // sitting under a switch the rider has just used, not as the screen's
        // whole answer to "turn sharing on".
        compose
            .onNodeWithText(
                "No trip is running to share to. Create one, or join a friend's — " +
                    "the switch above stays as you set it."
            )
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun `a trip row is greyed while the phone's answer is no`() {
        settings(
            SettingsUiState(
                loadingTrips = false,
                activeTrips = listOf(running),
                shareFromThisPhone = false,
            )
        )

        compose.onNodeWithText("Chiang Mai loop").performScrollTo().assertExists()
        // The phone outranks the trip. A trip switch that could contradict the
        // one above it is how the two concerns got confused in the first place.
        compose.onAllNodes(isToggleable())[1].assertIsNotEnabled()
        assertEquals(emptyList<Pair<Long, Boolean>>(), toggled)
    }

    @Test
    fun `a trip row still picks its trip while the phone's answer is yes`() {
        settings(SettingsUiState(loadingTrips = false, activeTrips = listOf(running)))

        compose.onAllNodes(isToggleable())[1].performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf(1L to true), toggled)
    }
}
