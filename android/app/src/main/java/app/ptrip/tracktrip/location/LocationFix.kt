package app.ptrip.tracktrip.location

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.CancellationSignal
import androidx.core.location.LocationListenerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
 * asks continuously — the sharing service takes a single fix per reporting
 * cycle and goes back to sleep — so that cache is only as fresh as the last
 * cycle, and only while a rider is sharing at all. Reading it on a timer
 * produced a speedometer that went blank between reports.
 *
 * This asks the provider for updates directly, so a screen that wants a live
 * reading gets one. It is deliberately **not** used by the reporting service:
 * that one still takes a single one-shot fix per cycle, because it runs with
 * the phone in a pocket and the battery budget is the whole point. This runs
 * only while a screen is collecting it — the rider is looking at the map —
 * and stops the moment they leave.
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

    // An explicit request rather than the old
    // `requestLocationUpdates(provider, minTime, minDistance, listener)`.
    //
    // That call says only "no more often than this", and the platform is free
    // to read it as permission to duty-cycle the GNSS engine down to that
    // rate — sleeping between fixes and reporting a Doppler speed averaged
    // over whatever window it happened to be awake for. At 2,000 ms that is
    // what put the speedometer several km/h behind the bike and left it
    // reading low while the speed was changing.
    //
    // QUALITY_HIGH_ACCURACY is the part that matters: it is the app saying it
    // wants the engine tracking continuously, which is what a navigation app
    // asks for and what makes each fix's speed an instantaneous reading
    // rather than an average. The interval below is then the delivery rate,
    // not a licence to sleep.
    val request = LocationRequestCompat.Builder(minIntervalMs)
        .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        // No floor under the delivery rate: a faster fix is a fresher speed,
        // and the engine is already awake producing it.
        .setMinUpdateIntervalMillis(0L)
        .setMinUpdateDistanceMeters(minDistanceMetres)
        .build()

    return callbackFlow {
        val listener = LocationListenerCompat { location -> trySend(location) }
        try {
            LocationManagerCompat.requestLocationUpdates(
                manager,
                provider,
                request,
                Executor { runnable -> runnable.run() },
                listener,
            )
        } catch (e: SecurityException) {
            // Permission revoked between the check above and here.
            close()
            return@callbackFlow
        }
        awaitClose { manager.removeUpdates(listener) }
    }
}

/**
 * The same feed, but one that comes back.
 *
 * [updates] ends for reasons that are not the collector's doing and are not
 * permanent: the provider is switched off and on again, permission is revoked
 * and re-granted, or the subscription is refused outright and the flow
 * completes before delivering anything. A collector that simply awaited it
 * then sat there for ever holding whatever value it had last — which is what
 * turned the map's speedometer into a dash that never came back, on a phone
 * that was otherwise working.
 *
 * Nothing here reports a failure, deliberately. There is no action a rider on
 * a motorcycle can take about a location provider that has gone quiet for a
 * moment, and the reading itself already says so by not changing.
 */
fun LocationFix.updatesRetrying(
    context: Context,
    minIntervalMs: Long = LIVE_INTERVAL_MS,
    retryDelayMs: Long = LIVE_RETRY_MS,
): Flow<Location> = flow {
    while (true) {
        // Re-entered rather than cached: `updates` decides on permission and
        // on which provider exists *at the moment it is called*, so asking
        // again is what lets a re-granted permission take effect without the
        // screen being reopened.
        emitAll(updates(context, minIntervalMs))
        delay(retryDelayMs)
    }
}

/**
 * How long to wait before re-subscribing to a feed that ended.
 *
 * Long enough that a provider which refuses instantly cannot spin, short
 * enough that a rider who grants permission mid-ride sees their speed within
 * a few seconds.
 */
const val LIVE_RETRY_MS = 3_000L

/**
 * How often the live feed may deliver.
 *
 * One second, which is the rate a GNSS engine produces fixes at anyway — so
 * this asks for every fix there is and throws none of them away.
 *
 * It used to be two, and that was the largest single cause of the speedometer
 * reading low on a real ride: it made every reading up to two seconds old
 * before it was drawn, and — worse — it told the platform the app was happy
 * with a fix only every two seconds, which is permission to duty-cycle the
 * GNSS engine and report an averaged speed instead of an instantaneous one.
 * See the request built in [updates].
 *
 * The cost is the screen the rider is already looking at with the map drawing
 * on it. The background *reporting* cadence is untouched and separate: that
 * one runs with the phone in a pocket, and its budget is the whole point of
 * it being a single one-shot fix per cycle.
 */
const val LIVE_INTERVAL_MS = 1_000L
