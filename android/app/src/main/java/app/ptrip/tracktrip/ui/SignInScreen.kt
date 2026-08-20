package app.ptrip.tracktrip.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.ui.theme.AppPrimary
import app.ptrip.tracktrip.ui.theme.AppPrimarySoft
import app.ptrip.tracktrip.ui.theme.AppText
import app.ptrip.tracktrip.ui.theme.HudPrimaryButton
import app.ptrip.tracktrip.ui.theme.TracktripTheme

@Composable
fun SignInScreen(
    state: SignInUiState,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppLogo()

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = AppText,
            modifier = Modifier.padding(top = 20.dp),
        )

        if (state is SignInUiState.Loading) {
            CircularProgressIndicator(
                color = AppPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            HudPrimaryButton(
                text = stringResource(R.string.sign_in_with_google),
                onClick = onSignInClick,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        if (state is SignInUiState.Error) {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * The app's own mark, as big as the screen it opens.
 *
 * The sign-in screen used to be the word "PTrips" alone in the middle of an
 * empty page — the one screen every rider sees before they have any reason to
 * trust what they are signing into, and it showed them nothing to recognise.
 *
 * ## The same mark as the launcher, drawn the way the launcher draws it
 *
 * `ic_launcher_foreground` rather than a new asset: it is already the mark
 * alone — the pin, the paper plane and the dashed path — already cropped and
 * centred on its own ink at five densities, and the whole point is that this
 * is the icon the rider just tapped. A second copy of the same artwork would
 * be one more thing to keep in step.
 *
 * That mipmap is an adaptive icon's *foreground layer*, so it is drawn here
 * under the two rules a launcher applies to it, and neither is decoration:
 *
 * - **The circle behind it** is `ic_launcher_background`, the same
 *   [AppPrimarySoft] the launcher paints under the mark. The mark's clouds are
 *   cut out of it rather than painted — transparent, not white — so on a bare
 *   page they would fill with whatever is behind, and the cut-outs would read
 *   as holes in the pin rather than as sky.
 * - **The 108:72 overdraw** is the mask maths. Only the middle 72dp of the
 *   layer's 108dp survives a launcher's mask, so a circle showing the whole
 *   108dp would draw the mark at two thirds the size the launcher gives it,
 *   floating in a ring of its own safe-zone padding. The image is laid out at
 *   [LOGO_LAYER] = 1.5 x [LOGO_SIZE] and clipped back to the circle, which is
 *   the same crop a round launcher icon performs — so what a rider sees here
 *   is what they tapped on the home screen, at 96dp instead of 48.
 *
 * `requiredSize` rather than `size`, because the Box's own 96dp is a hard
 * constraint the image has to be allowed to exceed for that crop to happen.
 *
 * No content description: the app's name is written underneath it in words,
 * and a screen reader announcing "PTrips" twice on a screen with one button
 * is worse than a mark it skips. [LOGO_TAG] is how the test finds it instead —
 * a tag rather than a description precisely because it adds no announcement.
 */
@Composable
internal fun AppLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .testTag(LOGO_TAG)
            .size(LOGO_SIZE)
            .clip(CircleShape)
            .background(AppPrimarySoft),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(LOGO_LAYER),
        )
    }
}

/**
 * How big the mark is on the sign-in screen.
 *
 * 96dp: twice the 48dp a launcher gives it, which is what makes it read as a
 * logo rather than as an icon that wandered onto the wrong screen — and still
 * short enough that the mark, the name and the Google button are one group in
 * the middle of the page on a small phone rather than three things scrolling
 * apart.
 */
private val LOGO_SIZE = 96.dp

/** The foreground layer at full size, so the circle crops it as a mask would. */
private val LOGO_LAYER = LOGO_SIZE * 108f / 72f

/**
 * How `SignInLogoTest` finds the mark.
 *
 * A tag and not a content description: the mark is decorative — the name is
 * written under it — and a description would buy the test the same hook at the
 * cost of a screen reader saying "PTrips" twice on a screen with one button.
 */
internal const val LOGO_TAG = "sign-in-logo"

@Preview(showBackground = true, backgroundColor = 0xFFF8F9FA)
@Composable
private fun SignInScreenPreview() {
    TracktripTheme {
        SignInScreen(state = SignInUiState.SignedOut, onSignInClick = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F9FA)
@Composable
private fun SignInScreenErrorPreview() {
    TracktripTheme {
        SignInScreen(
            state = SignInUiState.Error("Can't reach the server. Check your connection."),
            onSignInClick = {},
        )
    }
}
