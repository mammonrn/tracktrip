package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.SuggestedInvitee

/**
 * Which of "Ridden with before" is on screen, and which is behind a number.
 *
 * ## Why there is a window at all
 *
 * The panel drew every past companion at once, in a box that scrolled once it
 * grew past four rows. For a rider on their third trip that is three chips;
 * for the group this app was written for it is a wall of forty names in a
 * scroller, above the three buttons that are the actual ways onto a trip. The
 * shortcut had become the biggest thing on the panel, and a wall of names is
 * not a shortcut — it is the same reading problem the email form had before it
 * went behind a button.
 *
 * So: [VISIBLE] chips, and a "+12 more" chip that opens the rest in place.
 * Nothing is hidden — the number says exactly how much is behind it, and one
 * press puts it on screen — but the ordinary case is five names and a press.
 *
 * ## Which five
 *
 * The first five of the order the server sent, which is
 * `trips_together DESC, last_ridden_together DESC` — most ridden with, and
 * among equals most recently. That pairing is written down here because
 * `SuggestedInvitee` carries neither field except `tripsTogether`, so nothing
 * in the app would otherwise say what "the first five" means, and a change to
 * the `ORDER BY` in `GET /trips/:id/suggested-invitees` would silently change
 * what this screen offers.
 *
 * Two things are taken out of that order before it is cut to five, and both
 * are about the same thing — a chip is a shortcut for *adding somebody*, so a
 * name that cannot be added is a name in the way:
 *
 * - **Already on this trip.** The server excludes members when it builds the
 *   list, so this only catches the ones who joined since it was read. Dropped
 *   outright rather than sunk: they are on the trip, and there is nothing left
 *   to offer about them.
 * - **Invited in this sitting.** Sunk to the end, not dropped, which is the
 *   rule [ordered] inherited and keeps: inviting the wrong Nut is easy, and a
 *   chip that vanishes on tap leaves no way to notice, let alone to invite the
 *   right one afterwards. Sinking is also what makes the window refill — an
 *   invited chip drops out of the five and the sixth name rises into its place
 *   — which is the whole ask: a rider works down a group by pressing the same
 *   corner of the screen until the names run out.
 *
 * The sort is stable both times, so the server's order survives inside each
 * group.
 */
object InviteShortcuts {

    /**
     * How many chips are drawn before the rest go behind a number.
     *
     * Five: enough that a regular group is all on screen, few enough that the
     * chips stay one or two rows above the buttons they must not bury.
     */
    const val VISIBLE = 5

    /**
     * The chips, split into the ones drawn and the ones behind "+N more".
     *
     * [hidden] is a list rather than a count so that opening it needs no
     * second pass over anything — the chip says `hidden.size` and draws
     * `hidden` when pressed.
     */
    data class Window(
        val shown: List<SuggestedInvitee>,
        val hidden: List<SuggestedInvitee>,
    ) {
        /** How many the "+N more" chip is holding. Zero means there is no chip. */
        val moreCount: Int get() = hidden.size

        /** Everything, in order, for when the rider has opened it. */
        val all: List<SuggestedInvitee> get() = shown + hidden
    }

    /**
     * The full list in the order the chips are drawn in.
     *
     * @param memberIds who is already on this trip — dropped.
     * @param invited who has been invited since this screen opened — sunk.
     */
    fun ordered(
        suggestions: List<SuggestedInvitee>,
        invited: Set<Long>,
        memberIds: Set<Long>,
    ): List<SuggestedInvitee> =
        suggestions
            .filterNot { it.userId in memberIds }
            .sortedBy { it.userId in invited }

    /** [ordered], cut at [VISIBLE]. */
    fun window(ordered: List<SuggestedInvitee>): Window =
        Window(shown = ordered.take(VISIBLE), hidden = ordered.drop(VISIBLE))
}
