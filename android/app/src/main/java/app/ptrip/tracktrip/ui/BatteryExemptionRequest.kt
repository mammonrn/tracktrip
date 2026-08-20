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
import app.ptrip.tracktrip.location.BatteryExemption
import app.ptrip.tracktrip.location.BatterySettingsTarget

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
 * the way to the setting.
 *
 * "A way to ask for it" is what this used to be, and it is why the row was
 * reported dead: the asking dialog is not a dialog for a rider who already has
 * the exemption. Where [BatteryExemptionUi.ask] goes now depends on where the
 * rider stands — see [BatteryExemption.destinations].
 *
 * See [app.ptrip.tracktrip.location.BatteryExemption] for what the exemption
 * buys and why a foreground service alone does not buy it.
 */
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
        // Where to go depends on where the rider already stands, and that is
        // the whole of the fix: the one-tap dialog is only a dialog while the
        // app is *not* exempt. Launched by a rider whose row already reads
        // "Unrestricted", the system activity behind it finds nothing to grant
        // and finishes without drawing a pixel — which is precisely how the
        // row was reported: pressed, and nothing happens.
        //
        // See [BatteryExemption.destinations] for the order and the reasons.
        // Working down it stops at the first that opens; a phone with none of
        // them leaves the rider where they are rather than crashing them out
        // of a ride, and the row's own hint underneath still says what the
        // setting is for.
        BatteryExemption.destinations(isExempt).any { target ->
            try {
                launcher.launch(intentFor(target, context.packageName))
                true
            } catch (missing: ActivityNotFoundException) {
                false
            }
        }
    }
}

/**
 * The intent for one destination.
 *
 * Separate from the choosing, which is [BatteryExemption.destinations]: this
 * half needs Android and is three lines of `Intent`, and that half is the part
 * with a decision in it.
 */
@SuppressLint("BatteryLife")
private fun intentFor(target: BatterySettingsTarget, packageName: String): Intent =
    when (target) {
        BatterySettingsTarget.REQUEST_EXEMPTION -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", packageName, null),
        )

        BatterySettingsTarget.APP_DETAILS -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )

        BatterySettingsTarget.OPTIMISATION_LIST ->
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
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
