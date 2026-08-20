package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That the first screen of the app opens on the rides that are coming, not on
 * a year of them.
 *
 * A rider who goes out most weekends has forty trips, and all forty were
 * printed here — so the three cards they actually wanted sat at the top of a
 * column they scrolled past every time they opened the app.
 *
 * Nothing is hidden, and that is half of what this asserts: the archive says
 * how many are in it and opens on one tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TripArchiveTest {

    @get:Rule
    val compose = createComposeRule()

    /** Newest first, which is the order `GET /trips` hands them over in. */
    private fun trips(count: Int): List<Trip> = (1..count).map {
        Trip(
            id = it.toLong(),
            name = "Ride $it",
            ownerId = 1L,
            status = Trip.STATUS_ACTIVE,
            role = Trip.ROLE_OWNER,
        )
    }

    private var archiveOpen = false

    private fun show(state: TripsUiState) {
        compose.setContent {
            TracktripTheme {
                TripListScreen(
                    state = state,
                    displayName = "Poom",
                    sharingTripName = null,
                    onOpenTrip = {},
                    onCreateTrip = {},
                    onAcceptInvite = {},
                    onRefresh = {},
                    onScanQr = {},
                    onOpenMap = {},
                    onOpenSettings = {},
                    onToggleArchive = { archiveOpen = it },
                )
            }
        }
    }

    @Test
    fun `only the newest three are printed, and the rest are counted`() {
        show(TripsUiState(loading = false, trips = trips(7)))

        compose.onNodeWithText("Ride 1").assertExists()
        compose.onNodeWithText("Ride 2").assertExists()
        compose.onNodeWithText("Ride 3").assertExists()

        // The fourth onwards are the archive. Not "not visible" — not
        // composed: a LazyColumn would happily build every one of forty.
        compose.onNodeWithText("Ride 4").assertDoesNotExist()

        // And the rider is told how much is behind the tap, because that is
        // the whole of what decides whether they take it.
        compose.onNodeWithText("Archived trips (4)").performScrollTo().assertExists()
    }

    @Test
    fun `opening the archive is what the chip asks for`() {
        show(TripsUiState(loading = false, trips = trips(7)))

        compose.onNodeWithText("Archived trips (4)").performScrollTo().performClick()

        assertEquals(true, archiveOpen)
    }

    @Test
    fun `an open archive shows every trip and the way back`() {
        show(TripsUiState(loading = false, trips = trips(7), archiveOpen = true))

        compose.onNodeWithText("Ride 1").performScrollTo().assertExists()
        compose.onNodeWithText("Ride 7").performScrollTo().assertExists()
        compose.onNodeWithText("Show recent only").performScrollTo().assertExists()
    }

    @Test
    fun `a rider with three trips or fewer is offered no archive at all`() {
        show(TripsUiState(loading = false, trips = trips(3)))

        // An empty archive is a control that answers a question nobody has.
        compose.onNodeWithText("Ride 3").assertExists()
        compose.onNodeWithText("Archived trips (0)").assertDoesNotExist()
        compose.onNodeWithText("Show recent only").assertDoesNotExist()
    }
}
