package app.ptrip.tracktrip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ptrip.tracktrip.data.AuthApi
import app.ptrip.tracktrip.data.AuthApiException
import app.ptrip.tracktrip.data.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SignInUiState {
    data object SignedOut : SignInUiState
    data object Loading : SignInUiState
    data class Error(val message: String) : SignInUiState

    /**
     * Everything the app knows about who is signed in.
     *
     * All three are nullable, and all three are null on a restart that comes
     * straight in on stored tokens — the profile is only handed over by the
     * sign-in response.
     *
     * TODO: call GET /me on start-up so the settings screen shows a name and
     * photo after a restart too, instead of only in the session that signed in.
     */
    data class SignedIn(
        val displayName: String?,
        val email: String? = null,
        val photoUrl: String? = null,
    ) : SignInUiState
}

class SignInViewModel(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SignInUiState>(
        // Already have tokens from a previous run? Skip straight past sign-in.
        if (tokenStore.hasTokens()) SignInUiState.SignedIn(null) else SignInUiState.SignedOut
    )
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    fun onSignInStarted() {
        _uiState.value = SignInUiState.Loading
    }

    /** Called with the Google ID token obtained via Credential Manager. */
    fun exchangeGoogleIdToken(idToken: String) {
        _uiState.value = SignInUiState.Loading
        viewModelScope.launch {
            try {
                val session = authApi.signInWithGoogle(idToken)
                tokenStore.saveTokens(session.accessToken, session.refreshToken)
                _uiState.value = SignInUiState.SignedIn(
                    displayName = session.displayName,
                    email = session.email,
                    photoUrl = session.photoUrl,
                )
            } catch (e: AuthApiException) {
                _uiState.value = SignInUiState.Error(e.message ?: "Sign-in failed.")
            } catch (e: Exception) {
                // Anything unexpected still surfaces as a message rather than
                // taking the app down.
                _uiState.value = SignInUiState.Error("Something went wrong signing in.")
            }
        }
    }

    /** Credential Manager failed or the user dismissed the sheet. */
    fun onSignInFailed(message: String) {
        _uiState.value = SignInUiState.Error(message)
    }

    fun onSignInCancelled() {
        _uiState.value = SignInUiState.SignedOut
    }

    fun signOut() {
        tokenStore.clear()
        _uiState.value = SignInUiState.SignedOut
    }
}
