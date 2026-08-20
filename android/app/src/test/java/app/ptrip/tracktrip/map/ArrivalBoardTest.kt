package app.ptrip.tracktrip.map

import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.TripEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a trip card says reached the finish, and in what order.
 *
 * The measure is honest about being a measure — see [ArrivalBoard] — so what
 * is worth pinning here is the part that must never wobble: who counts as
 * having arrived, that the order is the order of the last reports, that it is
 * stable when the clock cannot separate two riders, and that a trip with
 * nobody at the finish produces nothing at all rather than an empty podium.
 */
class ArrivalBoardTest {

    private val pai = TripEndpoint(19.3583, 98.4400, "Pai")

    private fun rider(
        id: Long,
        name: String,
        lat: Double? = pai.lat,
        lng: Double? = pai.lng,
        recordedAt: String? = "2026-08-20T10:00:00Z",
    ) = MemberPosition(
        userId = id,
        displayName = name,
        username = null,
        photoUrl = null,
        role = "member",
        isSharing = false,
        sharingUntil = null,
        lat = lat,
        lng = lng,
        speedMps = null,
        batteryPct = null,
        recordedAt = recordedAt,
    )

    @Test
    fun `the earliest last report takes first place`() {
        val podium = ArrivalBoard.podium(
            pai,
            listOf(
                rider(1, "Nut", recordedAt = "2026-08-20T14:25:00Z"),
                rider(2, "Poom", recordedAt = "2026-08-20T14:00:00Z"),
                rider(3, "Ann", recordedAt = "2026-08-20T14:10:00Z"),
            ),
        )

        assertEquals(listOf("Poom", "Ann", "Nut"), podium.map { it.label })
        assertEquals(listOf(1, 2, 3), podium.map { it.place })
    }

    @Test
    fun `only the first three get a place`() {
        val podium = ArrivalBoard.podium(
            pai,
            (1..6).map { rider(it.toLong(), "Rider $it", recordedAt = "2026-08-20T14:0$it:00Z") },
        )

        assertEquals(ArrivalBoard.PLACES, podium.size)
        assertEquals(listOf("Rider 1", "Rider 2", "Rider 3"), podium.map { it.label })
    }

    @Test
    fun `a rider still out on the road is not on the podium`() {
        val podium = ArrivalBoard.podium(
            pai,
            listOf(
                rider(1, "Poom"),
                // Chiang Mai, a hundred kilometres short of the finish.
                rider(2, "Nut", lat = 18.7883, lng = 98.9853),
            ),
        )

        assertEquals(listOf("Poom"), podium.map { it.label })
    }

    @Test
    fun `arriving in the car park rather than on the pin still counts`() {
        // A destination is a pin somebody dropped roughly, not a doorway. Two
        // hundred metres out is the far side of a village square.
        val podium = ArrivalBoard.podium(
            pai,
            listOf(rider(1, "Poom", lat = pai.lat + 0.0018, lng = pai.lng)),
        )

        assertEquals(listOf("Poom"), podium.map { it.label })
    }

    @Test
    fun `nothing at all when nobody has finished`() {
        // Not an empty podium — nothing. Most trips in a rider's archive never
        // had a destination, and a row of grey placeholders on every card
        // would be the app filling space with what it does not know.
        assertTrue(ArrivalBoard.podium(pai, emptyList()).isEmpty())
        assertTrue(
            ArrivalBoard.podium(pai, listOf(rider(1, "Poom", lat = 18.7, lng = 98.9))).isEmpty()
        )
    }

    @Test
    fun `a trip with no destination has no finish to have reached`() {
        assertTrue(ArrivalBoard.podium(null, listOf(rider(1, "Poom"))).isEmpty())
    }

    @Test
    fun `a rider who has never reported is not placed`() {
        val podium = ArrivalBoard.podium(
            pai,
            listOf(rider(1, "Poom"), rider(2, "Nut", lat = null, lng = null)),
        )

        assertEquals(listOf("Poom"), podium.map { it.label })
    }

    @Test
    fun `two riders the clock cannot separate keep the same order every time`() {
        // The failure that would actually matter: a podium that reshuffles
        // itself under a rider's thumb on every poll. Whatever order these
        // two are handed over in, they come back in one order.
        val a = rider(7, "Ann")
        val b = rider(3, "Bee")

        assertEquals(
            ArrivalBoard.podium(pai, listOf(a, b)).map { it.userId },
            ArrivalBoard.podium(pai, listOf(b, a)).map { it.userId },
        )
    }

    @Test
    fun `a fix with no readable time does not outrank one that has one`() {
        val podium = ArrivalBoard.podium(
            pai,
            listOf(
                rider(1, "Nut", recordedAt = null),
                rider(2, "Poom", recordedAt = "2026-08-20T14:00:00Z"),
            ),
        )

        // "No time" is not evidence of having been there longest.
        assertEquals(listOf("Poom", "Nut"), podium.map { it.label })
    }
}
