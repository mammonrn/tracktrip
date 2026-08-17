package app.ptrip.tracktrip.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What this phone is currently broadcasting, and to which trip.
 *
 * @param expiresAtMillis when the session lapses on its own, or null for
 *   "until the rider stops it".
 */
data class ActiveSharing(
    val tripId: Long,
    val tripName: String,
    val expiresAtMillis: Long?,
    /** Set once the first fix has actually been accepted by the server. */
    val lastReportedAtMillis: Long? = null,
)

/**
 * Process-wide state of the location service.
 *
 * A plain object rather than something handed round the graph: the service and
 * the UI are in the same process but have no other connection — the service is
 * started by an Intent and outlives every screen — and this is the one fact
 * they both need. Whoever asks gets the same answer, including a screen
 * composed for the first time after the service was already running.
 *
 * The service is the only writer. Everything else observes.
 */
object SharingState {

    private val _active = MutableStateFlow<ActiveSharing?>(null)
    val active: StateFlow<ActiveSharing?> = _active.asStateFlow()

    /** True when this phone is sharing on [tripId] right now. */
    fun isSharing(tripId: Long): Boolean = _active.value?.tripId == tripId

    internal fun started(sharing: ActiveSharing) {
        _active.value = sharing
    }

    internal fun reported(atMillis: Long) {
        _active.update { it?.copy(lastReportedAtMillis = atMillis) }
    }

    internal fun stopped() {
        _active.value = null
    }
}
