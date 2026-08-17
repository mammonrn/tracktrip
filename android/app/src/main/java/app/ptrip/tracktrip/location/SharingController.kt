package app.ptrip.tracktrip.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import kotlinx.coroutines.flow.StateFlow

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
 */
class SharingController(
    private val context: Context,
    private val tripApi: TripApi,
) {

    val active: StateFlow<ActiveSharing?> = SharingState.active

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
