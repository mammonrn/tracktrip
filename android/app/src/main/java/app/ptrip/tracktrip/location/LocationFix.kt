package app.ptrip.tracktrip.location

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * One location fix, for whoever asks.
 *
 * Shared by the tracking service and by the map's "centre on me" button so
 * there is one answer to "which provider, and for how long do we wait" rather
 * than two that drift apart.
 *
 * Uses the platform's own `LocationManager` rather than Play Services' fused
 * provider: at the cadence this app works to, the extra accuracy is not worth
 * another dependency, and this keeps working on a phone whose Play Services
 * are unhealthy — which is exactly the phone that ends up on a mountain road.
 */
object LocationFix {

    /** Long enough for a cold GPS fix outdoors. For the background service. */
    const val DEFAULT_TIMEOUT_MS = 60 * 1000L

    /**
     * For a button press. A rider who tapped something is watching the screen,
     * and a minute of nothing happening reads as broken — better to give up
     * and say so than to leave them wondering.
     */
    const val QUICK_TIMEOUT_MS = 15 * 1000L

    fun hasPermission(context: Context): Boolean =
        SharingController.LOCATION_PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * A current fix, or null — no permission, no provider, or nothing arrived
     * before [timeoutMs].
     *
     * Null is an ordinary outcome, not a failure to report: a phone in a
     * tunnel has no position, and the caller's job is to carry on without one.
     */
    suspend fun current(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Location? {
        if (!hasPermission(context)) return null

        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return null

        val provider = when {
            LocationManagerCompat.hasProvider(manager, LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            LocationManagerCompat.hasProvider(manager, LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return null
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationSignal()
                try {
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        provider,
                        cancellation,
                        Executor { runnable -> runnable.run() },
                    ) { location: Location? -> continuation.resume(location) }
                } catch (e: SecurityException) {
                    // Permission revoked between the check above and here.
                    continuation.resume(null)
                }
                continuation.invokeOnCancellation { cancellation.cancel() }
            }
        }
    }

    /**
     * The last fix any app on the phone obtained, without waiting for a new one.
     *
     * Instant and often good enough to point a map at; the caller can ask for
     * a real fix afterwards. Null when there is nothing cached, which is the
     * ordinary state on a freshly booted phone.
     */
    fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return null

        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { LocationManagerCompat.hasProvider(manager, it) }
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }
}
