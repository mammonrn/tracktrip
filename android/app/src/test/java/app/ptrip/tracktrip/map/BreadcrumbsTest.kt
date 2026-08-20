package app.ptrip.tracktrip.map

import app.ptrip.tracktrip.data.Trail
import app.ptrip.tracktrip.data.TrailPoint
import app.ptrip.tracktrip.data.toTrail
import app.ptrip.tracktrip.location.ReportCadence
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        val jumbled = listOf(point(3, minutesAgo = 1), point(1, minutesAgo = 12), point(2, minutesAgo = 6))

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
        // The whole window filled at the app's own reporting cadence. Derived
        // from ReportCadence rather than written down, because the two are the
        // thing that came apart: this file was sized against a 45-second
        // cadence, and at 10 seconds the same window is 4.5 times the points.
        val perWindow = Breadcrumbs.expectedPointsPerRider
        val ride = (0 until perWindow).map {
            point(it.toLong(), secondsAgo = it * (ReportCadence.INTERVAL_MS / 1000L))
        }

        assertEquals(90, perWindow)
        assertEquals(perWindow, Breadcrumbs.trim(ride, now).size)
        assertTrue(
            "a normal ride must never lose the far end of its own line",
            perWindow < Breadcrumbs.MAX_POINTS_PER_RIDER,
        )
    }

    // --- the window against the endpoint's ceiling --------------------------

    @Test
    fun `a full group's window fits in one request`() {
        // The bug this pins down: the history endpoint answers oldest-first
        // with a LIMIT, so a window that overflows its ceiling comes back as
        // the *start* of the window — a line that stops in the middle of a
        // road. At 45 seconds a 45-minute window was 480 points for eight
        // riders and fitted; at 10 seconds the same window is 2,160 and does
        // not. Fifteen minutes is what fits.
        assertTrue(
            "a group of ${Breadcrumbs.GROUP_SIZE_BUDGET} needs " +
                "${Breadcrumbs.expectedPointsPerGroup} points, server gives " +
                "${Breadcrumbs.SERVER_MAX_POINTS}",
            Breadcrumbs.expectedPointsPerGroup <= Breadcrumbs.SERVER_MAX_POINTS,
        )
    }

    @Test
    fun `the window the old cadence was sized for would no longer fit`() {
        // Kept as a worked example of why this file had to change with the
        // cadence, so a future change to either is measured rather than
        // assumed.
        val oldWindowMs = 45 * 60 * 1000L
        val atNewCadence =
            (oldWindowMs / ReportCadence.INTERVAL_MS).toInt() * Breadcrumbs.GROUP_SIZE_BUDGET

        assertTrue(atNewCadence > Breadcrumbs.SERVER_MAX_POINTS)
    }

    @Test
    fun `the fetch asks for everything the server will give`() {
        // Asking for less than the ceiling would be choosing to be truncated.
        assertEquals(Breadcrumbs.SERVER_MAX_POINTS, Breadcrumbs.FETCH_LIMIT)
    }

    @Test
    fun `the cap leaves room for a backlog without clipping a normal ride`() {
        val expected = Breadcrumbs.expectedPointsPerRider
        assertTrue(Breadcrumbs.MAX_POINTS_PER_RIDER > expected)
        assertTrue(
            "the cap should be roughly double the window, not marginally above it",
            Breadcrumbs.MAX_POINTS_PER_RIDER >= expected * 2,
        )
    }

    @Test
    fun `a top-up brings a handful of points, not a window`() {
        // What the 30-second refresh actually costs at the reporting cadence.
        val perTopUp = Breadcrumbs.REFRESH_MS / ReportCadence.INTERVAL_MS
        assertEquals(3L, perTopUp)
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
        // Three hundred points spread across the window — more than the cap,
        // all of them fresh enough to keep. The oldest are the ones to lose:
        // the near end of the line is the half a rider is looking at.
        val count = 300
        val spanSeconds = Breadcrumbs.WINDOW_MS / 1000L - 60L
        val many = (0 until count).map {
            point(it.toLong(), secondsAgo = spanSeconds - it * spanSeconds / count)
        }
        val trimmed = Breadcrumbs.trim(many, now)

        assertEquals(Breadcrumbs.MAX_POINTS_PER_RIDER, trimmed.size)
        assertEquals((count - 1).toLong(), trimmed.last().id)
        assertEquals((count - Breadcrumbs.MAX_POINTS_PER_RIDER).toLong(), trimmed.first().id)
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

    // --- chasing a truncated answer -----------------------------------------

    /** A stub endpoint that records what it was asked for. */
    private class FakeHistory(private val pages: List<Trail>) {
        val asked = mutableListOf<Pair<String, Int>>()
        suspend fun fetch(sinceIso: String, limit: Int): Trail {
            asked += sinceIso to limit
            return pages.getOrElse(asked.size - 1) { Trail(emptyList(), truncated = false) }
        }
    }

    @Test
    fun `an untruncated answer is one request`() = runTest {
        val history = FakeHistory(
            listOf(Trail(listOf(point(1, minutesAgo = 5), point(2, minutesAgo = 4)), truncated = false))
        )

        val collected = Breadcrumbs.collect(emptyList(), now, history::fetch)

        assertEquals(2, collected.size)
        assertEquals(1, history.asked.size)
        assertEquals(Breadcrumbs.sinceIso(now), history.asked[0].first)
        assertEquals(Breadcrumbs.FETCH_LIMIT, history.asked[0].second)
    }

    @Test
    fun `a truncated answer is chased from its newest point, not re-asked`() = runTest {
        // The bug this exists for: the endpoint answers oldest-first with a
        // LIMIT, so a truncated page is the *start* of the window. Accepting
        // it draws a line that stops in the middle of a road.
        val first = Trail(listOf(point(1, minutesAgo = 12), point(2, minutesAgo = 10)), truncated = true)
        val second = Trail(listOf(point(3, minutesAgo = 8), point(4, minutesAgo = 6)), truncated = true)
        val third = Trail(listOf(point(5, minutesAgo = 2)), truncated = false)
        val history = FakeHistory(listOf(first, second, third))

        val collected = Breadcrumbs.collect(emptyList(), now, history::fetch)

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), collected.map { it.id })
        assertEquals(3, history.asked.size)
        // Each round walks forward from the newest point the last one gave.
        assertEquals(point(2, minutesAgo = 10).recordedAt, history.asked[1].first)
        assertEquals(point(4, minutesAgo = 6).recordedAt, history.asked[2].first)
    }

    @Test
    fun `the chase is bounded rather than following a server that always truncates`() = runTest {
        val alwaysTruncated = (1..10).map {
            Trail(listOf(point(it.toLong(), minutesAgo = (12 - it).toLong())), truncated = true)
        }
        val history = FakeHistory(alwaysTruncated)

        Breadcrumbs.collect(emptyList(), now, history::fetch)

        assertEquals(Breadcrumbs.MAX_CATCH_UP_ROUNDS, history.asked.size)
    }

    @Test
    fun `a truncated page with no readable timestamp stops rather than looping`() = runTest {
        val unreadable = Trail(
            listOf(TrailPoint(id = 1, userId = 1, lat = 18.0, lng = 98.0, recordedAt = null)),
            truncated = true,
        )
        val history = FakeHistory(listOf(unreadable, unreadable, unreadable))

        Breadcrumbs.collect(emptyList(), now, history::fetch)

        // Asking again from the same place would be a loop with nothing to
        // walk to, so it takes what arrived and stops.
        assertEquals(1, history.asked.size)
    }

    @Test
    fun `a top-up starts from what is already held, not from the window`() = runTest {
        val held = listOf(point(1, minutesAgo = 10), point(2, minutesAgo = 5))
        val history = FakeHistory(listOf(Trail(listOf(point(3, minutesAgo = 1)), truncated = false)))

        val collected = Breadcrumbs.collect(held, now, history::fetch)

        assertEquals(listOf(1L, 2L, 3L), collected.map { it.id })
        assertEquals(point(2, minutesAgo = 5).recordedAt, history.asked[0].first)
    }

    @Test
    fun `whatever the fetch throws comes straight back out`() = runTest {
        var thrown: IllegalStateException? = null
        try {
            Breadcrumbs.collect(emptyList(), now) { _, _ -> throw IllegalStateException("offline") }
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertEquals("offline", thrown?.message)
    }

    // --- the whole path, from a response the server actually produces --------

    @Test
    fun `a real history response becomes a line`() {
        // The end-to-end check that the trail's data path works: the exact
        // JSON shape `GET /trips/:id/positions/history` returns, through the
        // parser, the merge and the grouping, to coordinates a map can draw.
        // Written after a real-device report that the trail never appeared —
        // this is the half of the chain that was proven sound.
        val cadenceMs = ReportCadence.INTERVAL_MS
        val rows = (0 until 10).joinToString(",") { i ->
            val at = Instant.ofEpochMilli(now - (9 - i) * cadenceMs).toString()
            """{"id":${i + 1},"user_id":7,"lat":${18.0 + i * 0.001},"lng":98.0,"recorded_at":"$at"}"""
        }
        val body = """{"trip_id":1,"truncated":false,"points":[$rows]}"""

        val trail = JSONObject(body).toTrail()
        assertEquals(10, trail.points.size)

        val lines = Breadcrumbs.byRider(Breadcrumbs.merge(emptyList(), trail.points, now), now)
        assertEquals(setOf(7L), lines.keys)
        assertEquals(10, lines.getValue(7).size)
        assertEquals(LatLng(18.0, 98.0), lines.getValue(7).first())
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

/**
 * What the trail says for itself, and why every state has to say something.
 *
 * The toggle was reported as dead twice. The first time the trip genuinely had
 * no history and the map said nothing, which the "no trail yet" row fixed. The
 * second time the *fetch failed* and the map said nothing — the same symptom
 * arriving through the one door that row did not cover, because a swallowed
 * error set no state at all.
 *
 * So this pins the rule rather than the wording: the only two states allowed
 * to be silent are the two that speak for themselves.
 */
class TrailStatusTest {

    @Test
    fun `only off and drawn are allowed to say nothing`() {
        val silent = setOf(TrailStatus.OFF, TrailStatus.DRAWN)

        // Written as a sweep over the enum rather than four assertions, so a
        // state added later without a message fails here rather than on a
        // phone that has just been handed a dead-looking button.
        TrailStatus.entries.forEach { status ->
            val speaks = status !in silent
            assertEquals(
                "$status must ${if (speaks) "say something" else "stay silent"}",
                speaks,
                status in setOf(TrailStatus.LOADING, TrailStatus.EMPTY, TrailStatus.FAILED),
            )
        }
    }

    @Test
    fun `nothing recorded and could not ask are different states`() {
        // The distinction the whole value exists for: one is "somebody has to
        // ride", the other is "the server did not answer", and they look
        // identical on a map that draws no lines.
        assertNotEquals(TrailStatus.EMPTY, TrailStatus.FAILED)
    }

    @Test
    fun `a press always has a state that is not the one before it`() {
        // Off to on is LOADING, never straight to EMPTY or DRAWN: the fetch
        // has not run, and a press that changes no pixel is the thing that
        // reads as a broken button.
        assertNotEquals(TrailStatus.OFF, TrailStatus.LOADING)
    }
}
