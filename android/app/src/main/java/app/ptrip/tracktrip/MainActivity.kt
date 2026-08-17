package app.ptrip.tracktrip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import app.ptrip.tracktrip.data.AppContainer
import app.ptrip.tracktrip.ui.BackStack
import app.ptrip.tracktrip.ui.CreateTripScreen
import app.ptrip.tracktrip.ui.CreateTripViewModel
import app.ptrip.tracktrip.ui.Screen
import app.ptrip.tracktrip.ui.SignInScreen
import app.ptrip.tracktrip.ui.SignInUiState
import app.ptrip.tracktrip.ui.SignInViewModel
import app.ptrip.tracktrip.ui.TripDetailScreen
import app.ptrip.tracktrip.ui.TripDetailViewModel
import app.ptrip.tracktrip.ui.TripListScreen
import app.ptrip.tracktrip.ui.TripsViewModel
import app.ptrip.tracktrip.ui.rememberBackStack
import app.ptrip.tracktrip.ui.theme.TracktripTheme
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

    private val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TracktripTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    TracktripApp(
                        container = container,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun TracktripApp(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val signInViewModel: SignInViewModel = viewModel(factory = signInViewModelFactory(container))
    val state by signInViewModel.uiState.collectAsStateWithLifecycle()

    val credentialManager = remember { CredentialManager.create(context) }
    val webClientId = stringResource(R.string.google_web_client_id)

    val notConfiguredMessage = stringResource(R.string.error_client_id_not_configured)
    val noCredentialMessage = stringResource(R.string.error_no_credential)
    val cancelledOrAbortedMessage = stringResource(R.string.error_cancelled_or_aborted)
    val failedMessage = stringResource(R.string.error_sign_in_failed)
    val unexpectedCredentialMessage = stringResource(R.string.error_unexpected_credential)

    val backStack = rememberBackStack()

    when (val current = state) {
        is SignInUiState.SignedIn -> SignedInNavigation(
            container = container,
            backStack = backStack,
            displayName = current.displayName,
            onSignOut = {
                backStack.resetToRoot()
                signInViewModel.signOut()
            },
            modifier = modifier,
        )

        else -> SignInScreen(
            state = current,
            modifier = modifier,
            onSignInClick = {
                if (!webClientId.looksLikeGoogleClientId()) {
                    signInViewModel.onSignInFailed(notConfiguredMessage)
                } else {
                    signInViewModel.onSignInStarted()
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
                                signInViewModel.exchangeGoogleIdToken(result.idToken)

                            is GoogleSignInResult.Cancelled ->
                                signInViewModel.onSignInCancelled()

                            is GoogleSignInResult.Failure -> signInViewModel.onSignInFailed(
                                when (result.reason) {
                                    GoogleSignInFailure.NO_CREDENTIAL -> noCredentialMessage
                                    GoogleSignInFailure.CANCELLED_OR_ABORTED ->
                                        cancelledOrAbortedMessage
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

@Composable
private fun SignedInNavigation(
    container: AppContainer,
    backStack: BackStack,
    displayName: String?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One view model for the trip list, kept across navigation so coming back
    // from a trip doesn't blank the list while it reloads.
    val tripsViewModel: TripsViewModel =
        viewModel(factory = tripsViewModelFactory(container, onSignOut))
    val tripsState by tripsViewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = backStack.canGoBack) { backStack.pop() }

    when (val screen = backStack.current) {
        Screen.Trips -> TripListScreen(
            state = tripsState,
            displayName = displayName,
            onOpenTrip = { backStack.push(Screen.TripDetail(it.id)) },
            onCreateTrip = { backStack.push(Screen.CreateTrip) },
            onAcceptInvite = tripsViewModel::acceptInvite,
            onRefresh = tripsViewModel::refresh,
            onSignOut = onSignOut,
            modifier = modifier,
        )

        Screen.CreateTrip -> {
            val createViewModel: CreateTripViewModel =
                viewModel(factory = createTripViewModelFactory(container, onSignOut))
            val createState by createViewModel.uiState.collectAsStateWithLifecycle()

            CreateTripScreen(
                creating = createState.creating,
                error = createState.error,
                onCreate = { name ->
                    createViewModel.create(name) { trip ->
                        // Straight into the new trip: the next thing anyone
                        // does after creating one is invite people to it.
                        tripsViewModel.refresh()
                        backStack.pop()
                        backStack.push(Screen.TripDetail(trip.id))
                    }
                },
                onBack = { backStack.pop() },
                modifier = modifier,
            )
        }

        is Screen.TripDetail -> {
            val detailViewModel: TripDetailViewModel = viewModel(
                key = "trip-${screen.tripId}",
                factory = tripDetailViewModelFactory(container, screen.tripId, onSignOut),
            )
            val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()

            TripDetailScreen(
                state = detailState,
                onInviteEmailChange = detailViewModel::onInviteEmailChange,
                onSendInvite = detailViewModel::sendInvite,
                onEndTrip = {
                    detailViewModel.endTrip()
                    // The list shows each trip's status, so it is stale the
                    // moment this one ends.
                    tripsViewModel.refresh()
                },
                onBack = { backStack.pop() },
                modifier = modifier,
            )
        }
    }
}

private inline fun <reified VM : ViewModel> factoryOf(
    crossinline build: () -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
}

private fun signInViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    factoryOf { SignInViewModel(authApi = container.authApi, tokenStore = container.tokenStore) }

private fun tripsViewModelFactory(
    container: AppContainer,
    onSessionExpired: () -> Unit,
): ViewModelProvider.Factory =
    factoryOf { TripsViewModel(container.tripApi, onSessionExpired) }

private fun createTripViewModelFactory(
    container: AppContainer,
    onSessionExpired: () -> Unit,
): ViewModelProvider.Factory =
    factoryOf { CreateTripViewModel(container.tripApi, onSessionExpired) }

private fun tripDetailViewModelFactory(
    container: AppContainer,
    tripId: Long,
    onSessionExpired: () -> Unit,
): ViewModelProvider.Factory =
    factoryOf { TripDetailViewModel(tripId, container.tripApi, onSessionExpired) }
