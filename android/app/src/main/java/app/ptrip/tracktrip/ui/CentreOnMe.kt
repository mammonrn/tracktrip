package app.ptrip.tracktrip.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.ptrip.tracktrip.location.LocationFix
import app.ptrip.tracktrip.map.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long the button stays visibly busy, at the least.
 *
 * ## Why a floor at all
 *
 * The spinner used to be raised and lowered inside a single dispatch of the
 * main thread. `LocationFix.lastKnown` is a synchronous cache read, and on any
 * phone that has used a map recently it answers immediately — so
 * `busy = true`, the lookup, and `busy = false` all happened between two
 * frames. Compose renders the *result* of a frame, not each write within it,
 * so the busy state was never drawn: not briefly, not for one frame, never.
 * Proved rather than argued — `CentreOnMeTest` composes the real thing and
 * records what every composition saw.
 *
 * That is the whole of the "button does nothing" report. The other half of the
 * answer — the camera — is invisible for a different reason and cannot be
 * fixed here: a rider who has not panned is already looking at themselves, so
 * moving the map to where it already is changes no pixels. See
 * [PlacesMapScreen]'s locked state for what says so instead.
 *
 * 400ms because it is long enough to read as a flash of activity rather than
 * a glitch, and short enough that a cached fix still feels instant.
 */
const val CENTRE_BUSY_MIN_MS = 400L

/**
 * The "centre on me" button's half of the conversation.
 *
 * A press goes and asks the phone where it is — once, at the moment of the
 * press. Nothing here subscribes to anything: this screen wants a position,
 * not a feed, and the live feed exists only where a speed is being read off
 * it.
 *
 * Held as an object rather than as four `var`s in a screen's composition
 * because the interesting parts — that a second press does not queue a second
 * lookup, and that the busy state survives long enough to be drawn — are
 * rules, and rules that live inline in a navigation graph are rules nothing
 * can test.
 */
@Stable
class CentreOnMe internal constructor(
    private val scope: CoroutineScope,
    private val context: Context,
    private val minBusyMs: Long,
    private val onFix: (LatLng) -> Unit,
    private val onNoLocation: () -> Unit,
) {
    /**
     * Whether the phone is being asked where it is right now.
     *
     * The only thing on screen that says a press landed, in the ordinary case
     * where the map is already showing the rider.
     */
    var busy by mutableStateOf(false)
        private set

    private var asking: Job? = null

    /**
     * Go and find this rider.
     *
     * Ignored while an answer is already on its way: without the guard a
     * rider who pressed three times queued three location requests, each with
     * its own timeout, to answer a question that had already been asked.
     */
    fun press() {
        if (asking?.isActive == true) return
        asking = scope.launch {
            busy = true
            try {
                val here = look()
                if (here == null) onNoLocation() else onFix(here)
            } finally {
                // In a `finally` because the screen can go while the phone is
                // still thinking: this scope is cancelled with the
                // composition, and a spinner left true would be the state a
                // returning rider found the button in.
                busy = false
            }
        }
    }

    /**
     * The lookup, with the busy floor held alongside it rather than after it.
     *
     * Alongside, so a slow fix is not made slower: the floor and the question
     * run together and the wait is whichever is longer. The cache first and
     * the providers only if it is empty — `fastest` asks GPS and the network
     * at once, which is the difference between an answer and fifteen seconds
     * of nothing on a phone indoors.
     */
    private suspend fun look(): LatLng? = coroutineScope {
        val answer = async { LocationFix.nearest(context) }
        val floor = launch { delay(minBusyMs) }
        val fix = answer.await()
        floor.join()
        fix?.let { LatLng(it.latitude, it.longitude) }
    }
}

/**
 * [CentreOnMe], tied to the composition that asks for it.
 *
 * The callbacks are read through [rememberUpdatedState] so the object can be
 * remembered without freezing whichever lambdas the first composition made.
 */
@Composable
fun rememberCentreOnMe(
    onFix: (LatLng) -> Unit,
    onNoLocation: () -> Unit,
    minBusyMs: Long = CENTRE_BUSY_MIN_MS,
): CentreOnMe {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fix by rememberUpdatedState(onFix)
    val noLocation by rememberUpdatedState(onNoLocation)

    return remember(scope, context, minBusyMs) {
        CentreOnMe(
            scope = scope,
            context = context,
            minBusyMs = minBusyMs,
            onFix = { fix(it) },
            onNoLocation = { noLocation() },
        )
    }
}
