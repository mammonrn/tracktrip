package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which trips the list opens on, and which wait in the archive.
 *
 * The rule is small and split in two on purpose: the screen draws what
 * [TripListRules.shown] returns, and the view model reads a leaderboard for
 * what [TripListRules.shown] returns. Two answers that could drift apart would
 * mean a card showing a podium nobody can see, or a card without one where
 * every other card has one.
 */
class TripListRulesTest {

    private fun trips(count: Int): List<Trip> = (1..count).map {
        Trip(it.toLong(), "Ride $it", 1L, Trip.STATUS_ACTIVE, Trip.ROLE_OWNER)
    }

    @Test
    fun `the newest three open the list`() {
        // `GET /trips` orders newest first, so the newest three are the first
        // three and no timestamp has to cross the wire for this to hold.
        assertEquals(listOf(1L, 2L, 3L), TripListRules.recent(trips(7)).map { it.id })
    }

    @Test
    fun `everything older is the archive`() {
        assertEquals(listOf(4L, 5L, 6L, 7L), TripListRules.archived(trips(7)).map { it.id })
    }

    @Test
    fun `a short list is all recent and no archive`() {
        assertEquals(3, TripListRules.recent(trips(3)).size)
        assertTrue(TripListRules.archived(trips(3)).isEmpty())
        assertTrue(TripListRules.archived(emptyList()).isEmpty())
    }

    @Test
    fun `the two halves are the whole list and nothing twice`() {
        val all = trips(9)
        val rejoined = TripListRules.recent(all) + TripListRules.archived(all)

        // The property that matters more than either half: nothing is
        // dropped, and the order the server chose survives the split.
        assertEquals(all, rejoined)
    }

    @Test
    fun `an open archive shows everything, a closed one the newest three`() {
        val all = trips(9)

        assertEquals(all, TripListRules.shown(all, archiveOpen = true))
        assertEquals(
            TripListRules.recent(all),
            TripListRules.shown(all, archiveOpen = false),
        )
    }
}
