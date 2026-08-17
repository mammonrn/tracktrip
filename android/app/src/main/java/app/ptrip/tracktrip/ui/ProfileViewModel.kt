package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.LevelProgress
import app.ptrip.tracktrip.data.MeApi
import app.ptrip.tracktrip.data.ProfilePatch
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val loading: Boolean = true,
    val user: UserProfile? = null,
    /** Lifetime distance and the rank it earns. Null until it loads, or if it fails. */
    val level: LevelProgress? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val uploadingAvatar: Boolean = false,
)

/**
 * The signed-in rider's own account.
 *
 * Scoped to the activity and loaded once, because more than one screen needs
 * it: the settings screen shows the name and photo, the profile screen edits
 * them, and the trip screen needs the rider's own id to pick their row out of
 * the member list.
 *
 * It is also what fills the profile back in after a restart — the sign-in
 * response only exists in the session that signed in, whereas this survives
 * on the stored tokens.
 */
class ProfileViewModel(
    private val meApi: MeApi,
    private val onSessionExpired: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val user = meApi.profile()
                _uiState.update { it.copy(loading = false, user = user, error = null) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
                return@launch
            } catch (e: ApiException) {
                _uiState.update { it.copy(loading = false, error = e.message) }
                return@launch
            }

            loadLevel()
        }
    }

    /**
     * The rider's rank and lifetime distance.
     *
     * Loaded after the account and allowed to fail quietly: a badge that
     * didn't arrive is a profile screen without a badge, not an error banner
     * over a rider's own details. It is a separate request because the server
     * derives it rather than storing it — see GET /me/level.
     */
    private suspend fun loadLevel() {
        val level = try {
            meApi.levelProgress()
        } catch (e: SessionExpiredException) {
            onSessionExpired()
            return
        } catch (e: ApiException) {
            return
        }
        _uiState.update { it.copy(level = level) }
    }

    /** Sends only what changed. An empty patch is a no-op, not a request. */
    fun save(patch: ProfilePatch, onSaved: () -> Unit = {}) {
        if (_uiState.value.saving) return
        if (patch.isEmpty) {
            onSaved()
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            try {
                val user = meApi.updateProfile(patch)
                _uiState.update { it.copy(saving = false, user = user) }
                onSaved()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                // Includes the 409 for a username someone else holds, which
                // arrives already worded for the rider.
                _uiState.update { it.copy(saving = false, error = e.message) }
            }
        }
    }

    fun uploadAvatar(bytes: ByteArray, contentType: String) {
        if (_uiState.value.uploadingAvatar) return

        viewModelScope.launch {
            _uiState.update { it.copy(uploadingAvatar = true, error = null) }
            try {
                val user = meApi.uploadAvatar(bytes, contentType)
                _uiState.update { it.copy(uploadingAvatar = false, user = user) }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: ApiException) {
                _uiState.update { it.copy(uploadingAvatar = false, error = e.message) }
            }
        }
    }

    fun onImageUnreadable(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}
