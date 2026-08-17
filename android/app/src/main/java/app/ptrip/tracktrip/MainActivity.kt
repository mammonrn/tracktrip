package app.ptrip.tracktrip

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ptrip.tracktrip.data.AuthApi
import app.ptrip.tracktrip.data.TokenStore
import app.ptrip.tracktrip.ui.SignInScreen
import app.ptrip.tracktrip.ui.SignInUiState
import app.ptrip.tracktrip.ui.SignInViewModel
import app.ptrip.tracktrip.ui.TripListScreen
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

/**
 * Marker left in res/values/config.xml while the real client ID hasn't been
 * filled in. Checked up front so the user gets a clear message instead of an
 * opaque Google error.
 */
private const val CLIENT_ID_PLACEHOLDER = "__FILL_IN__"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TracktripApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun TracktripApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val viewModel: SignInViewModel = viewModel(factory = signInViewModelFactory(context))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val credentialManager = remember { CredentialManager.create(context) }
    val webClientId = stringResource(R.string.google_web_client_id)

    val notConfiguredMessage = stringResource(R.string.error_client_id_not_configured)
    val noAccountMessage = stringResource(R.string.error_no_google_account)
    val failedMessage = stringResource(R.string.error_sign_in_failed)
    val unexpectedCredentialMessage = stringResource(R.string.error_unexpected_credential)

    when (val current = state) {
        is SignInUiState.SignedIn -> TripListScreen(
            displayName = current.displayName,
            onSignOut = viewModel::signOut,
            modifier = modifier,
        )

        else -> SignInScreen(
            state = current,
            modifier = modifier,
            onSignInClick = {
                if (webClientId.contains(CLIENT_ID_PLACEHOLDER)) {
                    viewModel.onSignInFailed(notConfiguredMessage)
                } else {
                    viewModel.onSignInStarted()
                    scope.launch {
                        // Credential Manager takes the WEB client ID as the
                        // server client ID — the Android client is matched
                        // separately by package name + signing SHA-1, and is
                        // never passed here.
                        val googleIdOption = GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(false)
                            .setServerClientId(webClientId)
                            .build()

                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(googleIdOption)
                            .build()

                        try {
                            val result = credentialManager.getCredential(
                                context = context,
                                request = request,
                            )
                            val credential = result.credential
                            if (
                                credential is CustomCredential &&
                                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                            ) {
                                val googleCredential =
                                    GoogleIdTokenCredential.createFrom(credential.data)
                                viewModel.exchangeGoogleIdToken(googleCredential.idToken)
                            } else {
                                viewModel.onSignInFailed(unexpectedCredentialMessage)
                            }
                        } catch (e: GetCredentialCancellationException) {
                            // User dismissed the sheet: not an error.
                            viewModel.onSignInCancelled()
                        } catch (e: NoCredentialException) {
                            viewModel.onSignInFailed(noAccountMessage)
                        } catch (e: GetCredentialException) {
                            viewModel.onSignInFailed(failedMessage)
                        } catch (e: Exception) {
                            viewModel.onSignInFailed(failedMessage)
                        }
                    }
                }
            },
        )
    }
}

private fun signInViewModelFactory(context: Context): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appContext = context.applicationContext
            val baseUrl = appContext.getString(R.string.api_base_url)
            return SignInViewModel(
                authApi = AuthApi(baseUrl = baseUrl),
                tokenStore = TokenStore(appContext),
            ) as T
        }
    }
