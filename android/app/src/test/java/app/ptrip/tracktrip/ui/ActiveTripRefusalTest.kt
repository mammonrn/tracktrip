package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.ptrip.tracktrip.data.ActiveTripException
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Being told, in your own language, which trip is in the way.
 *
 * The backend now refuses a second active trip per rider — `POST /trips`,
 * accepting an invite and redeeming a join code all answer 409. The app
 * already showed the server's `error` string for any refusal, so the rider
 * would have read something true; they would have read it in English, on a
 * phone that may be set to Thai, for the one refusal they will meet every time
 * they forget to end a ride.
 *
 * So this refusal alone is re-worded here, and these hold the three claims
 * that make it worth doing: the client recognises it, the trip is named, and
 * every other failure still shows exactly what the server said.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ActiveTripRefusalTest {

    @get:Rule
    val compose = createComposeRule()

    private val refused = ActiveTripException(
        message = "You're already on an active trip, \"Chiang Mai loop\". " +
            "End it before starting or joining another.",
        tripId = 7L,
        tripName = "Chiang Mai loop",
    )

    private val expected =
        "You're already on \"Chiang Mai loop\". End that trip before starting or joining another."

    // --- the exception ----------------------------------------------------

    @Test
    fun `the refusal is a 409 carrying the trip in the way`() {
        assertEquals(409, refused.status)
        assertEquals("Chiang Mai loop", refused.tripName)
        assertEquals(7L, refused.tripId)
        // The server's own sentence survives as the fallback for a build
        // talking to a server that predates the code.
        assertTrue(refused.message!!.contains("Chiang Mai loop"))
    }

    // --- the wording ------------------------------------------------------

    @Test
    fun `the app names the trip rather than repeating the server`() {
        compose.setContent {
            TracktripTheme {
                androidx.compose.material3.Text(
                    text = apiErrorText(refused.message, refused.tripName).orEmpty()
                )
            }
        }

        compose.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `every other failure still says what the server said`() {
        compose.setContent {
            TracktripTheme {
                androidx.compose.material3.Text(
                    text = apiErrorText("this join code has expired", null).orEmpty()
                )
            }
        }

        // Re-wording the rest here would let the app's rules drift from the
        // server's, and an expired code and an unknown one are different
        // problems with different next steps.
        compose.onNodeWithText("this join code has expired").assertExists()
    }

    @Test
    fun `no failure at all is no line at all`() {
        compose.setContent {
            TracktripTheme {
                assertNull(apiErrorText(null, null))
            }
        }
    }

    // --- the three screens it can reach -----------------------------------

    @Test
    fun `creating a trip says which one is in the way`() {
        compose.setContent {
            TracktripTheme {
                CreateTripScreen(
                    creating = false,
                    error = refused.message,
                    blockedByTripName = refused.tripName,
                    onCreate = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `scanning a code says which one is in the way`() {
        compose.setContent {
            TracktripTheme {
                ScanQrScreen(
                    joining = false,
                    error = refused.message,
                    blockedByTripName = refused.tripName,
                    onCodeScanned = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `accepting an invite says which one is in the way, and keeps the invite`() {
        val state = TripsUiState(
            loading = false,
            error = refused.message,
            blockedByTripName = refused.tripName,
        )

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
                    isSuperuser = false,
                    onShowAllTrips = {},
                    onToggleArchive = {},
                )
            }
        }

        compose.onNodeWithText(expected).assertExists()
        // The server leaves a refused invite pending rather than consuming it,
        // so nothing was taken from the rider by the refusal — and the state
        // the screen draws says the same: the invite list is untouched.
        assertEquals(emptyList<Long>(), state.invites.map { it.id })
        assertNull(state.acceptingInviteId)
    }
}
