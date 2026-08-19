package app.ptrip.tracktrip.location

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import android.location.LocationListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
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

/**
 * A live feed of this phone's own fixes, for as long as it is collected.
 *
 * ## Why this exists, and why `lastKnown` could not do the job
 *
 * `LocationManager.getLastKnownLocation` is a **cache**, not a feed: it holds
 * whatever fix some app on the phone last asked for. Nothing on this phone
 * asks continuously — the sharing service takes a single fix every ten
 * minutes and goes back to sleep — so that cache is refreshed six times an
 * hour and is stale in between. Reading it on a timer produced a speedometer
 * that showed a real speed for a moment after each report and a dash for the
 * eight minutes that followed.
 *
 * This asks the provider for updates directly, so a screen that wants a live
 * reading gets one. It is deliberately **not** used by the reporting service:
 * that one still takes its one-shot fix every ten minutes, because it runs
 * with the phone in a pocket and the battery budget is the whole point. This
 * runs only while a screen is collecting it — the rider is looking at the
 * map — and stops the moment they leave.
 */
fun LocationFix.updates(
    context: Context,
    minIntervalMs: Long = LIVE_INTERVAL_MS,
    minDistanceMetres: Float = 0f,
): Flow<Location> {
    if (!hasPermission(context)) return emptyFlow()
    val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
        ?: return emptyFlow()

    // GPS first: it is the only provider that reports a speed worth showing.
    // Network fixes come with no speed at all on most devices, so they would
    // feed the readout nothing but nulls.
    val provider = when {
        LocationManagerCompat.hasProvider(manager, LocationManager.GPS_PROVIDER) ->
            LocationManager.GPS_PROVIDER
        LocationManagerCompat.hasProvider(manager, LocationManager.NETWORK_PROVIDER) ->
            LocationManager.NETWORK_PROVIDER
        else -> return emptyFlow()
    }

    return callbackFlow {
        val listener = LocationListener { location -> trySend(location) }
        try {
            manager.requestLocationUpdates(provider, minIntervalMs, minDistanceMetres, listener)
        } catch (e: SecurityException) {
            // Permission revoked between the check above and here.
            close()
            return@callbackFlow
        }
        awaitClose { manager.removeUpdates(listener) }
    }
}

/**
 * How often the live feed may deliver. A speedometer that lags by more than a
 * second or two reads as broken on a bike; the provider is free to give less.
 */
const val LIVE_INTERVAL_MS = 2_000L
