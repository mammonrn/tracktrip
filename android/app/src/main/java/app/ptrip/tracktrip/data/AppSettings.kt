package app.ptrip.tracktrip.data

import android.content.Context

/**
 * The rider's own preferences, kept on the device.
 *
 * Plain `SharedPreferences`, not DataStore. Two small scalars read once at
 * start-up and written when a rider taps a row do not need a coroutine-based
 * store, a new dependency and a migration; the app already reaches for
 * `SharedPreferences` (encrypted) for its tokens, so this is the shape the
 * codebase already has. Nothing here is secret — the language you read in and
 * how long you like to share for are not worth encrypting.
 */
class AppSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The chosen UI language as a BCP-47 tag, or null for "follow the system".
     *
     * Null is a real choice, not a missing value: a rider who never touched
     * the setting should follow their phone, and pinning them to whatever the
     * app happened to launch in would be worse.
     */
    var languageTag: String?
        get() = prefs.getString(KEY_LANGUAGE, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, value)
        }.apply()

    /**
     * How long location sharing runs by default, in minutes, or null for "until
     * I stop it".
     *
     * Stored as a sentinel rather than as a nullable Int, because
     * `SharedPreferences` cannot tell "absent" from "null" and those mean
     * different things here: absent falls back to the default, null means the
     * rider deliberately chose open-ended.
     */
    var defaultSharingMinutes: Int?
        get() = when (val stored = prefs.getInt(KEY_SHARING_MINUTES, ABSENT)) {
            ABSENT -> DEFAULT_SHARING_MINUTES
            UNTIL_STOPPED -> null
            else -> stored
        }
        set(value) = prefs.edit().putInt(KEY_SHARING_MINUTES, value ?: UNTIL_STOPPED).apply()

    /**
     * Whether the rider has already been shown Android's battery-exemption
     * dialog.
     *
     * Asked once, at the moment sharing first starts, and never again from
     * there: the system dialog is modal and a rider who said no meant it. The
     * row in settings stays available for anyone who changes their mind, which
     * is why this records "we asked" rather than "they said yes" — the answer
     * itself is the system's to keep, and it can change outside the app.
     */
    var batteryExemptionAsked: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_ASKED, value).apply()

    private companion object {
        const val FILE_NAME = "tracktrip-settings"
        const val KEY_LANGUAGE = "language_tag"
        const val KEY_SHARING_MINUTES = "default_sharing_minutes"
        const val KEY_BATTERY_ASKED = "battery_exemption_asked"

        /** No value written yet. Not a duration anyone could choose. */
        const val ABSENT = -1

        /** The rider chose open-ended sharing. Also not a duration. */
        const val UNTIL_STOPPED = 0

        const val DEFAULT_SHARING_MINUTES = 60
    }
}
