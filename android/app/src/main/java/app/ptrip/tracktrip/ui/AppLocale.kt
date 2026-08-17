package app.ptrip.tracktrip.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Switching the app's language.
 *
 * Done through [AppCompatDelegate.setApplicationLocales], which is Android's
 * own per-app language API: on Android 13+ it hands the choice to the system
 * (so it also appears under Settings › Apps › Tracktrip › Language), and below
 * that AppCompat stores it and recreates the activity itself. Either way the
 * app never restarts itself, and never reaches into `Configuration`.
 *
 * The choice is persisted separately in [app.ptrip.tracktrip.data.AppSettings]
 * rather than relying on AppCompat's `autoStoreLocales`: that needs a
 * content-provider-backed service declared in the manifest and writes its own
 * file, which is a lot of machinery to own one string the app already has a
 * place for.
 */
object AppLocale {

    /**
     * Applies [languageTag], or clears the override when it is null.
     *
     * A no-op when the requested locale is already in force — worth checking,
     * because applying one recreates the activity below Android 13, and doing
     * that unconditionally on every start would loop.
     */
    fun apply(languageTag: String?) {
        val requested = if (languageTag == null) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }

        if (AppCompatDelegate.getApplicationLocales() == requested) {
            return
        }
        AppCompatDelegate.setApplicationLocales(requested)
    }

    /** The tag currently in force, or null when following the system. */
    fun current(): String? =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotEmpty() }
}
