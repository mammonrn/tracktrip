package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.Trip

/**
 * How much of a rider's history the trip list opens on.
 *
 * ## Why the whole list stopped being the right answer
 *
 * This is the first screen the app shows, and its job is "get me into the ride
 * I am about to do". A rider who has been out most weekends for a year has
 * forty trips, and all forty were printed here, so the list a rider actually
 * wanted was three cards at the top of a column they had to scroll past every
 * time they opened the app.
 *
 * Nothing is hidden — [archived] is one tap away and says how many are in it.
 * What changes is which of the two questions this screen answers first: "what
 * am I riding" comes before "what have I ridden".
 *
 * ## Why "recent" is the first three and not a date
 *
 * `GET /trips` orders by `created_at DESC, id DESC` — newest first, for every
 * caller, with the id breaking ties between two trips made in the same second.
 * So the newest [RECENT] are the first [RECENT], and the client never has to
 * hold a timestamp to know it.
 *
 * That is worth writing down because the Android `Trip` has no `created_at` on
 * it at all: if the server's ordering ever changed, this would silently start
 * calling the wrong three trips recent rather than failing. The pairing is the
 * same shape as `ReportCadence`'s copy of the backend's rate limit.
 */
object TripListRules {

    /**
     * How many trips the list opens on.
     *
     * Three because that is what fits above the fold next to the invitations
     * and the two buttons at the bottom, and because a rider planning this
     * weekend is choosing between this weekend's rides.
     */
    const val RECENT = 3

    /** The trips the list shows before anybody asks for more. */
    fun recent(trips: List<Trip>): List<Trip> = trips.take(RECENT)

    /** Everything older, which the archive holds. Empty for a new rider. */
    fun archived(trips: List<Trip>): List<Trip> = trips.drop(RECENT)

    /**
     * What to draw, given whether the archive is open.
     *
     * One function rather than the screen deciding, because the view model
     * asks the same question when it works out which trips are on screen and
     * therefore worth reading a leaderboard for — and two answers that could
     * disagree would mean a card showing a podium the rider cannot see, or a
     * card with none where every other card has one.
     */
    fun shown(trips: List<Trip>, archiveOpen: Boolean): List<Trip> =
        if (archiveOpen) trips else recent(trips)
}
