package app.ptrip.tracktrip.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * The rider's standing with Android's battery optimiser, and the way to
 * change it.
 *
 * [isExempt] is read fresh every time this screen resumes, because the answer
 * is a system setting: a rider can grant it from Android's own settings, or
 * revoke it there, without this app being involved at all.
 */
data class BatteryExemptionUi(
    val isExempt: Boolean,
    val ask: () -> Unit,
)

/**
 * Reads whether this app is exempt from battery optimisation, and hands back
 * a way to ask for it.
 *
 * See [app.ptrip.tracktrip.location.BatteryExemption] for what the exemption
 * buys and why a foreground service alone does not buy it.
 */
@SuppressLint("BatteryLife")
@Composable
fun rememberBatteryExemption(): BatteryExemptionUi {
    val context = LocalContext.current

    // Bumped whenever the answer might have changed: on resume, and after the
    // system dialog returns. `remember(reads)` then re-reads PowerManager
    // rather than showing a stale row.
    var reads by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { reads += 1 }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reads += 1 }

    val isExempt = remember(reads) { isIgnoringBatteryOptimizations(context) }

    return BatteryExemptionUi(isExempt = isExempt) {
        // The direct dialog is the one worth showing: it is a single yes/no
        // rather than a list of every app on the phone to find this one in.
        // It can be absent — some builds strip it, and a managed device can
        // disable it — so a refusal to open is answered with the list instead
        // of with nothing happening.
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )
        try {
            launcher.launch(direct)
        } catch (missingDialog: ActivityNotFoundException) {
            try {
                launcher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (missingList: ActivityNotFoundException) {
                // Nothing else to try: leave the rider where they are rather
                // than crashing them out of a ride.
            }
        }
    }
}

/**
 * Whether Android currently exempts this app.
 *
 * `PowerManager` is present on every API level this app supports, so there is
 * no version branch; a phone that somehow has no power service is treated as
 * "not exempt", which asks a harmless question rather than skipping it.
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val power = context.getSystemService<PowerManager>() ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}
