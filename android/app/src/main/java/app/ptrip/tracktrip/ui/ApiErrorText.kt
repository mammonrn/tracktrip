package app.ptrip.tracktrip.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.ActiveTripException

/**
 * The error line for a screen that can be refused for being mid-ride.
 *
 * ## Why one refusal is worded here rather than by the server
 *
 * Everywhere else the app prints the backend's own `error` string, which is
 * deliberate: "trip has ended", "only the trip owner can do this" are written
 * to be read, and re-wording them here would let the app's rules drift from
 * the server's. The cost is that they are English, on a phone that may be set
 * to Thai.
 *
 * That cost is worth paying for a message a rider meets once. It is not worth
 * paying for this one. Being told you cannot start a trip because you are
 * already on one is the refusal every rider will meet the first time they
 * forget to end a ride, and it is the only thing standing between them and the
 * thing they opened the app to do — so it is said in their own language, with
 * the trip named.
 *
 * [blockedByTripName] rather than a code the screen matches on, because the
 * name is the half that matters: a rider who has forgotten to end a ride does
 * not know which one it was, and "you already have an active trip" sends them
 * to a list to find out.
 *
 * Falls back to [error] — which for this case is the server's own English
 * sentence — so a build talking to a server that predates the code still says
 * something true. See [ActiveTripException].
 */
@Composable
internal fun apiErrorText(error: String?, blockedByTripName: String?): String? =
    blockedByTripName?.let { stringResource(R.string.error_active_trip_exists, it) } ?: error
