package app.ptrip.tracktrip.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The settings the app persists.
 *
 * The view model itself now needs a `SharedPreferences` file, an API client
 * and a foreground-service controller, so it belongs in an instrumented test
 * rather than here. What is worth pinning down without a device is the mapping
 * in both directions — a stored value that reads back as something else is how
 * a setting silently resets itself.
 */
class SettingsViewModelTest {

    @Test
    fun `a stored language tag reads back as the option that wrote it`() {
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }

    @Test
    fun `no stored language means following the phone`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
    }

    @Test
    fun `a regional tag still matches its language`() {
        // AppCompat hands back what the platform resolved, which on a Thai
        // phone is "th-TH" rather than the bare "th" that was asked for.
        assertEquals(AppLanguage.THAI, AppLanguage.fromTag("th-TH"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-GB"))
    }

    @Test
    fun `a language the app does not offer falls back to the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("ja"))
    }

    @Test
    fun `a stored duration reads back as the option that wrote it`() {
        for (duration in SharingDuration.entries) {
            assertEquals(duration, SharingDuration.fromMinutes(duration.minutes))
        }
    }

    @Test
    fun `open-ended sharing has no minute count`() {
        assertNull(SharingDuration.UNTIL_STOPPED.minutes)
        assertEquals(
            listOf(30, 60, 240, null),
            SharingDuration.entries.map { it.minutes },
        )
    }

    @Test
    fun `a duration the app no longer offers falls back to an hour`() {
        // A value written by an older build, or a corrupted preference file.
        // Falling back beats crashing, and an hour is the middle of the range.
        assertEquals(SharingDuration.ONE_HOUR, SharingDuration.fromMinutes(17))
    }
}
