package app.ptrip.tracktrip.map

import app.ptrip.tracktrip.data.TrailPoint
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of a trip's fixes end up as a line on the map.
 *
 * The endpoint has served the whole history since PR #37; the decisions worth
 * testing are the ones this side makes about it — how far back to reach, how
 * much to draw, and how to ask for the next lot without re-reading what is
 * already held.
 *
 * The last of those is not an optimisation. The history query is `ORDER BY
 * recorded_at ASC ... LIMIT`, so a request without a `since` returns the
 * *oldest* points of the ride: asking for the window is the only way to get
 * the recent tail at all.
 */
class BreadcrumbsTest {

    private val now = Instant.parse("2026-08-20T10:00:00Z").toEpochMilli()

    private fun point(
        id: Long,
        userId: Long = 1,
        minutesAgo: Long = 0,
        secondsAgo: Long = 0,
        lat: Double = 18.0,
        lng: Double = 98.0,
    ) = TrailPoint(
        id = id,
        userId = userId,
        lat = lat,
        lng = lng,
        recordedAt = Instant
            .ofEpochMilli(now - minutesAgo * 60_000L - secondsAgo * 1_000L)
            .toString(),
    )

    // --- the window -------------------------------------------------------

    @Test
    fun `a first fetch asks for the window, not for the whole ride`() {
        val since = Instant.parse(Breadcrumbs.sinceIso(now))

        assertEquals(now - Breadcrumbs.WINDOW_MS, since.toEpochMilli())
    }

    @Test
    fun `a top-up asks only for what is newer than the newest point held`() {
        val held = listOf(point(1, minutesAgo = 10), point(2, minutesAgo = 3))
        val since = Breadcrumbs.sinceIso(now, Breadcrumbs.newest(held))

        assertEquals(point(2, minutesAgo = 3).recordedAt, since)
    }

    @Test
    fun `a top-up with nothing held asks for the window`() {
        assertEquals(Breadcrumbs.sinceIso(now), Breadcrumbs.sinceIso(now, null))
    }

    @Test
    fun `a held point older than the window does not drag the ask back with it`() {
        // The phone was in a pocket for two hours. Asking from that point
        // would fetch an hour and a quarter of rows destined for the bin.
        val stale = Breadcrumbs.newest(listOf(point(1, minutesAgo = 120)))

        assertEquals(Breadcrumbs.sinceIso(now), Breadcrumbs.sinceIso(now, stale))
    }

    @Test
    fun `points older than the window are dropped`() {
        val points = listOf(
            point(1, minutesAgo = 90),
            point(2, minutesAgo = 60),
            point(3, minutesAgo = 10),
            point(4, minutesAgo = 1),
        )

        assertEquals(listOf(3L, 4L), Breadcrumbs.trim(points, now).map { it.id })
    }

    @Test
    fun `a trail is drawn oldest first, which is the order a line is drawn in`() {
        val jumbled = listOf(point(3, minutesAgo = 1), point(1, minutesAgo = 20), point(2, minutesAgo = 10))

        assertEquals(listOf(1L, 2L, 3L), Breadcrumbs.trim(jumbled, now).map { it.id })
    }

    @Test
    fun `a point with an unreadable timestamp is dropped rather than guessed at`() {
        val points = listOf(
            point(1, minutesAgo = 5),
            TrailPoint(id = 2, userId = 1, lat = 18.0, lng = 98.0, recordedAt = "not a time"),
            TrailPoint(id = 3, userId = 1, lat = 18.0, lng = 98.0, recordedAt = null),
        )

        assertEquals(listOf(1L), Breadcrumbs.trim(points, now).map { it.id })
    }

    // --- the cap ----------------------------------------------------------

    @Test
    fun `an ordinary ride is never trimmed by the cap`() {
        // The whole window filled at the app's forty-five-second reporting
        // cadence: sixty points. The cap is double that on purpose, so a
        // rider on a normal ride never loses the far end of their own line.
        val cadenceSeconds = 45L
        val perWindow = (Breadcrumbs.WINDOW_MS / 1000L / cadenceSeconds).toInt()
        val ride = (0 until perWindow).map {
            point(it.toLong(), secondsAgo = it * cadenceSeconds)
        }

        assertEquals(60, perWindow)
        assertEquals(perWindow, Breadcrumbs.trim(ride, now).size)
        assertTrue(perWindow < Breadcrumbs.MAX_POINTS_PER_RIDER)
    }

