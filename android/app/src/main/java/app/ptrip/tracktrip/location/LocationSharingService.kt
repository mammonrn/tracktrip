package app.ptrip.tracktrip.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import app.ptrip.tracktrip.MainActivity
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.AppContainer
import app.ptrip.tracktrip.data.SessionExpiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Reports this rider's position to a trip while they are sharing.
 *
 * A foreground service, because that is the only way Android lets an app read
 * location while the rider's phone is in their pocket — which is the entire
 * situation this feature exists for. The notification is not a formality
 * either: it is the rider's standing reminder that their location is going
 * out, and it carries the control to stop it.
 *
 * The service is the authority on whether this phone is tracking. It writes
 * [SharingState]; the UI only reads it.
 */
class LocationSharingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharing()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val tripId = intent.getLongExtra(EXTRA_TRIP_ID, 0L)
                val tripName = intent.getStringExtra(EXTRA_TRIP_NAME).orEmpty()
                val expiresAt = intent.getLongExtra(EXTRA_EXPIRES_AT, NO_EXPIRY)
                    .takeIf { it != NO_EXPIRY }
                if (tripId <= 0L) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSharing(tripId, tripName, expiresAt)
            }

            else -> {
                // Restarted by the system with no intent (START_STICKY would
                // do this). There is nothing to resume from — the session
                // lives on the server and the rider can start again — so go
                // quietly rather than sit in the shade doing nothing.
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Not sticky: a killed tracking service should not silently come back
        // to life hours later and start broadcasting a rider's location
        // without them asking.
        return START_NOT_STICKY
    }

    private fun startSharing(tripId: Long, tripName: String, expiresAtMillis: Long?) {
        val sharing = ActiveSharing(tripId, tripName, expiresAtMillis)
        SharingState.started(sharing)

        startForegroundWithType(notificationFor(sharing))

        worker?.cancel()
        worker = scope.launch { reportLoop(tripId, expiresAtMillis) }
    }

    /**
     * The reporting loop: a fix roughly every ten minutes until the session
     * lapses, the trip ends, or the rider stops.
     */
    private suspend fun reportLoop(tripId: Long, expiresAtMillis: Long?) {
        val container = AppContainer.from(applicationContext)

        while (true) {
            if (hasExpired(System.currentTimeMillis(), expiresAtMillis)) {
                // The server would refuse the next report anyway; stopping
                // here means the notification goes away on time rather than
                // ten minutes late.
                break
            }

            val fix = currentLocation()
            if (fix != null) {
                val outcome = report(container, tripId, fix)
                if (outcome == ReportOutcome.STOP) {
                    break
                }
                if (outcome == ReportOutcome.OK) {
                    SharingState.reported(System.currentTimeMillis())
                }
            }

            val wait = nextDelayMillis(
                nowMillis = System.currentTimeMillis(),
                expiresAtMillis = expiresAtMillis,
                intervalMillis = REPORT_INTERVAL_MS,
            )
            if (wait <= 0L) {
                break
            }
            delay(wait)
        }

        stopSharing()
    }

    private enum class ReportOutcome { OK, RETRY, STOP }

    private suspend fun report(
        container: AppContainer,
        tripId: Long,
        fix: Location,
    ): ReportOutcome = try {
        container.tripApi.reportPosition(
            tripId = tripId,
            lat = fix.latitude,
            lng = fix.longitude,
            timestamp = Instant.ofEpochMilli(fix.time).toString(),
            accuracy = fix.accuracy.takeIf { fix.hasAccuracy() },
            batteryPct = batteryPercent(),
        )
        ReportOutcome.OK
    } catch (e: SessionExpiredException) {
        // Signed out from under us. Nothing this service can do about it, and
        // it must not keep trying.
        ReportOutcome.STOP
    } catch (e: ApiException) {
        // The server refuses a report once the trip has ended or the rider's
        // session is spent, and says which. Both mean stop; anything else —
        // no signal, a 500 — is worth another go in ten minutes.
        if (e.message?.contains("ended", ignoreCase = true) == true) {
            ReportOutcome.STOP
        } else {
            ReportOutcome.RETRY
        }
    }

    /**
     * One location fix, or null.
     *
     * Uses the platform's own `LocationManager` rather than Play Services'
     * fused provider: at a ten-minute cadence the extra accuracy is not worth
     * another dependency, and this keeps working on a phone whose Play
     * Services are unhealthy — which is exactly the phone that ends up on a
     * mountain road.
     */
    private suspend fun currentLocation(): Location? {
        if (!hasLocationPermission()) {
            return null
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val provider = when {
            LocationManagerCompat.hasProvider(manager, LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            LocationManagerCompat.hasProvider(manager, LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return null
        }

        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
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

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Battery level, for the member list. Null rather than a guess. */
    private fun batteryPercent(): Int? {
        val manager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationFor(sharing: ActiveSharing): Notification {
        createChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationSharingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sharing_notification)
            .setContentTitle(getString(R.string.sharing_notification_title))
            .setContentText(
                getString(
                    R.string.sharing_notification_text,
                    sharing.tripName.ifBlank { getString(R.string.untitled_trip) },
                )
            )
            .setContentIntent(open)
            .addAction(0, getString(R.string.sharing_notification_stop), stop)
            // Ongoing and unswipeable: this is the rider's guarantee that they
            // know when their location is going out.
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sharing_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.sharing_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopSharing() {
        worker?.cancel()
        worker = null
        SharingState.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        SharingState.stopped()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "location-sharing"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_START = "app.ptrip.tracktrip.SHARE_START"
        const val ACTION_STOP = "app.ptrip.tracktrip.SHARE_STOP"

        private const val EXTRA_TRIP_ID = "trip_id"
        private const val EXTRA_TRIP_NAME = "trip_name"
        private const val EXTRA_EXPIRES_AT = "expires_at"
        private const val NO_EXPIRY = -1L

        /** The cadence the backend's rate limit was sized for. */
        internal const val REPORT_INTERVAL_MS = 10 * 60 * 1000L

        /** How long to wait for a fix before giving up on this round. */
        private const val FIX_TIMEOUT_MS = 60 * 1000L

        /**
         * Starts sharing on a trip. [expiresAtIso] is the server's own expiry
         * for the session, so the phone and the server stop at the same moment
         * rather than at two clocks' idea of "in an hour".
         */
        fun start(context: Context, tripId: Long, tripName: String, expiresAtIso: String?) {
            val intent = Intent(context, LocationSharingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_TRIP_NAME, tripName)
                .putExtra(EXTRA_EXPIRES_AT, parseExpiryMillis(expiresAtIso) ?: NO_EXPIRY)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationSharingService::class.java)
                .setAction(ACTION_STOP)
            // Not startForegroundService: the service is either already running
            // (and will handle this) or gone (and there is nothing to stop).
            runCatching { context.startService(intent) }
        }
    }
}
