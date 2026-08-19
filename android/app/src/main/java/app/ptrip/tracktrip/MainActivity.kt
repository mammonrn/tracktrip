package app.ptrip.tracktrip

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.location.LocationFix
import app.ptrip.tracktrip.location.updates
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.map.Speed
import app.ptrip.tracktrip.ui.AppLocale
import app.ptrip.tracktrip.ui.BackStack
import app.ptrip.tracktrip.ui.CreateTripScreen
import app.ptrip.tracktrip.ui.CreateTripViewModel
import app.ptrip.tracktrip.ui.JoinTripViewModel
import app.ptrip.tracktrip.ui.joinCodeFrom
import app.ptrip.tracktrip.ui.joinWebLinkFor
import app.ptrip.tracktrip.ui.rememberSharingPermissionRequest
import app.ptrip.tracktrip.ui.LocalApiBaseUrl
import app.ptrip.tracktrip.ui.ProfileScreen
import app.ptrip.tracktrip.ui.ProfileViewModel
import app.ptrip.tracktrip.ui.ScanQrScreen
import app.ptrip.tracktrip.ui.Screen
import app.ptrip.tracktrip.ui.SettingsScreen
import app.ptrip.tracktrip.ui.SettingsViewModel
import app.ptrip.tracktrip.ui.SharingDuration
import app.ptrip.tracktrip.ui.SignInScreen
import app.ptrip.tracktrip.ui.SignInUiState
import app.ptrip.tracktrip.ui.SignInViewModel
import app.ptrip.tracktrip.ui.TripDetailScreen
import app.ptrip.tracktrip.ui.TripDetailViewModel
import app.ptrip.tracktrip.ui.MapFocus
import app.ptrip.tracktrip.ui.TripListScreen
import app.ptrip.tracktrip.ui.TripMapScreen
import app.ptrip.tracktrip.ui.TripMapViewModel
import app.ptrip.tracktrip.ui.TripQrScreen
import app.ptrip.tracktrip.ui.TripsViewModel
import app.ptrip.tracktrip.ui.rememberBackStack
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * An [AppCompatActivity], not a bare [ComponentActivity], for one reason: the
 * per-app language API. Below Android 13, `AppCompatDelegate` is what stores
 * the chosen locale and recreates the activity to apply it, and it can only do
 * that to an activity it owns.
 */
class MainActivity : AppCompatActivity() {

    private val container: AppContainer by lazy { AppContainer.from(this) }

    /** A join code from a link the app was opened with, awaiting a signed-in rider. */
    private val pendingJoinCode = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The stored language is already in force — TracktripApplication puts
        // it there before any activity exists.
        super.onCreate(savedInstanceState)
        // Both bars are drawn over a light background, so both are asked for
        // dark icons explicitly. The default (`auto`) follows the *system*
        // dark-mode setting, which on a phone in dark mode would put white
        // icons on this app's off-white ground.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        pendingJoinCode.value = joinCodeFrom(intent?.dataString)

