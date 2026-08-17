package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The languages the app offers. Display strings live in `strings.xml`. */
enum class AppLanguage {
    THAI,
    ENGLISH,
}

/**
 * How long a rider's location sharing runs by default when they start it.
 *
 * [UNTIL_STOPPED] is deliberately not "forever": it maps to the backend's
 * open-ended sharing session, which runs until the rider ends it or the trip
 * does. That is why [minutes] is nullable rather than, say, `Int.MAX_VALUE`.
 */
enum class SharingDuration(val minutes: Int?) {
    THIRTY_MINUTES(30),
    ONE_HOUR(60),
    FOUR_HOURS(240),
    UNTIL_STOPPED(null),
}

data class SettingsUiState(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val defaultSharingDuration: SharingDuration = SharingDuration.ONE_HOUR,
)

/**
 * Holds what the settings screen has been set to.
 *
 * Both settings are chosen here and read nowhere else yet:
 *
 * - TODO: language only moves the selection. The strings are English-only, so
 *   switching does nothing visible until the translation work lands and this
 *   drives `AppCompatDelegate.setApplicationLocales` (or an equivalent).
 * - TODO: the sharing default isn't persisted or sent anywhere. It wants
 *   either a local store (DataStore) or a field on the user record before it
 *   can outlive the process — which is also when the sharing screen should
 *   start reading it.
 *
 * Scoped to the activity like the other view models, so the choices survive
 * navigating away from settings and back within a session.
 */
class SettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setLanguage(language: AppLanguage) {
        _uiState.update { it.copy(language = language) }
    }

    fun setDefaultSharingDuration(duration: SharingDuration) {
        _uiState.update { it.copy(defaultSharingDuration = duration) }
    }
}
