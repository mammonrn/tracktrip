package app.ptrip.tracktrip.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The settings screen is the only place these choices are made, and nothing
 * persists them yet — so what is worth pinning down is that a choice sticks,
 * that the two settings don't disturb each other, and that "until I stop it"
 * stays distinguishable from a duration rather than becoming a large number.
 */
class SettingsViewModelTest {

    @Test
    fun `starts on the documented defaults`() {
        val state = SettingsViewModel().uiState.value

        assertEquals(AppLanguage.ENGLISH, state.language)
        assertEquals(SharingDuration.ONE_HOUR, state.defaultSharingDuration)
    }

    @Test
    fun `changing the language leaves the sharing default alone`() {
        val viewModel = SettingsViewModel()

        viewModel.setDefaultSharingDuration(SharingDuration.FOUR_HOURS)
        viewModel.setLanguage(AppLanguage.THAI)

        val state = viewModel.uiState.value
        assertEquals(AppLanguage.THAI, state.language)
        assertEquals(SharingDuration.FOUR_HOURS, state.defaultSharingDuration)
    }

    @Test
    fun `the last choice wins`() {
        val viewModel = SettingsViewModel()

        viewModel.setDefaultSharingDuration(SharingDuration.THIRTY_MINUTES)
        viewModel.setDefaultSharingDuration(SharingDuration.UNTIL_STOPPED)

        assertEquals(SharingDuration.UNTIL_STOPPED, viewModel.uiState.value.defaultSharingDuration)
    }

    @Test
    fun `open-ended sharing has no minute count`() {
        assertNull(SharingDuration.UNTIL_STOPPED.minutes)
        assertEquals(
            listOf(30, 60, 240, null),
            SharingDuration.entries.map { it.minutes },
        )
    }
}
