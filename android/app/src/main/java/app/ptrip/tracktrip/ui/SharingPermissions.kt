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
 */
@Composable
fun rememberSharingPermissionRequest(
    onGranted: () -> Unit,
    onDenied: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val deniedMessage = stringResource(R.string.location_permission_denied)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Notifications being refused is not a reason to refuse to share —
        // location is the one that decides.
        val allowed = SharingController.LOCATION_PERMISSIONS.any { results[it] == true }
        if (allowed) onGranted() else onDenied(deniedMessage)
    }

    return remember(launcher, onGranted, onDenied) {
        {
            val locationHeld = SharingController.LOCATION_PERMISSIONS.any { granted(context, it) }
            val missing = (SharingController.LOCATION_PERMISSIONS +
                SharingController.NOTIFICATION_PERMISSIONS)
                .filterNot { granted(context, it) }

            when {
                missing.isEmpty() -> onGranted()
                // Location already held and only the notification is missing:
                // ask for it, but don't make sharing wait on the answer.
                locationHeld -> {
                    launcher.launch(missing.toTypedArray())
                    onGranted()
                }
                else -> launcher.launch(missing.toTypedArray())
            }
        }
    }
}

private fun granted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
