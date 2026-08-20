package app.ptrip.tracktrip.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.ptrip.tracktrip.data.AppSettings
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Starting and stopping location sharing, in the right order.
 *
 * Both the settings screen and the trip screen can do it, and getting the
 * order wrong is the kind of bug that leaves a phone broadcasting after the
 * rider thinks they stopped — so the sequence lives here once:
 *
 * - **Starting**: tell the server first, then start the service with the
 *   expiry the server decided. If the call fails there is nothing to stop, and
 *   the phone never began sending.
 * - **Stopping**: stop the service first, then tell the server. If the network
 *   call fails the phone has still stopped, which is the half that matters —
 *   the session then lapses on its own, and the write guard refuses anything
 *   that arrives meanwhile.
 *
 * It also holds the device-wide switch — see [DeviceSharing] — for the same
 * reason it holds the order: both screens ask, and there is exactly one right
 * answer per phone. The switch is a preference on disk; [enabled] is that
 * preference where a screen can watch it change.
 */
class SharingController(
    private val context: Context,
    private val tripApi: TripApi,
    private val settings: AppSettings,
) {

    val active: StateFlow<ActiveSharing?> = SharingState.active

    private val _enabled = MutableStateFlow(settings.shareLocationFromThisPhone)

    /**
     * Whether this phone may share its location at all.
     *
     * Read from disk once, here, and published as a flow because two screens
     * draw it and either can change it. The stored preference stays the
     * authority across launches; this is the same value with a way to observe
     * it.
     */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Whether this phone can read location at all. Checked before starting. */
    fun hasLocationPermission(): Boolean =
        LOCATION_PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Starts sharing on [trip] for [durationMinutes] (null for "until I stop
     * it"), and returns the session the server created.
     *
     * Throws whatever the API throws — the caller is a view model with an
     * error line to put it on.
     */
    suspend fun start(trip: Trip, durationMinutes: Int?) {
        // Starting is consenting. A rider who picks a duration on the trip
        // screen has answered the device question too, and leaving the switch
        // off behind their back would put Settings and the notification in
        // their pocket in flat contradiction.
        setEnabled(true)
        val session = tripApi.startSharing(trip.id, durationMinutes)
        LocationSharingService.start(
            context = context,
            tripId = trip.id,
            tripName = trip.name,
            expiresAtIso = session.expiresAt,
        )
    }

    /**
     * Stops sharing on [tripId].
     *
     * The service goes down first and unconditionally; the server call is
     * allowed to fail without leaving the phone transmitting.
     */
    suspend fun stop(tripId: Long) {
        LocationSharingService.stop(context)
        tripApi.stopSharing(tripId)
    }

    /**
     * Stops the service without telling the server — for when the server
     * already knows, which is what ending a trip does to everyone's session.
     */
    fun stopLocally() {
        LocationSharingService.stop(context)
    }

    /**
     * Records whether this phone may share at all.
     *
     * Written through to disk immediately: a privacy switch that forgets
     * itself on the next launch is worse than no switch. What it does to a
     * session already running is [DeviceSharing.onSwitched]'s decision and the
     * caller's to carry out — this is the remembering, not the acting.
     */
    fun setEnabled(on: Boolean) {
        if (settings.shareLocationFromThisPhone != on) {
            settings.shareLocationFromThisPhone = on
        }
        _enabled.value = on
    }

    /**
     * The device switch going off, in the order that makes it a kill switch.
     *
     * The service goes down first and unconditionally, then the server is told
     * if there is a session to tell it about. Nothing here reads the trip
     * list: the id comes from the service, so a session that outlived its trip
     * — ended, deleted, left — stops all the same. That case is the whole
     * reason the switch exists, and it is precisely the one a trip-shaped
     * control could not reach.
     */
    suspend fun disable() {
        setEnabled(false)
        val tripId = SharingState.active.value?.tripId
        stopLocally()
        if (tripId != null) {
            tripApi.stopSharing(tripId)
        }
    }

    companion object {
        /**
         * Fine first: a trip map that places riders a city block out is not
         * worth showing. Coarse is accepted because Android lets a rider grant
         * only that, and an approximate position on the map beats none.
         */
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /**
         * The notification permission, on the versions that have one.
         *
         * Empty below Android 13, where notifications need no grant. Worth
         * asking for: without it the foreground service still runs, but its
         * notification is silently hidden — and that notification is how a
         * rider knows they are being tracked and how they stop it.
         */
        val NOTIFICATION_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
    }
}
