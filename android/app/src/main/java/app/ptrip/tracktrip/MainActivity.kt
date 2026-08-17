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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ptrip.tracktrip.auth.GoogleSignInFailure
import app.ptrip.tracktrip.auth.GoogleSignInResult
import app.ptrip.tracktrip.auth.requestGoogleIdToken
import app.ptrip.tracktrip.data.AuthApi
import app.ptrip.tracktrip.data.TokenStore
import app.ptrip.tracktrip.ui.SignInScreen
import app.ptrip.tracktrip.ui.SignInUiState
import app.ptrip.tracktrip.ui.SignInViewModel
import app.ptrip.tracktrip.ui.TripListScreen
import kotlinx.coroutines.launch

private const val CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"

/**
 * Sanity-checks the configured web client ID before handing it to Google.
 *
 * A misconfigured value (blank, or edited into something that isn't a client
 * ID) otherwise surfaces as an opaque Google error that's hard to trace back
 * to config.xml, so it's worth catching up front.
 */
private fun String.looksLikeGoogleClientId(): Boolean =
    isNotBlank() && endsWith(CLIENT_ID_SUFFIX) && length > CLIENT_ID_SUFFIX.length + 10

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
    val noCredentialMessage = stringResource(R.string.error_no_credential)
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
                if (!webClientId.looksLikeGoogleClientId()) {
                    viewModel.onSignInFailed(notConfiguredMessage)
                } else {
                    viewModel.onSignInStarted()
                    scope.launch {
                        // The helper walks the filter=true -> filter=false ->
                        // SignInWithGoogle fallback chain; see
                        // auth/GoogleSignInHelper.kt.
                        val result = requestGoogleIdToken(
                            context = context,
                            webClientId = webClientId,
                            credentialManager = credentialManager,
                        )
                        when (result) {
                            is GoogleSignInResult.Success ->
                                viewModel.exchangeGoogleIdToken(result.idToken)

                            is GoogleSignInResult.Cancelled ->
                                viewModel.onSignInCancelled()

                            is GoogleSignInResult.Failure -> viewModel.onSignInFailed(
                                when (result.reason) {
                                    GoogleSignInFailure.NO_CREDENTIAL -> noCredentialMessage
                                    GoogleSignInFailure.UNEXPECTED_CREDENTIAL ->
                                        unexpectedCredentialMessage
                                    GoogleSignInFailure.OTHER -> failedMessage
                                }
                            )
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
