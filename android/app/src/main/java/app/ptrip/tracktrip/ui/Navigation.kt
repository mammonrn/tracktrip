package app.ptrip.tracktrip.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Where the app can be.
 *
 * Deliberately a plain sealed hierarchy rather than Navigation Compose: five
 * screens and one argument between them don't need a route DSL, and the only
 * published navigation-compose builds at the time of writing are 2.10.0
 * pre-releases. If deep links or nested graphs ever arrive, this is the thing
 * to replace.
 */
sealed interface Screen {
    data object Trips : Screen
    data object CreateTrip : Screen
    data object Settings : Screen
    data class TripDetail(val tripId: Long) : Screen
}

/**
 * A back stack that always has something on it. [pop] refuses to empty it, so
 * the home screen can't be navigated away from — the system back button falls
 * through to leaving the app instead, which is what a user expects there.
 */
class BackStack(initial: Screen) {
    private val entries: SnapshotStateList<Screen> = mutableStateListOf(initial)

    val current: Screen get() = entries.last()

    val canGoBack: Boolean get() = entries.size > 1

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

@Composable
fun rememberBackStack(initial: Screen = Screen.Trips): BackStack = remember { BackStack(initial) }