        setContent {
            TracktripTheme {
                // Avatars are stored as paths, so the one place that turns
                // them into URLs needs the API's base URL. Provided here
                // rather than threaded through every screen that shows a face.
                CompositionLocalProvider(LocalApiBaseUrl provides container.baseUrl) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background,
                    ) { innerPadding ->
                        TracktripApp(
                            container = container,
                            pendingJoinCode = pendingJoinCode,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }

    /**
     * A second invite link, tapped while the app was already open.
     *
     * The activity is `singleTask`, so this arrives here instead of as a new
     * instance — without it, the first link would keep being handled forever
     * and later ones would do nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        joinCodeFrom(intent.dataString)?.let { pendingJoinCode.value = it }
    }
}

@Composable
private fun TracktripApp(
    container: AppContainer,
    pendingJoinCode: MutableStateFlow<String?>,
    modifier: Modifier = Modifier,
) {
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
            user = current,
            pendingJoinCode = pendingJoinCode,
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
    user: SignInUiState.SignedIn,
    pendingJoinCode: MutableStateFlow<String?>,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One view model for the trip list, kept across navigation so coming back
    // from a trip doesn't blank the list while it reloads.
    val tripsViewModel: TripsViewModel =
        viewModel(factory = tripsViewModelFactory(container, onSignOut))
    val tripsState by tripsViewModel.uiState.collectAsStateWithLifecycle()

    // The rider's own account, loaded once from the server. Several screens
    // need it, and unlike the sign-in response it is still there after a
    // restart that came in on stored tokens.
    val profileViewModel: ProfileViewModel =
        viewModel(factory = profileViewModelFactory(container, onSignOut))
    val profileState by profileViewModel.uiState.collectAsStateWithLifecycle()
    val profile = profileState.user

    // What the location service is broadcasting. Read here rather than in each
    // screen so the trip list, the trip screen and settings cannot disagree.
    val sharing by container.sharingController.active.collectAsStateWithLifecycle()

    val joinViewModel: JoinTripViewModel =
        viewModel(factory = joinTripViewModelFactory(container, onSignOut))
    val joinState by joinViewModel.uiState.collectAsStateWithLifecycle()

    // An invite link the app was opened with. Redeemed once a rider is signed
    // in — which is now, since this whole branch only exists then.
    val linkCode by pendingJoinCode.collectAsStateWithLifecycle()
    LaunchedEffect(linkCode) {
        val code = linkCode ?: return@LaunchedEffect
        pendingJoinCode.value = null
        joinViewModel.join(code) { trip ->
            tripsViewModel.refresh()
            backStack.resetToRoot()
            backStack.push(Screen.TripDetail(trip.id))
        }
    }

    // A sharing action waiting on the permission dialog's answer: a toggle
    // from settings, or a duration picked on the trip screen.
    var pendingToggle by remember { mutableStateOf<Pair<Trip, Boolean>?>(null) }
    var pendingDuration by remember { mutableStateOf<SharingDuration?>(null) }

    BackHandler(enabled = backStack.canGoBack) { backStack.pop() }

    when (val screen = backStack.current) {
        Screen.Trips -> TripListScreen(
            state = tripsState,
            displayName = profile?.label ?: user.displayName,
            sharingTripName = sharing?.tripName,
            onOpenTrip = { backStack.push(Screen.TripDetail(it.id)) },
            onCreateTrip = { backStack.push(Screen.CreateTrip) },
            onAcceptInvite = tripsViewModel::acceptInvite,
            onRefresh = tripsViewModel::refresh,
            onScanQr = { backStack.push(Screen.ScanQr) },
            onOpenSettings = { backStack.push(Screen.Settings) },
            modifier = modifier,
        )

        Screen.Settings -> {
            val settingsViewModel: SettingsViewModel =
                viewModel(factory = settingsViewModelFactory(container, onSignOut))
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

            // Applying the language is the screen's side of the setting: the
            // view model persists the choice, AppCompat enacts it (and, below
            // Android 13, recreates this activity to do so).
            LaunchedEffect(settingsState.language) {
                AppLocale.apply(settingsState.language.tag)
            }

            val requestSharingPermission = rememberSharingPermissionRequest(
                onGranted = { pendingToggle?.let { (trip, on) -> settingsViewModel.toggleSharing(trip, on) } },
                onDenied = settingsViewModel::onPermissionDenied,
            )

            SettingsScreen(
                state = settingsState,
                displayName = profile?.label ?: user.displayName,
                email = profile?.email ?: user.email,
                photoUrl = profile?.photoUrl ?: user.photoUrl,
                sharingTripId = sharing?.tripId,
                onOpenProfile = { backStack.push(Screen.Profile) },
                onLanguageChange = settingsViewModel::setLanguage,
                onSharingDurationChange = settingsViewModel::setDefaultSharingDuration,
                onToggleSharing = { trip, on ->
                    if (on) {
                        pendingToggle = trip to true
                        requestSharingPermission()
                    } else {
                        settingsViewModel.toggleSharing(trip, false)
                    }
                },
                onSignOut = onSignOut,
                onBack = { backStack.pop() },
                modifier = modifier,
            )
        }

        Screen.Profile -> ProfileScreen(
            state = profileState,
            // Back to settings once it lands: the changed name and photo are
            // both visible there, which is a clearer confirmation than a
            // message on a screen the rider is now finished with.
            onSave = { patch -> profileViewModel.save(patch) { backStack.pop() } },
            onAvatarPicked = { picked ->
                profileViewModel.uploadAvatar(picked.bytes, picked.contentType)
            },
            onImageUnreadable = profileViewModel::onImageUnreadable,
            onBack = { backStack.pop() },
            modifier = modifier,
        )

        Screen.ScanQr -> {
            ScanQrScreen(
                joining = joinState.joining,
                error = joinState.error,
                onCodeScanned = { code ->
                    joinViewModel.join(code) { trip ->
                        // Straight to the trip that was just joined — the
                        // scanner's whole purpose is getting there.
                        tripsViewModel.refresh()
                        backStack.pop()
                        backStack.push(Screen.TripDetail(trip.id))
                    }
                },
                onBack = { backStack.pop() },
                modifier = modifier,
            )
        }

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

            val context = LocalContext.current
            val shareSubject = stringResource(R.string.invite_share_subject)
            val shareChooser = stringResource(R.string.invite_share_chooser)
            val tripName = detailState.trip?.name ?: stringResource(R.string.untitled_trip)
            val shareBodyTemplate = stringResource(R.string.invite_share_body)

            // The share sheet opens once a code has been issued for it, then
            // the request is cleared so a recomposition can't reopen it.
            LaunchedEffect(detailState.pendingShareCode) {
                val code = detailState.pendingShareCode ?: return@LaunchedEffect
                detailViewModel.shareLinkConsumed()
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, shareSubject)
                            putExtra(
                                Intent.EXTRA_TEXT,
                                shareBodyTemplate.format(
                                    tripName,
                                    code.code,
                                    joinWebLinkFor(code.code),
                                ),
                            )
                        },
                        shareChooser,
                    )
                )
            }

            val requestSharingPermission = rememberSharingPermissionRequest(
                onGranted = { pendingDuration?.let(detailViewModel::startSharing) },
                onDenied = detailViewModel::onPermissionDenied,
            )

            TripDetailScreen(
                state = detailState,
                currentUserId = profile?.id,
                sharing = sharing?.tripId == screen.tripId,
                onStartSharing = { duration ->
                    pendingDuration = duration
                    requestSharingPermission()
                },
                onStopSharing = detailViewModel::stopSharing,
                onShareInviteLink = detailViewModel::requestShareLink,
                onOpenMap = { backStack.push(Screen.TripMap(screen.tripId)) },
                onInviteEmailChange = detailViewModel::onInviteEmailChange,
                onSendInvite = detailViewModel::sendInvite,
                onUseSuggestion = detailViewModel::useSuggestion,
                onShowQr = {
                    // Issued on the way in, so the screen never opens on a
                    // code from an earlier visit that has since lapsed.
                    detailViewModel.createJoinCode()
                    backStack.push(Screen.TripQr(screen.tripId))
                },
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

        is Screen.TripMap -> {
            val mapViewModel: TripMapViewModel = viewModel(
                key = "map-${screen.tripId}",
                factory = tripMapViewModelFactory(container, screen.tripId, onSignOut),
            )
            val mapState by mapViewModel.uiState.collectAsStateWithLifecycle()

            val context = LocalContext.current
            val noLocationMessage = stringResource(R.string.map_no_location)
            var centreOn by remember { mutableStateOf<MapFocus?>(null) }
            var centreSequence by remember { mutableIntStateOf(0) }
            val scope = rememberCoroutineScope()

            /**
             * This phone's own position and speed, live for as long as the map
             * is on screen.
             *
             * The cached fix seeds it, so the camera has somewhere to open
             * instantly, and then the provider's own feed takes over. Reading
             * only the cache — which is what this did — meant reading a value
             * that nothing refreshes: the sharing service takes one fix every
             * ten minutes, so between reports the cache ages out and the
             * speedometer fell back to a dash for minutes at a time.
             *
             * The live feed runs only while this screen is composed. The
             * ten-minute *reporting* cadence is untouched: that one exists for
             * a phone in a pocket, and this one for a rider watching the map.
             */
            var myFix by remember { mutableStateOf<android.location.Location?>(null) }
            LaunchedEffect(screen.tripId) {
                myFix = LocationFix.lastKnown(context)
                LocationFix.updates(context).collect { myFix = it }
            }
            val myLocation = myFix?.let { LatLng(it.latitude, it.longitude) }
            val mySpeedKmh = myFix?.let { fix ->
                Speed.ownKmh(
                    metresPerSecond = fix.speed.takeIf { fix.hasSpeed() },
                    fixAgeMs = System.currentTimeMillis() - fix.time,
                )
            }

            /**
             * Where the rider is, best effort: the phone's own idea first,
             * falling back to the position the server last heard from them —
             * which is the one thing that works with location switched off.
             */
            fun centreOnMe() {
                scope.launch {
                    val cached = LocationFix.lastKnown(context)
                    val fix = cached ?: LocationFix.current(context, LocationFix.QUICK_TIMEOUT_MS)
                    val fallback = mapState.members
                        .firstOrNull { it.userId == profile?.id && it.hasPosition }

                    val target = when {
                        fix != null -> fix.latitude to fix.longitude
                        fallback?.lat != null && fallback.lng != null ->
                            fallback.lat!! to fallback.lng!!
                        else -> null
                    }
                    if (target == null) {
                        mapViewModel.onNoLocation(noLocationMessage)
                    } else {
                        centreSequence += 1
                        centreOn = MapFocus(target.first, target.second, centreSequence)
                    }
                }
            }

            // The same permission request the sharing controls use — asked
            // only when the button is pressed without it.
            val requestLocation = rememberSharingPermissionRequest(
                onGranted = { centreOnMe() },
                onDenied = mapViewModel::onNoLocation,
            )

            TripMapScreen(
                state = mapState,
                currentUserId = profile?.id,
                centreOn = centreOn,
                myLocation = myLocation,
                mySpeedKmh = mySpeedKmh,
                onRefresh = mapViewModel::refresh,
                onCenterOnMe = {
                    if (LocationFix.hasPermission(context)) centreOnMe() else requestLocation()
                },
                onBack = { backStack.pop() },
                modifier = modifier,
            )
        }

        is Screen.TripQr -> {
            // The same instance the trip screen used, so the code it just
            // issued is the one shown here.
            val detailViewModel: TripDetailViewModel = viewModel(
                key = "trip-${screen.tripId}",
                factory = tripDetailViewModelFactory(container, screen.tripId, onSignOut),
            )
            val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()

            TripQrScreen(
                joinCode = detailState.joinCode,
                loading = detailState.joinCodeLoading,
                error = detailState.joinCodeError,
                onGenerate = detailViewModel::createJoinCode,
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
    factoryOf {
        TripDetailViewModel(
            tripId = tripId,
            tripApi = container.tripApi,
            sharingController = container.sharingController,
            settings = container.settings,
            onSessionExpired = onSessionExpired,
        )
    }

private fun settingsViewModelFactory(
    container: AppContainer,
    onSessionExpired: () -> Unit,
): ViewModelProvider.Factory =
    factoryOf {
        SettingsViewModel(
            settings = container.settings,
            tripApi = container.tripApi,
            sharingController = container.sharingController,
            onSessionExpired = onSessionExpired,
        )
    }

private fun tripMapViewModelFactory(
    container: AppContainer,
    tripId: Long,
    onSessionExpired: () -> Unit,
): ViewModelProvider.Factory =
    factoryOf { TripMapViewModel(tripId, container.tripApi, onSessionExpired) }

private fun profileViewModelFactory(
    container: AppContainer,
    onSessionExpired: () -> Unit,
): ViewModelProvider.Factory =
    factoryOf { ProfileViewModel(container.meApi, onSessionExpired) }

private fun joinTripViewModelFactory(
    container: AppContainer,
    onSessionExpired: () -> Unit,
): ViewModelProvider.Factory =
    factoryOf { JoinTripViewModel(container.tripApi, onSessionExpired) }
