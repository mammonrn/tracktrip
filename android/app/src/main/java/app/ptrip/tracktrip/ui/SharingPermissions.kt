package app.ptrip.tracktrip.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.AppContainer
import app.ptrip.tracktrip.location.BatteryExemption
import app.ptrip.tracktrip.location.SharingController

/**
 * Asking for what location sharing needs, in one place.
 *
 * Both screens that can start sharing go through this, so a rider gets the
 * same prompt and the same explanation wherever they tapped. Returns a
 * function to call when they ask to start: it grants immediately if the
 * permissions are already held, and otherwise asks.
 *
 * Location and notifications are requested together. They arrive as one system
 * dialog sequence rather than two interruptions minutes apart, and they are
 * needed for the same thing — the notification is not decoration, it is how a
 * rider knows they are being tracked and how they stop it.
 *
 * A refusal is answered with a sentence, never with a button that silently
 * does nothing: [onDenied] carries a message the caller puts on screen.
 *
 * [askBatteryExemption] adds one more question after the runtime permissions
 * are settled — Android's own "let this app run in the background" dialog. It
 * is off by default because this helper is also what the map's "centre on me"
 * button uses, and a rider who tapped that has not asked for anything that
 * runs in the background. See [BatteryExemption] for why a foreground service
 * is not enough on its own.
 */
@Composable
fun rememberSharingPermissionRequest(
    onGranted: () -> Unit,
    onDenied: (String) -> Unit,
    askBatteryExemption: Boolean = false,
): () -> Unit {
    val context = LocalContext.current
    val deniedMessage = stringResource(R.string.location_permission_denied)
    val settings = remember(context) { AppContainer.from(context).settings }
    val battery = rememberBatteryExemption()

    // Asked *after* sharing has started, not before: the rider has just said
    // yes to a thing, and this is the follow-up that makes it work for the
    // whole ride rather than a second gate in front of it.
    val proceed = {
        onGranted()
        if (askBatteryExemption &&
            BatteryExemption.shouldAsk(battery.isExempt, settings.batteryExemptionAsked)
        ) {
            settings.batteryExemptionAsked = true
            battery.ask()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Notifications being refused is not a reason to refuse to share —
        // location is the one that decides.
        val allowed = SharingController.LOCATION_PERMISSIONS.any { results[it] == true }
        if (allowed) proceed() else onDenied(deniedMessage)
    }

    // Not wrapped in `remember`: the lambda closes over the caller's
    // callbacks and over this app's own record of having asked about the
    // battery, and a cached copy of it would go on answering with whichever
    // of those it captured first.
    return {
        val locationHeld = SharingController.LOCATION_PERMISSIONS.any { granted(context, it) }
        val missing = (SharingController.LOCATION_PERMISSIONS +
            SharingController.NOTIFICATION_PERMISSIONS)
            .filterNot { granted(context, it) }

        when {
            missing.isEmpty() -> proceed()
            // Location already held and only the notification is missing:
            // ask for it, but don't make sharing wait on the answer.
            locationHeld -> {
                launcher.launch(missing.toTypedArray())
                proceed()
            }
            else -> launcher.launch(missing.toTypedArray())
        }
    }
}

private fun granted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
