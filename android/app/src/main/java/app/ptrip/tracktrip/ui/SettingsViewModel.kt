package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.AppSettings
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.location.ActiveSharing
import app.ptrip.tracktrip.location.SharingController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The languages the app offers.
 *
 * [SYSTEM] is not a cop-out third option: it is the state of a rider who has
 * never opened this setting, and without it a fresh install would have to pin
 * everyone to one language — putting an English UI in front of a Thai phone
 * and calling it a default.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    THAI("th"),
    ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag != null && tag?.startsWith(it.tag) == true } ?: SYSTEM
    }
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
    UNTIL_STOPPED(null);

    companion object {
        fun fromMinutes(minutes: Int?): SharingDuration =
            entries.firstOrNull { it.minutes == minutes } ?: ONE_HOUR
    }
}

data class SettingsUiState(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val defaultSharingDuration: SharingDuration = SharingDuration.ONE_HOUR,
    /** Trips that could be shared on — running ones the rider belongs to. */
    val activeTrips: List<Trip> = emptyList(),
    val loadingTrips: Boolean = true,
    /** The trip whose toggle is mid-flight, so only its row shows a spinner. */
    val pendingTripId: Long? = null,
    val error: String? = null,
)

/**
 * Everything on the settings screen: the two preferences, and the sharing
 * toggles.
 *
 * Both preferences are persisted the moment they change — a setting that
 * forgets itself when the app is closed is not a setting.
 */
class SettingsViewModel(
    private val settings: AppSettings,
    private val tripApi: TripApi,
    private val sharingController: SharingController,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            language = AppLanguage.fromTag(settings.languageTag),
            defaultSharingDuration = SharingDuration.fromMinutes(settings.defaultSharingMinutes),
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** What this phone is broadcasting right now, straight from the service. */
    val activeSharing: StateFlow<ActiveSharing?> = sharingController.active

    init {
        refreshTrips()
    }

    fun setLanguage(language: AppLanguage) {
        settings.languageTag = language.tag
        _uiState.update { it.copy(language = language) }
        // Applying it is the screen's job — it happens through
        // AppCompatDelegate, which recreates the activity below Android 13.
    }

    fun setDefaultSharingDuration(duration: SharingDuration) {
        settings.defaultSharingMinutes = duration.minutes
        _uiState.update { it.copy(defaultSharingDuration = duration) }
    }

    /**
     * Loads the trips that can be shared on.
     *
     * Which of them is *being* shared on comes from [activeSharing] — the
     * service's own state — not from the server. That is deliberate. The
     * server's `is_sharing` answers "would a report from this rider be
     * accepted", and for a rider who has never touched the controls the answer
     * is yes on every trip, because sharing is the default there. A toggle
     * built on that would show ON for every running trip while the phone sent
     * nothing at all.
     *
     * What a rider means by "am I sharing?" is whether their phone is
     * transmitting, and the service is the only thing that knows.
     */
    fun refreshTrips() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingTrips = true, error = null) }
            try {
                val active = tripApi.listTrips().filter { it.isActive }
                _uiState.update { it.copy(loadingTrips = false, activeTrips = active) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(loadingTrips = false, error = e.message) }
            }
        }
    }

    /**
     * Turns sharing on or off for one trip.
     *
     * Starting uses whatever default duration the rider set, so the toggle
     * stays a toggle — the duration picker lives on the trip screen, where
     * someone about to ride is making a deliberate choice.
     */
    fun toggleSharing(trip: Trip, on: Boolean) {
        if (_uiState.value.pendingTripId != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingTripId = trip.id, error = null) }
            try {
                if (on) {
                    sharingController.start(trip, settings.defaultSharingMinutes)
                } else {
                    sharingController.stop(trip.id)
                }
                // No local bookkeeping of what is on: the service publishes
                // that, and the screen reads it from there.
                _uiState.update { it.copy(pendingTripId = null) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(pendingTripId = null, error = e.message) }
            }
        }
    }

    fun onPermissionDenied(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