    @Test
    fun `a flushed backlog cannot grow one rider's line without limit`() {
        // A phone that lost signal in the hills can deliver several minutes of
        // fixes in one second, and the window alone would not stop it.
        val backlog = (0 until 500).map { point(it.toLong(), minutesAgo = 1) }
        val trimmed = Breadcrumbs.trim(backlog, now)

        assertEquals(Breadcrumbs.MAX_POINTS_PER_RIDER, trimmed.size)
    }

    @Test
    fun `when the cap bites it is the newest points that are kept`() {
        val many = (0 until 200).map { point(it.toLong(), minutesAgo = (200 - it).toLong() / 10) }
        val trimmed = Breadcrumbs.trim(many, now)

        assertEquals(199L, trimmed.last().id)
        assertEquals(Breadcrumbs.MAX_POINTS_PER_RIDER, trimmed.size)
    }

    // --- one line per rider ------------------------------------------------

    @Test
    fun `each rider gets their own line`() {
        val points = listOf(
            point(1, userId = 7, minutesAgo = 5, lat = 18.0),
            point(2, userId = 7, minutesAgo = 4, lat = 18.1),
            point(3, userId = 9, minutesAgo = 5, lat = 19.0),
            point(4, userId = 9, minutesAgo = 4, lat = 19.1),
        )

        val lines = Breadcrumbs.byRider(points, now)

        assertEquals(setOf(7L, 9L), lines.keys)
        assertEquals(listOf(LatLng(18.0, 98.0), LatLng(18.1, 98.0)), lines.getValue(7))
    }

    @Test
    fun `a rider with one point is not given a line`() {
        // One point is not a line, and drawing it as a dot beside their pin
        // would be a second marker for the same rider in the same place.
        val points = listOf(point(1, userId = 7, minutesAgo = 5))

        assertTrue(Breadcrumbs.byRider(points, now).isEmpty())
    }

    @Test
    fun `a rider whose points have all aged out is not given a line`() {
        val points = listOf(
            point(1, userId = 7, minutesAgo = 200),
            point(2, userId = 7, minutesAgo = 180),
        )

        assertFalse(Breadcrumbs.byRider(points, now).containsKey(7))
    }

    // --- merging ------------------------------------------------------------

    @Test
    fun `a top-up is added to what is already held`() {
        val held = listOf(point(1, minutesAgo = 5), point(2, minutesAgo = 4))
        val added = listOf(point(3, minutesAgo = 1))

        assertEquals(listOf(1L, 2L, 3L), Breadcrumbs.merge(held, added, now).map { it.id })
    }

    @Test
    fun `a point delivered twice is stored once`() {
        // By id rather than by timestamp: two fixes can share a timestamp to
        // the millisecond when a phone flushes a backlog, and the row id is
        // what the server orders by to break exactly that tie.
        val held = listOf(point(1, minutesAgo = 5))
        val added = listOf(point(1, minutesAgo = 5), point(2, minutesAgo = 4))

        assertEquals(listOf(1L, 2L), Breadcrumbs.merge(held, added, now).map { it.id })
    }

    @Test
    fun `merging nothing still ages the tail out of the window`() {
        // The window moves whether or not the head grew, so a map left open
        // does not accumulate a line back to breakfast.
        val held = listOf(point(1, minutesAgo = 90), point(2, minutesAgo = 2))

        assertEquals(listOf(2L), Breadcrumbs.merge(held, emptyList(), now).map { it.id })
    }

    @Test
    fun `merging keeps each rider's points apart when capping`() {
        val held = (0 until 200).map { point(it.toLong(), userId = 1, minutesAgo = 1) }
        val added = listOf(point(9_000, userId = 2, minutesAgo = 1), point(9_001, userId = 2, minutesAgo = 1))

        val merged = Breadcrumbs.merge(held, added, now)

        // The busy rider is capped; the quiet one is not swept away with them.
        assertEquals(Breadcrumbs.MAX_POINTS_PER_RIDER, merged.count { it.userId == 1L })
        assertEquals(2, merged.count { it.userId == 2L })
    }

    @Test
    fun `the newest timestamp of nothing is nothing`() {
        assertNull(Breadcrumbs.newest(emptyList()))
        assertNull(
            Breadcrumbs.newest(
                listOf(TrailPoint(id = 1, userId = 1, lat = 18.0, lng = 98.0, recordedAt = null))
            )
        )
    }
}
