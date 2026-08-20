package app.ptrip.tracktrip.ui

/**
 * Where the app can be.
 *
 * Deliberately a plain sealed hierarchy rather than Navigation Compose: eight
 * screens and one argument between them don't need a route DSL, and the only
 * published navigation-compose builds at the time of writing are 2.10.0
 * pre-releases. If deep links or nested graphs ever arrive, this is the thing
 * to replace.
 *
 * Kept in its own file, free of Compose, so the back stack's saved form can be
 * tested on a laptop — which is the whole reason [screenToken] exists.
 */
sealed interface Screen {
    data object Trips : Screen
    data object CreateTrip : Screen
    data object Settings : Screen
    data object Profile : Screen
    data object ScanQr : Screen

    /**
     * The map with no trip behind it.
     *
     * A place to look things up and to write places down, reachable from the
     * trip list rather than from inside a ride. It carries no id because it is
     * about nothing but the map: no riders, no positions, no route.
     */
    data object Places : Screen
    data class TripDetail(val tripId: Long) : Screen
    data class TripQr(val tripId: Long) : Screen
    data class TripMap(val tripId: Long) : Screen
}

/**
 * A screen as one short string, for the saved instance state.
 *
 * Android destroys and rebuilds this activity for reasons that have nothing to
 * do with the rider leaving a screen: a language change (AppCompat recreates
 * the activity to apply it), a rotation, a system font-size change, or the
 * system reclaiming memory while the app is in the background. Without a saved
 * form the back stack was rebuilt empty every time, which put the rider back
 * on the trip list with no way back to where they were — the app looked like
 * it had lost its back button when what it had actually lost was its history.
 *
 * A string rather than a Parcelable because that is what `rememberSaveable`
 * stores without ceremony, and because a format this small is worth being able
 * to read in a bug report.
 */
internal fun screenToken(screen: Screen): String = when (screen) {
    Screen.Trips -> "trips"
    Screen.CreateTrip -> "createTrip"
    Screen.Settings -> "settings"
    Screen.Profile -> "profile"
    Screen.ScanQr -> "scanQr"
    Screen.Places -> "places"
    is Screen.TripDetail -> "tripDetail:${screen.tripId}"
    is Screen.TripQr -> "tripQr:${screen.tripId}"
    is Screen.TripMap -> "tripMap:${screen.tripId}"
}

/**
 * The inverse of [screenToken], or null for anything unreadable.
 *
 * Null rather than an exception: saved state can outlive the build that wrote
 * it — a rider updates the app while it sits in the background — and a token
 * this version has never heard of should cost that one entry, not crash the
 * app on resume.
 */
internal fun screenFromToken(token: String): Screen? {
    val name = token.substringBefore(':')
    val argument = token.substringAfter(':', missingDelimiterValue = "")

    return when (name) {
        "trips" -> Screen.Trips
        "createTrip" -> Screen.CreateTrip
        "settings" -> Screen.Settings
        "profile" -> Screen.Profile
        "scanQr" -> Screen.ScanQr
        "places" -> Screen.Places
        "tripDetail" -> argument.toLongOrNull()?.let { Screen.TripDetail(it) }
        "tripQr" -> argument.toLongOrNull()?.let { Screen.TripQr(it) }
        "tripMap" -> argument.toLongOrNull()?.let { Screen.TripMap(it) }
        else -> null
    }
}

/**
 * Restores a stack of screens from the tokens [screenToken] wrote.
 *
 * Always returns something to stand on: an empty or wholly unreadable list
 * becomes the home screen rather than nothing, because a back stack with no
 * current screen has no meaning and every caller would have to guard for it.
 */
internal fun screensFromTokens(tokens: List<String>): List<Screen> {
    val restored = tokens.mapNotNull(::screenFromToken)
    return restored.ifEmpty { listOf(Screen.Trips) }
}
