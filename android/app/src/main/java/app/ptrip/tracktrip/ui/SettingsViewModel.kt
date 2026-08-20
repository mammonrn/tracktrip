package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.AppSettings
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.location.ActiveSharing
import app.ptrip.tracktrip.location.DeviceSharing
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
    /**
     * Whether this phone may share its location at all.
     *
     * A device switch, not a trip's. It is here on the same state object as
     * the language because that is what it is — a preference of this phone's
     * — and unlike [activeTrips] it is answerable with the network down and
     * with every trip a rider has ever been on finished.
     */
    val shareFromThisPhone: Boolean = true,
    /** True while the switch's own start or stop is in flight. */
    val switchPending: Boolean = false,
    /**
     * The trip the switch would share on — running, and this rider's.
     *
     * A list rather than a single trip because that is the shape `GET /trips`
     * comes back in, and nothing in the schema or the API stops a rider being
     * on two at once: `trips` has no such constraint, `POST /trips` no such
     * guard, and `sharing_sessions` is keyed per trip *and* rider. In practice
     * a rider is on one ride at a time, so [DeviceSharing.onSwitched] takes
     * the first — `GET /trips` orders newest first — rather than asking.
     */
    val activeTrips: List<Trip> = emptyList(),
    val loadingTrips: Boolean = true,
    val error: String? = null,
)

/**
 * Everything on the settings screen: the two preferences, and the sharing
 * switch.
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
            shareFromThisPhone = sharingController.enabled.value,
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** What this phone is broadcasting right now, straight from the service. */
    val activeSharing: StateFlow<ActiveSharing?> = sharingController.active

    init {
        refreshTrips()

        // The switch can be moved from the trip screen too — starting there is
        // consenting — so this screen follows the controller rather than only
        // its own writes.
        viewModelScope.launch {
            sharingController.enabled.collect { on ->
                _uiState.update { it.copy(shareFromThisPhone = on) }
            }
        }
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
     * Loads the trip the switch would share on.
     *
     * Whether one is *being* shared on comes from [activeSharing] — the
     * service's own state — not from the server. That is deliberate. The
     * server's `is_sharing` answers "would a report from this rider be
     * accepted", and for a rider who has never touched the controls the answer
     * is yes on every trip, because sharing is the default there. A toggle
     * built on that would read ON while the phone sent nothing at all.
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
     * Moves the device-wide switch, and does what that means to whatever is
     * live.
     *
     * Always available. Nothing here consults the trip list to decide *whether*
     * the rider may press it — that was the bug: an ended trip left the screen
     * with no control at all, and a rider trying to turn sharing on found the
     * app answering a question about their weekend instead. The list is read
     * only to answer the second question, which trip an "on" should start on,
     * and [DeviceSharing.onSwitched] decides that.
     *
     * The stored preference is written first and unconditionally, so a phone
     * with no signal still stops sending and still remembers why.
     */
    fun setShareFromThisPhone(on: Boolean) {
        if (_uiState.value.switchPending) return

        val effect = DeviceSharing.onSwitched(
            on = on,
            sharingTripId = sharingController.active.value?.tripId,
            running = _uiState.value.activeTrips,
        )

        viewModelScope.launch {
            _uiState.update { it.copy(switchPending = true, error = null) }
            try {
                when (effect) {
                    is DeviceSharing.Effect.Remember -> sharingController.setEnabled(on)
                    is DeviceSharing.Effect.Stop -> sharingController.disable()
                    is DeviceSharing.Effect.Start ->
                        sharingController.start(effect.trip, settings.defaultSharingMinutes)
                }
                _uiState.update { it.copy(switchPending = false) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                // The phone's own answer survives a failed round trip: turning
                // off has already stopped the service by this point, and
                // turning on has already been remembered.
                sharingController.setEnabled(on)
                _uiState.update { it.copy(switchPending = false, error = e.message) }
            }
        }
    }

    /**
     * Whether moving the switch to [on] would actually begin transmitting.
     *
     * The screen asks before it asks Android: turning the switch on with no
     * trip running only records a preference, and putting the location
     * permission dialog in front of that would be demanding an answer to a
     * question nothing is about to act on. The same decision as the flip
     * itself, so it is the same function that makes it.
     */
    fun switchWouldStartSharing(on: Boolean): Boolean =
        DeviceSharing.onSwitched(
            on = on,
            sharingTripId = sharingController.active.value?.tripId,
            running = _uiState.value.activeTrips,
        ) is DeviceSharing.Effect.Start

    fun onPermissionDenied(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
