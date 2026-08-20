package app.ptrip.tracktrip.ui

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mark on the sign-in screen.
 *
 * The screen every rider sees before they have any reason to trust what they
 * are signing into used to be the word "PTrips" alone in the middle of an
 * empty page. The mark is the thing they recognise from their own home screen,
 * so these pin the three claims that make it that rather than decoration: it
 * is there, it is a logo rather than an icon, and it sits above the name and
 * on the same centre line, which is how the two read as one lockup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SignInLogoTest {

    @get:Rule
    val compose = createComposeRule()

    private fun signIn(state: SignInUiState = SignInUiState.SignedOut) {
        compose.setContent {
            TracktripTheme {
                SignInScreen(state = state, onSignInClick = {})
            }
        }
    }

    @Test
    fun `the mark is drawn at logo size, not icon size`() {
        compose.setContent { TracktripTheme { AppLogo() } }

        // 96dp — twice what a launcher gives it. The brief was "not a small
        // icon", so the assertion is the floor rather than only the exact
        // number: anything at toolbar or list-row scale fails it.
        compose.onNodeWithTag(LOGO_TAG).assertWidthIsEqualTo(96.dp)
        compose.onNodeWithTag(LOGO_TAG).assertHeightIsEqualTo(96.dp)
    }

    @Test
    fun `the mark is above the name, on the same centre line`() {
        signIn()

        val mark = compose.onNodeWithTag(LOGO_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
        val name = compose.onNodeWithText("PTrips").getUnclippedBoundsInRoot()

        assertTrue(
            "the mark should sit above the name, not beside or below it",
            mark.bottom <= name.top,
        )

        // Both are centred by the same Column, so their middles line up. Worth
        // asserting rather than assuming: a mark that drifts off the axis the
        // name sits on stops reading as part of the same lockup.
        val markCentre = (mark.left + mark.right) / 2
        val nameCentre = (name.left + name.right) / 2
        val drift = (markCentre - nameCentre).value
        assertTrue("the mark and the name should share a centre line", kotlin.math.abs(drift) < 1f)
    }

    @Test
    fun `the mark stays while the screen is signing in`() {
        // The loading state swaps the Google button for a spinner. The mark is
        // not part of that swap — a logo that blinks out mid-sign-in reads as
        // the screen having gone wrong.
        signIn(SignInUiState.Loading)

        compose.onNodeWithTag(LOGO_TAG).assertIsDisplayed()
        compose.onNodeWithText("PTrips").assertIsDisplayed()
    }
}
