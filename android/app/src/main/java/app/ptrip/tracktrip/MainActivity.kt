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
import android.os.SystemClock
import app.ptrip.tracktrip.location.LiveFix
import app.ptrip.tracktrip.location.LocationFix
import app.ptrip.tracktrip.location.updatesRetrying
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.map.Speed
import kotlinx.coroutines.delay
import app.ptrip.tracktrip.ui.AppLocale
import app.ptrip.tracktrip.ui.BackStack
import app.ptrip.tracktrip.ui.CreateTripScreen
import app.ptrip.tracktrip.ui.CreateTripViewModel
import app.ptrip.tracktrip.ui.EditTripActions
import app.ptrip.tracktrip.ui.EditTripScreen
import app.ptrip.tracktrip.ui.JoinTripViewModel
import app.ptrip.tracktrip.ui.joinCodeFrom
import app.ptrip.tracktrip.ui.joinWebLinkFor
import app.ptrip.tracktrip.ui.rememberCentreOnMe
import app.ptrip.tracktrip.ui.rememberSharingPermissionRequest
import app.ptrip.tracktrip.ui.LocalApiBaseUrl
import app.ptrip.tracktrip.ui.ProfileScreen
import app.ptrip.tracktrip.ui.ProfileViewModel
import app.ptrip.tracktrip.ui.PlacesMapScreen
import app.ptrip.tracktrip.ui.PlacesViewModel
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
            onOpenMap = { backStack.push(Screen.Places) },
            onOpenSettings = { backStack.push(Screen.Settings) },
            isSuperuser = profile?.isSuperuser == true,
            onShowAllTrips = tripsViewModel::setShowAllTrips,
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
                askBatteryExemption = true,
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

        Screen.Profile -> {
            // Re-read on the way in.
            //
            // This view model is scoped to the whole signed-in session,
            // because settings, the trip screen and this one all need the
            // rider's account — and it loaded exactly once, when the app
            // started. Everything on it that a rider *changes* here was
            // therefore right, and the one thing the server changes underneath
            // them was not: `Ridden` showed the distance they had at sign-in,
            // so a rider who opened the app, rode for an hour and then looked
            // at their profile saw the number they set out with. Opening the
            // screen is exactly the moment to ask again.
            LaunchedEffect(Unit) { profileViewModel.refresh() }

            ProfileScreen(
                state = profileState,
                // Back to settings once it lands: the changed name and photo
                // are both visible there, which is a clearer confirmation than
                // a message on a screen the rider is now finished with.
                onSave = { patch -> profileViewModel.save(patch) { backStack.pop() } },
                onAvatarPicked = { picked ->
                    profileViewModel.uploadAvatar(picked.bytes, picked.contentType)
                },
                onImageUnreadable = profileViewModel::onImageUnreadable,
                onBack = { backStack.pop() },
                modifier = modifier,
            )
        }

        Screen.Places -> {
            val placesViewModel: PlacesViewModel =
                viewModel(factory = placesViewModelFactory(container, profile?.id, onSignOut))
            val placesState by placesViewModel.uiState.collectAsStateWithLifecycle()
            val searchState by placesViewModel.placeSearch.state.collectAsStateWithLifecycle()
            val context = LocalContext.current

            // Read, never reported. Nothing on this screen shares a position:
            // there is no trip to share it with. It is here to bias the search
            // towards the region the rider is in, to fill "use my current
            // location", and to answer the button that centres the map on the
            // rider — all of which want a fix, not a feed.
            var mapPermission by remember { mutableStateOf(false) }
            var myLocation by remember { mutableStateOf<LatLng?>(null) }
            var centreOn by remember { mutableStateOf<MapFocus?>(null) }
            var centreSequence by remember { mutableIntStateOf(0) }
            val noLocationMessage = stringResource(R.string.map_no_location)

            // A rider's own position, however it arrives: noted here, handed
            // to the search so a place name is ranked against the region they
            // are in, and — when they asked for it — pointed at.
            val onFix: (LatLng) -> Unit = { here ->
                myLocation = here
                placesViewModel.placeSearch.near = here
            }

            // The button's own logic, which is not this navigation graph's to
            // hold: see [CentreOnMe] for the two rules it exists to keep — one
            // lookup per press, and a busy state that lasts long enough to be
            // drawn.
            val centre = rememberCentreOnMe(
                onFix = { here ->
                    onFix(here)
                    centreSequence += 1
                    centreOn = MapFocus(here.lat, here.lng, centreSequence)
                },
                onNoLocation = { placesViewModel.onNoLocation(noLocationMessage) },
            )

            // The read on the way in, which is nobody's request: it fills
            // "use my current location" and opens the camera somewhere useful,
            // and has nothing to say for itself if it finds nothing.
            LaunchedEffect(Unit) {
                mapPermission = LocationFix.hasPermission(context)
                if (mapPermission) {
                    LocationFix.nearest(context)?.let { onFix(LatLng(it.latitude, it.longitude)) }
                }
            }

            // The same request the sharing controls and the trip map's own
            // "centre on me" use — asked only when the button is pressed
            // without it. This screen deliberately does not ask on arrival:
            // looking a place up needs no permission, and a dialog in front
            // of a map nobody asked to be located on is a toll.
            val requestLocation = rememberSharingPermissionRequest(
                onGranted = { mapPermission = true; centre.press() },
                onDenied = placesViewModel::onNoLocation,
            )

            PlacesMapScreen(
                state = placesState,
                searchState = searchState,
                myLocation = myLocation,
                hasLocationPermission = mapPermission,
                currentUserId = profile?.id,
                onSearchQueryChanged = placesViewModel.placeSearch::onQueryChanged,
                onSearchCleared = placesViewModel.placeSearch::clear,
                onAddShared = placesViewModel::addShared,
                onAddPersonal = placesViewModel::addPersonal,
                onRemoveShared = placesViewModel::removeShared,
                onRemovePersonal = placesViewModel::removePersonal,
                onDismissError = placesViewModel::clearError,
                centreOn = centreOn,
                centringOnMe = centre.busy,
                onCenterOnMe = {
                    // Re-read rather than trusting what the arrival found: a
                    // rider who granted the permission from Android's own
                    // settings while this screen was in front of them is
                    // exactly who presses this.
                    if (LocationFix.hasPermission(context)) {
                        mapPermission = true
                        centre.press()
                    } else {
                        requestLocation()
                    }
                },
                onBack = { backStack.pop() },
                modifier = modifier,
            )
        }

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
                askBatteryExemption = true,
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
                onEditTrip = {
                    // Any failure from a previous visit stays on the form it
                    // happened on, not on the one being opened.
                    detailViewModel.clearRenameError()
                    backStack.push(Screen.EditTrip(screen.tripId))
                },
                onEndTrip = {
                    detailViewModel.endTrip()
                    // The list shows each trip's status, so it is stale the
                    // moment this one ends.
                    tripsViewModel.refresh()
                },
                // Quiet: a background poll must not raise the loading flag or
                // clear an error the rider has not read. See
                // TripDetailViewModel.refresh for why this screen polls at all.
                onRefresh = { detailViewModel.refresh(quiet = true) },
                onBack = { backStack.pop() },
                modifier = modifier,
            )
        }

        is Screen.EditTrip -> {
            // The same view model the member list uses, on the same key, so
            // the trip is already loaded when this opens and the new name is
            // on the screen behind before the rider gets back to it — no
            // second fetch, and no window where the two disagree.
            val detailViewModel: TripDetailViewModel = viewModel(
                key = "trip-${screen.tripId}",
                factory = tripDetailViewModelFactory(container, screen.tripId, onSignOut),
            )
            val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()

            // And the map's, on *its* key, for the same reason: the route list
            // on this screen is the map's route list, editing the map's draft.
            // A second instance here would give the two screens a draft each,
            // which is precisely the disagreement the shared list avoids.
            //
            // It is not free. TripMapViewModel opens this trip's position
            // socket in its `init`, so reaching the route list from here starts
            // a live feed for a screen that draws no positions — and, being
            // scoped to the activity, it outlives the visit. A rider who opens
            // the map at all pays that anyway; this brings it forward. Worth
            // revisiting if the route ever needs its own view model, but not
            // worth a second copy of the route logic to avoid today.
            val mapViewModel: TripMapViewModel = viewModel(
                key = "map-${screen.tripId}",
                factory = tripMapViewModelFactory(container, screen.tripId, onSignOut),
            )
            val mapState by mapViewModel.uiState.collectAsStateWithLifecycle()
            val routeSearchState by mapViewModel.placeSearch.state.collectAsStateWithLifecycle()

            val context = LocalContext.current
            var routePermission by remember { mutableStateOf(false) }
            var routeLocation by remember { mutableStateOf<LatLng?>(null) }
            LaunchedEffect(Unit) {
                routePermission = LocationFix.hasPermission(context)
                if (routePermission) {
                    val fix = LocationFix.nearest(context)
                    routeLocation = fix?.let { LatLng(it.latitude, it.longitude) }
                    mapViewModel.placeSearch.near = routeLocation
                }
            }

            // The two exits, and the difference between them — which is the
            // whole of [EditTripActions]. Saving the name is a write to one
            // field and touches nothing else; *leaving* is what throws the
            // route draft away, because the map seeds its route list from the
            // trip and a draft left sitting here is what it would find.
            val actions = EditTripActions(
                rename = { name, done -> detailViewModel.rename(name, done) },
                // The trip list shows every name, so it is stale the moment
                // one changes.
                onRenamed = tripsViewModel::refresh,
                discardRouteDraft = {
                    mapViewModel.closeRouteSetup()
                    mapViewModel.placeSearch.clear()
                },
                goBack = { backStack.pop() },
            )

            EditTripScreen(
                trip = detailState.trip,
                saving = detailState.renamePending,
                error = detailState.renameError,
                onSave = actions::save,
                routeDraft = mapState.routeDraft,
                routePlan = mapState.summaryPlan,
                routeLine = mapState.draftRoute,
                routePreviewLoading = mapState.routePreviewLoading,
                searchState = routeSearchState,
                personalPlaces = mapState.personalPlaces,
                myLocation = routeLocation,
                hasLocationPermission = routePermission,
                currentUserId = profile?.id,
                onOpenRouteSetup = mapViewModel::openRouteSetup,
                onSearchQueryChanged = mapViewModel.placeSearch::onQueryChanged,
                onSearchCleared = mapViewModel.placeSearch::clear,
                onPickRoutePoint = mapViewModel::pickRoutePoint,
                onRemoveRouteRow = mapViewModel::removeRouteRow,
                onMoveRoutePoint = mapViewModel::moveRoutePoint,
                // Confirm here writes the route and leaves it on screen. The
                // list *is* this screen, so the map's confirm — which throws
                // the draft away because its card is closing — would blank the
                // rows the rider just confirmed. See TripMapViewModel.
                onConfirmRoute = mapViewModel::confirmRoute,
                onBack = actions::leave,
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
             * that nothing refreshes: the sharing service takes one fix per
             * reporting cycle, so between reports the cache ages out and the
             * speedometer fell back to a dash — and with sharing switched off
             * nothing refreshes it at all.
             *
             * The live feed runs only while this screen is composed. The
             * background *reporting* cadence is a separate budget: that one
             * exists for a phone in a pocket, and this one for a rider
             * watching the map.
             */
            var myFix by remember { mutableStateOf<android.location.Location?>(null) }
            LaunchedEffect(screen.tripId) {
                myFix = LocationFix.lastKnown(context)
                // `updatesRetrying`, not `updates`: the plain feed ends when
                // the provider is switched off, when permission changes, or
                // when the subscription is refused before delivering anything,
                // and a collector of it then held its last value for ever.
                // That is what left the speedometer showing a dash for a whole
                // ride on a phone that was reporting normally.
                LocationFix.updatesRetrying(context).collect { fix ->
                    // Out-of-order delivery is real: a GNSS engine that has
                    // been batching flushes its queue oldest-first, *after* a
                    // newer fix has already arrived. Taking whatever came last
                    // meant showing a speed from several seconds ago — which
                    // is the "reads behind the bike" symptom, arriving through
                    // a route no amount of tightening the cadence would fix.
                    if (LiveFix.isNewer(myFix?.elapsedRealtimeNanos, fix.elapsedRealtimeNanos)) {
                        myFix = fix
                    }
                }
            }
            val myLocation = myFix?.let { LatLng(it.latitude, it.longitude) }

            /**
             * The speed on the top bar.
             *
             * Held in its own state and recomputed on a timer rather than
             * derived from [myFix] alone, because the *age* of a fix changes
             * without the fix changing. Derived, a feed that went quiet left
             * the last number on screen for ever — the staleness guard in
             * [Speed.ownKmh] would never be re-evaluated, so a phone that lost
             * GPS in a tunnel kept confidently displaying the speed it was
             * doing on the way in.
             *
             * The age itself comes off the monotonic clock, not the wall
             * clock: see [LiveFix]. That removes the satellite-versus-network
             * clock skew this used to have to clamp around, and with it the
             * last place a stale reading could hide.
             */
            var mySpeedKmh by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(screen.tripId) {
                while (true) {
                    mySpeedKmh = myFix?.let { fix ->
                        Speed.ownKmh(
                            metresPerSecond = fix.speed.takeIf { fix.hasSpeed() },
                            fixAgeMs = LiveFix.ageMs(
                                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                                fixElapsedRealtimeNanos = fix.elapsedRealtimeNanos,
                            ),
                        )
                    }
                    delay(SPEED_TICK_MS)
                }
            }

            /**
             * Whether this app may read the phone's position.
             *
             * Re-read on the same beat as the speed rather than once when the
             * screen opened: a rider who grants location from the Android
             * settings while the map is in front of them — which is exactly
             * what the "location access is off" line on the search panel
             * invites — has to see that line go away without reopening the
             * screen. The check is a cached lookup, not a binder round trip.
             */
            var hasLocationPermission by remember { mutableStateOf(false) }
            LaunchedEffect(screen.tripId) {
                while (true) {
                    hasLocationPermission = LocationFix.hasPermission(context)
                    delay(SPEED_TICK_MS)
                }
            }

            val searchState by mapViewModel.placeSearch.state.collectAsStateWithLifecycle()

            /**
             * Where the rider is, best effort: the phone's own idea first,
             * falling back to the position the server last heard from them —
             * which is the one thing that works with location switched off.
             */
            fun centreOnMe() {
                scope.launch {
                    val fix = LocationFix.nearest(context)
                    val fallback = mapState.members
                        .firstOrNull { it.userId == profile?.id && it.hasPosition }

                    val target = when {
                        fix != null -> fix.latitude to fix.longitude
                        fallback?.lat != null && fallback.lng != null ->
                            fallback.lat to fallback.lng
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

            // Where the rider is, handed to the search so a place name is
            // ranked against the region they are riding in rather than against
            // the planet. An effect rather than a line in the composition
            // because it is a side effect on an object the view model owns.
            LaunchedEffect(myLocation) { mapViewModel.placeSearch.near = myLocation }

            TripMapScreen(
                state = mapState,
                currentUserId = profile?.id,
                centreOn = centreOn,
                myLocation = myLocation,
                mySpeedKmh = mySpeedKmh,
                searchState = searchState,
                onSearchQueryChanged = mapViewModel.placeSearch::onQueryChanged,
                onSearchCleared = mapViewModel.placeSearch::clear,
                onOpenRouteSetup = mapViewModel::openRouteSetup,
                onCloseRouteSetup = mapViewModel::closeRouteSetup,
                onPickRoutePoint = mapViewModel::pickRoutePoint,
                onRemoveRouteStop = mapViewModel::removeRouteStop,
                onRemoveRouteRow = mapViewModel::removeRouteRow,
                onMoveRoutePoint = mapViewModel::moveRoutePoint,
                // The card closes behind this one, so the draft goes with it:
                // a draft left on the view model outranks the trip's own stops
                // when the map draws its pins.
                onConfirmRoute = mapViewModel::confirmRouteAndClose,
                onAddSharedPlace = mapViewModel::addSharedPlace,
                onRemoveSharedPlace = mapViewModel::removeSharedPlace,
                onAddPersonalPlace = mapViewModel::addPersonalPlace,
                personalPlaces = mapState.personalPlaces,
                hasLocationPermission = hasLocationPermission,
                onRefresh = mapViewModel::refresh,
                onCenterOnMe = {
                    if (LocationFix.hasPermission(context)) centreOnMe() else requestLocation()
                },
                onPlace = { placement, point, name ->
                    mapViewModel.place(placement, point.lat, point.lng, name)
                },
                onRemoveWaypoint = mapViewModel::removeWaypoint,
                // Only this trip's own reports: `sharing` is what the service
                // is broadcasting, and it broadcasts to one trip at a time.
                lastReportedAtMillis = sharing
                    ?.takeIf { it.tripId == screen.tripId }
                    ?.lastReportedAtMillis,
                isSharingThisTrip = sharing?.tripId == screen.tripId,
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

/**
 * How often the speed readout re-checks how old its fix is.
 *
 * A second, matching the feed it is reading. It only writes state when the
 * displayed number actually changes, so a rider holding a steady speed
 * recomposes nothing.
 */
private const val SPEED_TICK_MS = 1_000L

private fun tripMapViewModelFactory(
    container: AppContainer,
    tripId: Long,
    onSessionExpired: () -> Unit,
): ViewModelProvider.Factory =
    factoryOf {
        TripMapViewModel(
            tripId = tripId,
            tripApi = container.tripApi,
            positionSocket = container.positionSocket,
            onSessionExpired = onSessionExpired,
            placeSearchApi = container.placeSearchApi,
            sharedPlacesApi = container.sharedPlacesApi,
            personalPlacesApi = container.personalPlacesApi,
            routeApi = container.directionsApi,
        )
    }

private fun placesViewModelFactory(
    container: AppContainer,
    currentUserId: Long?,
    onSessionExpired: () -> Unit,
): ViewModelProvider.Factory =
    factoryOf {
        PlacesViewModel(
            sharedPlacesApi = container.sharedPlacesApi,
            personalPlacesApi = container.personalPlacesApi,
            placeSearchApi = container.placeSearchApi,
            currentUserId = currentUserId,
            onSessionExpired = onSessionExpired,
        )
    }

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
