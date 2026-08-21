package app.ptrip.tracktrip.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.ptrip.tracktrip.ui.FeedbackTone

/**
 * The tone travels with the message.
 *
 * Material's own [SnackbarVisuals] carries a string, an action label and a
 * duration, and nothing that says whether the thing being reported worked. A
 * green bar and a red one are the whole point here, so the host has to be able
 * to ask — and a subclass is how a `SnackbarHostState` carries anything the
 * library did not think of.
 */
class FeedbackVisuals(
    override val message: String,
    val tone: FeedbackTone,
    override val actionLabel: String?,
) : SnackbarVisuals {

    /**
     * A failure stays longer than a success.
     *
     * A success is a confirmation of something the rider just did and already
     * expected; a failure is news, is often a sentence rather than two words,
     * and may have a Retry on it that has to still be there when they reach
     * for it.
     */
    override val duration: SnackbarDuration =
        if (tone == FeedbackTone.FAILURE) SnackbarDuration.Long else SnackbarDuration.Short

    /**
     * No dismiss cross. Both kinds go by themselves, and a permanent control
     * on a bar that is about to disappear is a target that moves out from
     * under a thumb.
     */
    override val withDismissAction: Boolean = false
}

/**
 * The tag a test finds the bar by, whichever screen raised it.
 *
 * Worth having on top of matching the sentence: the sentences are translated
 * and several of them are the server's, so a test that only ever asked for
 * text could not tell "no bar was drawn" from "the bar said something else".
 */
const val FEEDBACK_SNACKBAR_TAG = "feedback-snackbar"

/**
 * The app's one snackbar.
 *
 * Solid rather than the app's usual white-card-with-a-tint, and that is
 * deliberate: this is the only surface in the app that appears *over* whatever
 * the rider was reading, including a full-bleed map, and a white card with a
 * coloured hairline is exactly the thing that disappears against OpenStreetMap
 * tiles — the same lesson [AppRouteProgress] records. Colour is never the only
 * carrier: the sentence says what happened, and a failure says so in words.
 */
@Composable
fun HudSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val tone = (data.visuals as? FeedbackVisuals)?.tone ?: FeedbackTone.SUCCESS
    val container = if (tone == FeedbackTone.FAILURE) AppDanger else AppSuccess

    Snackbar(
        modifier = modifier.padding(12.dp).testTag(FEEDBACK_SNACKBAR_TAG),
        containerColor = container,
        contentColor = AppOnPrimary,
        shape = AppCardShape,
        action = data.visuals.actionLabel?.let { label ->
            {
                TextButton(onClick = { data.performAction() }) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = AppOnPrimary,
                    )
                }
            }
        },
    ) {
        Text(text = data.visuals.message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Where [HudSnackbar]s appear. One per activity — see `FeedbackCenter`. */
@Composable
fun HudSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data -> HudSnackbar(data) }
}
