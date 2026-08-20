package app.ptrip.tracktrip.ui

/**
 * The two exits from the edit screen, and what each one is allowed to touch.
 *
 * ## Why this is a class rather than two lambdas in the navigation graph
 *
 * It was two lambdas in the navigation graph, and they grew into each other.
 * Saving a name called the same `leave()` the back arrow did — refresh the
 * list, throw the route draft away, pop the screen — and throwing the route
 * draft away is what a rider saw: press Save on the name, and the route
 * section underneath went back to "Choose a starting point" with every stop
 * they had confirmed gone from the screen.
 *
 * Nothing was lost on the server. `PATCH /trips/:id` leaves out what the body
 * leaves out, so a rename touches the name column and nothing else, and the
 * stops were still there to be read back. What went was the *draft* —
 * `TripMapViewModel.closeRouteSetup()` resets it to an empty [RouteDraft], and
 * the route section on this screen draws the draft.
 *
 * The two exits are genuinely different and the difference is the whole point
 * of writing them down here:
 *
 *  - **[save]** is a write to one field. It does not navigate and it does not
 *    touch the route — not a route that is saved and being looked at, and not
 *    one half-edited on this screen. A rider who fixes a typo in the name has
 *    said nothing about their route.
 *  - **[leave]** is the rider going. *That* is when the draft is discarded,
 *    for the reason it always was: the map seeds the route list from the trip,
 *    and a draft left sitting on the shared view model is what it would find
 *    instead.
 */
class EditTripActions(
    /** Sends the new name, and calls back only if the server took it. */
    private val rename: (String, () -> Unit) -> Unit,
    /** Anything else in the app that shows a trip's name and is now stale. */
    private val onRenamed: () -> Unit,
    /** Throws the route draft away. Leaving only — never saving. */
    private val discardRouteDraft: () -> Unit,
    private val goBack: () -> Unit,
) {

    /**
     * Save the name. Touches the name.
     *
     * The rider stays where they are, looking at the route they were looking
     * at. Everything this does after a successful write is about the *name*:
     * the trip list shows one for every trip, so it is stale the moment this
     * one changes.
     */
    fun save(name: String) {
        rename(name) { onRenamed() }
    }

    /** Back, or Cancel, or the cross in the route card's corner. */
    fun leave() {
        discardRouteDraft()
        goBack()
    }
}
