package app.ptrip.tracktrip.map

import android.content.Context
import android.content.pm.PackageManager
import org.osmdroid.config.Configuration

/**
 * osmdroid's one-time setup.
 *
 * ## The user agent is not a formality
 *
 * The tiles come from OpenStreetMap's public servers, which are donated
 * bandwidth run by a charity. Their tile usage policy requires a User-Agent
 * that identifies the application, and osmdroid's default is the string
 * `osmdroid` — shared with every other app that never changed it, and blocked
 * outright at the server for exactly that reason. An app sending it gets
 * HTTP 429s and eventually a ban that lands on the whole shared identity.
 *
 * So this sends the package name and version: unique to this app, traceable
 * to it, and no use to anyone else.
 *
 * The other half of the policy is not hammering the servers. This app is a
 * poor citizen by accident only if something loops — the map polls positions,
 * not tiles, and osmdroid caches what it has drawn.
 *
 * https://operations.osmfoundation.org/policies/tiles/
 */
object MapConfig {

    @Volatile
    private var configured = false

    fun ensureConfigured(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return

            val appContext = context.applicationContext
            Configuration.getInstance().apply {
                userAgentValue = userAgentFor(appContext)

                // Tiles live in the app's own cache directory. osmdroid's
                // default is external storage, which on older releases wanted
                // a runtime permission the app has no other use for — and a
                // map cache is exactly what a cache directory is for: the
                // system may clear it, and redrawing is a download, not a
                // loss.
                osmdroidBasePath = appContext.cacheDir
                osmdroidTileCache = appContext.cacheDir.resolve("osmdroid-tiles")
            }
            configured = true
        }
    }

    /** e.g. `app.ptrip.tracktrip/0.1.0`. */
    internal fun userAgentFor(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return "${context.packageName}/${version ?: "dev"}"
    }
}
