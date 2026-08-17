package app.ptrip.tracktrip

import android.app.Application
import app.ptrip.tracktrip.data.AppContainer
import app.ptrip.tracktrip.map.MapConfig
import app.ptrip.tracktrip.ui.AppLocale

/**
 * Exists for one job: put the rider's chosen language in force before any
 * activity is created.
 *
 * Applying it from an activity works, but not without a visible cost — below
 * Android 13 `AppCompatDelegate` reacts by recreating the activity, so a rider
 * who chose Thai would watch the app start in English and restart itself on
 * every launch. Here there is nothing on screen yet to restart.
 */
class TracktripApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLocale.apply(AppContainer.from(this).settings.languageTag)

        // Before any map is built: osmdroid reads this configuration when a
        // MapView is constructed, and a tile fetched under the default user
        // agent is a tile fetched against OpenStreetMap's usage policy.
        MapConfig.ensureConfigured(this)
    }
}
