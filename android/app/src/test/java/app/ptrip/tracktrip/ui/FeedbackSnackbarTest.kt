package app.ptrip.tracktrip.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.ui.theme.FEEDBACK_SNACKBAR_TAG
import app.ptrip.tracktrip.ui.theme.HudSnackbarHost
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The other end of `FeedbackCenterTest`: that a message raised by a view model
 * actually reaches the rider, in words, over whatever screen they are on.
 *
 * The two halves are worth testing separately because they fail separately.
 * A view model can raise a perfectly good message into a channel nobody is
 * collecting — which is what "the app said nothing" looked like before any of
 * this existed — and a bar can be drawn with the wrong sentence in it. This is
 * the second half, composed the way `MainActivity` composes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class FeedbackSnackbarTest {

    @get:Rule
    val compose = createComposeRule()

    private val center = FeedbackCenter()

    private fun show() {
        compose.setContent {
            TracktripTheme {
                val host = rememberFeedbackHostState(center)
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { HudSnackbarHost(host) },
                ) { padding ->
                    Text("a screen", modifier = Modifier.padding(padding))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `nothing is drawn until something has been done`() {
        show()

        // The bar is on the activity's Scaffold, so it is composed from the
        // first frame of the first screen. It must be composed *empty*.
        compose.onNodeWithTag(FEEDBACK_SNACKBAR_TAG).assertDoesNotExist()
    }

    @Test
    fun `a success is drawn in the app's own words, translated`() {
        show()

        center.succeeded(R.string.feedback_place_deleted_shared)
        compose.waitForIdle()

        compose.onNodeWithTag(FEEDBACK_SNACKBAR_TAG).assertIsDisplayed()
        // Resolved against real resources — which is the point of sending a
        // resource id from a view model rather than a sentence.
        compose.onNodeWithText("Place deleted").assertIsDisplayed()
    }

    @Test
    fun `a format string gets its arguments`() {
        show()

        center.succeeded(R.string.feedback_trip_joined, "Pai run")
        compose.waitForIdle()

        compose.onNodeWithText("Joined \"Pai run\"").assertIsDisplayed()
    }

    @Test
    fun `a failure shows the server's sentence, with a Retry that runs`() {
        show()

        var attempts = 0
        center.failed("trip has ended") { attempts += 1 }
        compose.waitForIdle()

        compose.onNodeWithText("trip has ended").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()

        assertEquals(1, attempts)
    }

    @Test
    fun `a failure with nothing to retry offers no button`() {
        show()

        // Refusals the app cannot usefully repeat — a burnt join code, a
        // second trip while one is running — say why and stop there. A Retry
        // that only ever earns the same refusal teaches a rider to ignore it.
        center.failed("that code has expired")
        compose.waitForIdle()

        compose.onNodeWithText("that code has expired").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `the newest answer replaces the one on screen rather than queueing behind it`() {
        show()

        // Two presses in a row are two questions, and the rider is waiting on
        // the second. `showSnackbar` suspends until the bar goes, so without
        // the dismiss in `rememberFeedbackHostState` a failure would sit
        // behind a success for four seconds and arrive looking like an answer
        // to something else.
        center.succeeded(R.string.feedback_place_deleted_shared)
        compose.waitForIdle()
        center.failed("you didn't add this place")
        compose.waitForIdle()

        compose.onNodeWithText("you didn't add this place").assertIsDisplayed()
        compose.onNodeWithText("Place deleted").assertDoesNotExist()
    }
}
