package app.ptrip.tracktrip.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * A back stack that always has something on it. [pop] refuses to empty it, so
 * the home screen can't be navigated away from — the system back button falls
 * through to leaving the app instead, which is what a user expects there.
 */
class BackStack(initial: List<Screen>) {

    constructor(initial: Screen) : this(listOf(initial))

    private val entries: SnapshotStateList<Screen> =
        mutableStateListOf<Screen>().apply { addAll(initial.ifEmpty { listOf(Screen.Trips) }) }

    val current: Screen get() = entries.last()

    val canGoBack: Boolean get() = entries.size > 1

    /** The whole history, oldest first — what [BackStackSaver] writes down. */
    internal val screens: List<Screen> get() = entries.toList()

    fun push(screen: Screen) {
        entries.add(screen)
    }

    fun pop(): Boolean {
        if (!canGoBack) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /** Drops everything above the home screen — used after signing out. */
    fun resetToRoot() {
        while (canGoBack) {
            entries.removeAt(entries.lastIndex)
        }
    }
}

/**
 * How the back stack survives the activity being rebuilt.
 *
 * It is rebuilt more often than it looks: changing the app's language
 * recreates the activity (that is how AppCompat applies a locale below Android
 * 13), and so does a rotation, a system font-size change, or the system
 * reclaiming memory behind a backgrounded app. With a plain `remember` the
 * history died each time and the rider landed back on the trip list — which
 * reads, from the rider's side, as screens that have lost their back button.
 */
internal val BackStackSaver: Saver<BackStack, Any> = listSaver(
    save = { stack -> stack.screens.map(::screenToken) },
    restore = { tokens -> BackStack(screensFromTokens(tokens.map { it.toString() })) },
)

@Composable
fun rememberBackStack(initial: Screen = Screen.Trips): BackStack =
    rememberSaveable(saver = BackStackSaver) { BackStack(initial) }
