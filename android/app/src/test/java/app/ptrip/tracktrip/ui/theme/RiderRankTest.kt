package app.ptrip.tracktrip.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rank badges are keyed by the level names the backend sends, so the two
 * lists have to agree. If `src/users/levels.js` is retuned, this is what
 * notices.
 */
class RiderRankTest {

    @Test
    fun `the ranks match the backend's table, in order`() {
        assertEquals(
            listOf(
                "Novice",
                "Rookie Rider",
                "Wanderer",
                "Voyager",
                "Trailblazer",
                "Road Warrior",
                "Road King",
                "Legendary",
            ),
            RiderRank.entries.map { it.apiName },
        )
    }

    @Test
    fun `a name from the server picks its badge`() {
        assertEquals(RiderRank.ROOKIE_RIDER, RiderRank.fromApiName("Rookie Rider"))
        assertEquals(RiderRank.LEGENDARY, RiderRank.fromApiName("legendary"))
        assertEquals(RiderRank.ROAD_KING, RiderRank.fromApiName("  Road King  "))
    }

    @Test
    fun `a rank this build has never heard of falls back to the first one`() {
        assertEquals(RiderRank.NOVICE, RiderRank.fromApiName("Interstellar"))
        assertEquals(RiderRank.NOVICE, RiderRank.fromApiName(""))
        assertEquals(RiderRank.NOVICE, RiderRank.fromApiName(null))
    }
}
