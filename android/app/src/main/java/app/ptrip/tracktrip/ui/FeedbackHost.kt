package app.ptrip.tracktrip.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.collectLatest
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.ui.theme.FeedbackVisuals

/**
 * Turns the messages a view model raises into the bar the rider sees.
 *
 * ## Why the collection is here and not in each screen
 *
 * Several of these actions navigate on success — creating a trip opens it,
 * joining one opens it, leaving one goes back to the list — so a snackbar
 * owned by the screen that started the write would be torn down in the same
 * frame as the answer arrived. Collected at the activity, above the whole
 * navigation stack, the answer outlives the screen that asked the question.
 *
 * It also means there is exactly one bar. Two screens each with their own host
 * is how an app ends up stacking two answers to one press.
 *
 * The collector is keyed on the centre alone, so navigating does not restart
 * it — and it stops with the composition, which is the behaviour that matters:
 * an answer that arrives while the app is not on screen is not saved up to be
 * announced later. See `FeedbackCenter` for why the flow has no replay.
 */
@Composable
fun rememberFeedbackHostState(center: FeedbackCenter): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val retryLabel = stringResource(R.string.feedback_retry)

    LaunchedEffect(center) {
        // `collectLatest`, and this is the load-bearing part of the whole
        // file. `showSnackbar` suspends for as long as the bar is on screen,
        // so a plain `collect` would leave the collector parked inside the
        // first message for four seconds while the second waited in the
        // buffer — and the answer to a press a rider made *now* would arrive
        // after the answer to the last one had finished being read, looking
        // like an answer to the wrong question.
        //
        // Collecting the latest cancels the suspended `showSnackbar`, which
        // takes the old bar off, and draws the new one immediately. The newest
        // answer is the one on screen, which is the one the rider is waiting
        // for.
        center.messages.collectLatest { message ->
            val result = hostState.showSnackbar(
                FeedbackVisuals(
                    message = message.text.resolve(context),
                    tone = message.tone,
                    // The label is only drawn where there is something to
                    // retry — see [FeedbackMessage.onRetry].
                    actionLabel = message.onRetry?.let { retryLabel },
                )
            )

            if (result == SnackbarResult.ActionPerformed) {
                message.onRetry?.invoke()
            }
        }
    }

    return hostState
}
