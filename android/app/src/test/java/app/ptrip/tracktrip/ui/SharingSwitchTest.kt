package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.assertIsEnabled
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
 * That Settings has one switch for sharing, reachable when no trip is.
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
 * ## And why there is only one switch
 *
 * The first fix put the phone's switch above those rows and kept them both.
 * That is one question too many — a rider is on one ride at a time, and the
 * phone is on one by construction, since the service reports to the trip it
 * was started for. The rows are gone; the switch says what it is doing in a
 * line instead, and names the trip when there is one.
 *
 * `DeviceSharingTest` holds the decisions; this holds that the control exists,
 * is reachable in the state the report was made in, and is the only one there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SharingSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private val switched = mutableListOf<Boolean>()

    private val running = Trip(1L, "Chiang Mai loop", 1L, Trip.STATUS_ACTIVE, Trip.ROLE_OWNER)

    private fun settings(
        state: SettingsUiState,
        sharingTripId: Long? = null,
        sharingTripName: String? = null,
    ) {
        switched.clear()
        compose.setContent {
            TracktripTheme {
                SettingsScreen(
                    state = state,
                    places = PlacesUiState(loading = false),
                    displayName = "Poom",
                    email = "rider@gmail.com",
                    photoUrl = null,
                    sharingTripId = sharingTripId,
                    sharingTripName = sharingTripName,
                    onOpenProfile = {},
                    onRemovePersonalPlace = {},
                    onRemoveSharedPlace = {},
                    onLanguageChange = {},
                    onSharingDurationChange = {},
                    onShareFromThisPhoneChange = { switched += it },
                    onSignOut = {},
                    onBack = {},
                )
            }
        }
    }

    /** The section's own switch: the only toggleable thing on the screen. */
    private fun theSwitch() = compose.onNode(isToggleable())

    @Test
    fun `the switch is there and pressable with every trip finished`() {
        // The repro. No running trips at all — which is what an ended trip
        // leaves behind — and the rider still gets to answer the question
        // about their own phone.
        settings(SettingsUiState(loadingTrips = false, activeTrips = emptyList()))

        theSwitch().performScrollTo().assertIsEnabled().assertIsOn().performClick()

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

        theSwitch().performScrollTo().assertIsEnabled().assertIsOff().performClick()

        assertEquals(listOf(true), switched)
    }

    @Test
    fun `there is exactly one switch, even with a trip running`() {
        // The whole of this change: no second control asking which trip. A
        // rider is on one ride at a time, and `onNode` fails outright if a
        // second toggleable thing appears.
        settings(SettingsUiState(loadingTrips = false, activeTrips = listOf(running)))

        theSwitch().performScrollTo().assertIsEnabled().assertIsOn()
        // The trip is named in the line when it is being shared with, not
        // offered as a row of its own to switch.
        compose.onNodeWithText("Chiang Mai loop").assertDoesNotExist()
    }

    @Test
    fun `sharing names the trip it is sharing with`() {
        settings(
            SettingsUiState(loadingTrips = false, activeTrips = listOf(running)),
            sharingTripId = running.id,
            sharingTripName = running.name,
        )

        compose
            .onNodeWithText("On. Sharing your position with Chiang Mai loop.")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun `on with nowhere to send says so, rather than reading as sharing`() {
        // "On" is permission, not transmission, so the middle state has to be
        // sayable: willing, with no trip to send to. A switch alone cannot
        // tell a rider which of the two they are looking at.
        settings(SettingsUiState(loadingTrips = false, activeTrips = emptyList()))

        compose
            .onNodeWithText("On. No trip is running, so nothing is being sent yet.")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun `off says it covers every trip, not just the ones on screen`() {
        settings(
            SettingsUiState(
                loadingTrips = false,
                activeTrips = listOf(running),
                shareFromThisPhone = false,
            )
        )

        compose
            .onNodeWithText("Off. This phone sends nothing, whatever trips are running.")
            .performScrollTo()
            .assertExists()
    }
}
