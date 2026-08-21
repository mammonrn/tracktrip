package app.ptrip.tracktrip.ui

import android.content.Context
import androidx.annotation.StringRes
import app.ptrip.tracktrip.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What the app says back after it has changed something on the server.
 *
 * ## Why this exists
 *
 * Every write in this app went out the same way and came back three different
 * ways. A rename put its failure on the form; a leave put its failure on the
 * member list; a place deleted itself off a list and said nothing at all,
 * success or failure, which is the one that started this — a rider tapping the
 * cross on a place had no way to tell "gone" from "the request never landed",
 * because both look like a row that is still there for a moment.
 *
 * Successes were worse: there were none. The only signal that anything had
 * worked was the screen changing underneath, and half of these actions change
 * nothing visible — an invitation sent, a trip renamed from a form that closes,
 * a place removed from a list a rider has already scrolled past.
 *
 * So there is one channel, one look, and one rule: **every action that writes
 * to the server says whether it worked.** A view model raises a
 * [FeedbackMessage] here; the activity draws it once, over whatever screen the
 * rider is on. Screens do not each grow their own banner, and a message
 * survives the screen that started it — which matters, because several of
 * these actions navigate away on success.
 *
 * ## Why the text is a resource and not a string
 *
 * A view model has no `Context` and must not have one, so it cannot call
 * `getString`. Sending [FeedbackText.Res] instead of a sentence keeps the
 * wording translatable — the app is fully Thai, and `StringCoverageTest` fails
 * the build over an English string with no Thai — while leaving
 * [FeedbackText.Plain] for the one case where the app is *not* the author: the
 * backend's own error text, which is deliberately shown verbatim (see
 * [ApiErrorText][apiErrorText] for why).
 */
enum class FeedbackTone {
    /** It worked. Drawn green. */
    SUCCESS,

    /** It did not. Drawn red, and given longer to be read. */
    FAILURE,
}

/** Either something this app wrote, or something the server did. */
sealed interface FeedbackText {

    /** App wording, translated. [args] fill a format string's placeholders. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : FeedbackText

    /**
     * A sentence from somewhere else — in practice the backend's `error`
     * field, which is written to be read ("trip has ended", "only the trip
     * owner can do this") and is shown as it arrived rather than re-worded
     * here, so the app's rules cannot drift from the server's.
     */
    data class Plain(val value: String) : FeedbackText
}

/** Resolves either kind against real resources. Needs a `Context`; the UI has one. */
fun FeedbackText.resolve(context: Context): String = when (this) {
    is FeedbackText.Plain -> value
    is FeedbackText.Res -> context.getString(id, *args.toTypedArray())
}

/**
 * One answer, on its way to the rider.
 *
 * [onRetry] is offered only where repeating the call is the whole fix — a
 * timed-out delete, a failed rename — and never where the app would have to
 * reassemble something first (a form the rider has since left, a join code the
 * server has already burned). A Retry that quietly does something else is
 * worse than no Retry.
 */
data class FeedbackMessage(
    val text: FeedbackText,
    val tone: FeedbackTone,
    val onRetry: (() -> Unit)? = null,
)

/**
 * The one place messages are raised, and the one place the UI reads them.
 *
 * A [SharedFlow] with no replay: a message is for the moment it happened. A
 * rider who was not looking at the app when a background poll failed does not
 * want to be told about it when they come back, and a replayed success would
 * announce itself again on every rotation.
 *
 * The small buffer is for the opposite case — several writes finishing at
 * once — so nothing is dropped between the emit and the collector picking it
 * up. Oldest first out if even that fills, for the same reason the UI collects
 * the latest rather than queueing (see `rememberFeedbackHostState`): the
 * newest answer is the one a rider is waiting for.
 */
class FeedbackCenter {

    private val _messages = MutableSharedFlow<FeedbackMessage>(
        extraBufferCapacity = FEEDBACK_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val messages: SharedFlow<FeedbackMessage> = _messages.asSharedFlow()

    /** It worked. [args] fill the placeholders in [text]. */
    fun succeeded(@StringRes text: Int, vararg args: Any) {
        emit(FeedbackMessage(FeedbackText.Res(text, args.toList()), FeedbackTone.SUCCESS))
    }

    /**
     * It did not work, and the server said why.
     *
     * [message] is `ApiException.message`, which is nullable on `Throwable`
     * and empty on a failure that never reached a server with an opinion. In
     * that case the app says something rather than showing a blank line.
     */
    fun failed(message: String?, onRetry: (() -> Unit)? = null) {
        val text = message?.takeIf { it.isNotBlank() }?.let(FeedbackText::Plain)
            ?: FeedbackText.Res(R.string.feedback_failed)
        emit(FeedbackMessage(text, FeedbackTone.FAILURE, onRetry))
    }

    /** It did not work, and this app has the better wording for why. */
    fun failed(@StringRes text: Int, vararg args: Any, onRetry: (() -> Unit)? = null) {
        emit(
            FeedbackMessage(
                FeedbackText.Res(text, args.toList()),
                FeedbackTone.FAILURE,
                onRetry,
            )
        )
    }

    private fun emit(message: FeedbackMessage) {
        // tryEmit rather than emit: raising a message must never suspend the
        // coroutine that just finished a write, and with DROP_OLDEST it cannot
        // fail. A view model is not the place to await a snackbar.
        _messages.tryEmit(message)
    }

    private companion object {
        /**
         * How many answers may sit between an emit and the collector.
         *
         * Four is generous: the app raises one message per action a rider
         * takes, and even the burstiest — one press of Invite, which is one
         * request per address — is deliberately reduced to a single answer
         * before it gets here. The buffer exists so [emit] never has to
         * suspend a coroutine that has just finished a write, not to keep a
         * queue for the screen to work through.
         */
        const val FEEDBACK_BUFFER = 4
    }
}
