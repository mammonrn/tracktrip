package app.ptrip.tracktrip.map

import app.ptrip.tracktrip.data.Trail
import app.ptrip.tracktrip.data.TrailPoint
import app.ptrip.tracktrip.location.ReportCadence
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The trail: where everybody has just been, drawn behind them.
 *
 * `position_history` has held one row per accepted fix since PR #37 and
 * `GET /trips/:id/positions/history` has served them since; nothing ever drew
 * them. This is the part that decides *which* of them to draw, kept away from
 * the map so the decisions can be argued with in a test.
 *
 * ## How far back, and why not further
 *
 * Four things push against each other here.
 *
 *  - **Readability.** A whole day's ride drawn behind eight riders is not a
 *    map any more, it is a ball of wool. The trail exists to answer "which way
 *    did they come, and did they take the turn?" — a question about the last
 *    few minutes, not about breakfast.
 *  - **The endpoint's shape.** The history query is `ORDER BY recorded_at ASC
 *    ... LIMIT`, so a request without `since` returns the *oldest* points, not
 *    the newest. Asking for a window is therefore not an optimisation, it is
 *    the only way to get the recent tail at all — see [sinceIso].
 *  - **The endpoint's ceiling.** That same query is capped at
 *    [SERVER_MAX_POINTS] rows. A window wide enough to overflow it does not
 *    fail — it silently returns the *oldest* part of the window and a
 *    `truncated` flag, which draws a line that stops in the middle of a road.
 *    The window has to be chosen so a realistic group fits underneath.
 *  - **What a phone can draw.** [WINDOW_MS] at the app's reporting cadence is
 *    [expectedPointsPerRider] points each. That is a polyline, not a problem.
 *
 * ## The cadence moved, so these moved with it
 *
 * These were 45 minutes and 120 points, sized against a 45-second reporting
 * cadence: 60 points per rider, and a group of eight comfortably under the
 * endpoint's 500-row default. The cadence is 10 seconds now
 * ([ReportCadence.INTERVAL_MS]), which is 4.5 times as many points for the
 * same wall-clock window — 270 per rider, and over two thousand for a group of
 * eight. Both ceilings would have been hit at once: the per-rider cap would
 * have quietly clipped the trail to its last twenty minutes, and the endpoint
 * would have returned the *start* of the window and stopped.
 *
 * So the window is fifteen minutes now. That is not a downgrade: at ten
 * seconds a fifteen-minute trail is ninety points where the old
 * forty-five-minute one was sixty, so the line is both denser and more
 * road-shaped than it was, and fifteen minutes is still around fifteen
 * kilometres of riding.
 *
 * [MAX_POINTS_PER_RIDER] is the guard for the case the cadence does not
 * describe: a phone that lost signal in the hills and flushed a backlog of
 * fixes at once, which can put several minutes of points into one second.
 */
object Breadcrumbs {

    /** How far back a trail reaches. */
    const val WINDOW_MS = 15 * 60 * 1000L

    /**
     * `HISTORY_MAX_LIMIT` in `src/routes/positions.js`. Duplicated here for
     * the same reason [ReportCadence.BACKEND_MAX_POSTS_PER_MINUTE] duplicates
     * the rate limit: a constant in a Node file cannot be imported into
     * Kotlin, and the test beside this one is what keeps the copy honest.
     */
    const val SERVER_MAX_POINTS = 1000

    /**
     * The biggest group this window is sized to hold in one request.
     *
     * Not a limit the app enforces — a trip may have more riders, and the
     * catch-up loop in the view model handles a response the server had to
     * truncate. It is the number the window was *chosen against*, so that the
     * ordinary case is one request and the loop is an exception rather than
     * the rule.
     */
    const val GROUP_SIZE_BUDGET = 10

    /**
     * The most points one rider's line is drawn from.
     *
     * A little over twice [expectedPointsPerRider], so an ordinary ride is
     * never trimmed and a flushed backlog still cannot grow the line without
     * limit. When it does bite, the *newest* points are the ones kept — the
     * near end of the line is the half a rider is looking at.
     */
    const val MAX_POINTS_PER_RIDER = 200

    /**
     * How often the trail is topped up while the map is open.
     *
     * Slower than the position poll and much slower than the socket: a
     * breadcrumb that arrives thirty seconds late is a line thirty seconds
     * short at its near end, which nobody can see, and the pins are already
     * live. Each top-up asks only for what has been added since the last point
     * already held, so at the ten-second cadence the usual answer is three
     * rows per rider.
     */
    const val REFRESH_MS = 30_000L

    /** How many points one rider puts in the window at the reporting cadence. */
    val expectedPointsPerRider: Int get() = (WINDOW_MS / ReportCadence.INTERVAL_MS).toInt()

    /** How many the whole group puts there, at the size the window is sized for. */
    val expectedPointsPerGroup: Int get() = expectedPointsPerRider * GROUP_SIZE_BUDGET

    /**
     * How many points to ask for in one request.
     *
     * The server's own ceiling, always. Asking for less would mean choosing to
     * be truncated; the server clamps anything larger, so this is simply "as
     * much as you will give me".
     */
    const val FETCH_LIMIT = SERVER_MAX_POINTS

    /**
     * How many times a truncated response may be chased before giving up.
     *
     * A truncated answer is the *oldest* part of the window, so the fix is to
     * ask again from the newest point received and keep going. Bounded because
     * this runs on a phone: three rounds is thirty riders' worth of window,
     * and past that a partial trail is a better outcome than a request loop.
     */
    const val MAX_CATCH_UP_ROUNDS = 3

    /**
     * The `since` parameter for a first fetch: the start of the window.
     *
     * ISO-8601 in UTC, which is what the endpoint normalises to and compares
     * against. Without it the endpoint answers with the oldest points of the
     * ride, which is the opposite of what a trail behind a moving pin needs.
     */
    fun sinceIso(nowMs: Long): String = Instant.ofEpochMilli(nowMs - WINDOW_MS).toString()

    /**
     * The `since` parameter for a top-up: just after the newest point already
     * held, or the start of the window when nothing is held yet.
     *
     * The endpoint's filter is `recorded_at > since`, so passing the newest
     * timestamp asks for strictly newer rows and never re-reads one.
     */
    fun sinceIso(nowMs: Long, newestHeld: String?): String {
        val floor = sinceIso(nowMs)
        val held = newestHeld ?: return floor
        // A held point older than the window is about to be dropped anyway, so
        // asking from it would re-fetch rows destined for the bin.
        return if (held > floor) held else floor
    }

    /**
     * One rider's line: their points, oldest first, inside the window and
     * inside the cap.
     *
     * Points with an unreadable timestamp are dropped rather than kept: the
     * window is the whole point, and a point that cannot be dated cannot be
     * put inside or outside it.
     */
    fun trim(points: List<TrailPoint>, nowMs: Long): List<TrailPoint> {
        val floor = nowMs - WINDOW_MS
        val fresh = points
            .filter { point -> point.recordedAt?.let { millisOf(it) }?.let { it >= floor } == true }
            .sortedBy { it.recordedAt }
        return if (fresh.size <= MAX_POINTS_PER_RIDER) {
            fresh
        } else {
            fresh.takeLast(MAX_POINTS_PER_RIDER)
        }
    }

    /**
     * Every rider's line, keyed by rider, ready to draw.
     *
     * A rider with fewer than two points inside the window is left out
     * entirely: one point is not a line, and drawing it as a dot beside their
     * pin would be a second marker for the same rider in the same place.
     */
    fun byRider(points: List<TrailPoint>, nowMs: Long): Map<Long, List<LatLng>> =
        points
            .groupBy { it.userId }
            .mapValues { (_, theirs) -> trim(theirs, nowMs) }
            .filterValues { it.size >= 2 }
            .mapValues { (_, theirs) -> theirs.map { LatLng(it.lat, it.lng) } }

    /**
     * Everything in the window, chasing a truncated answer rather than
     * accepting it.
     *
     * ## Why a loop and not a request
     *
     * The endpoint answers oldest-first with a `LIMIT` and says `truncated`
     * when it had more to give. So a response it had to cut is the *start* of
     * the window, not the end — the near half of every rider's line, the half
     * they are actually looking at, silently missing. A map that drew it would
     * show a trail stopping in the middle of a road, which is precisely the
     * thing the `truncated` flag was added to make impossible.
     *
     * Asking again from the newest point received walks forward through it.
     * [WINDOW_MS] is chosen so that a realistic group never needs a second
     * round — this is the exception, not the mechanism.
     *
     * [fetch] is a parameter rather than an API call so this can be tested
     * against a stub: the behaviour worth pinning down is *how many times it
     * asks and what it asks for*, which a test that needed a server could not
     * answer. Whatever [fetch] throws comes straight back out; deciding what a
     * failed trail fetch means belongs to the caller.
     */
    suspend fun collect(
        held: List<TrailPoint>,
        nowMs: Long,
        fetch: suspend (sinceIso: String, limit: Int) -> Trail,
    ): List<TrailPoint> {
        var since = sinceIso(nowMs, newest(held))
        var collected = held

        for (round in 0 until MAX_CATCH_UP_ROUNDS) {
            val trail = fetch(since, FETCH_LIMIT)
            collected = merge(collected, trail.points, nowMs)
            if (!trail.truncated) break
            // A truncated page with no readable timestamp in it leaves nowhere
            // to walk to, and asking again from the same place would be a
            // loop. Take what arrived and stop.
            since = newest(trail.points) ?: break
        }

        return collected
    }

    /** The newest timestamp in a set of points, or null when there is none. */
    fun newest(points: List<TrailPoint>): String? = points.mapNotNull { it.recordedAt }.maxOrNull()

    /**
     * Merges a top-up into what is already held, dropping duplicates by id.
     *
     * By id rather than by timestamp: two fixes can share a timestamp to the
     * millisecond when a phone flushes a backlog, and the row id is what the
     * server orders by to break exactly that tie.
     */
    fun merge(held: List<TrailPoint>, added: List<TrailPoint>, nowMs: Long): List<TrailPoint> {
        if (added.isEmpty()) return trimAll(held, nowMs)
        val byId = LinkedHashMap<Long, TrailPoint>(held.size + added.size)
        held.forEach { byId[it.id] = it }
        added.forEach { byId[it.id] = it }
        return trimAll(byId.values.toList(), nowMs)
    }

    /** Every rider's points trimmed to the window and the cap, in one list. */
    private fun trimAll(points: List<TrailPoint>, nowMs: Long): List<TrailPoint> =
        points.groupBy { it.userId }.values.flatMap { trim(it, nowMs) }

    private fun millisOf(iso: String): Long? = try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * What the trail has to say for itself.
 *
 * ## Why a value and not a boolean
 *
 * There was a boolean — `trailsEmpty` — and it covered one of the four things
 * the trail can be doing. That left a real hole: a fetch that *failed* set
 * nothing at all, so the map drew no lines and said nothing about why, which
 * from the rider's side is a toggle that does not work. It is the exact
 * complaint the "no trail yet" message was added to answer, arriving through
 * the one door that message did not cover.
 *
 * So every state says something now, and the two that look identical on screen
 * — nothing recorded yet, and could not ask — are told apart, because they
 * have completely different fixes: one is "somebody has to ride", the other is
 * "the server did not answer".
 */
enum class TrailStatus {
    /** Switched off. The grey icon is the whole message. */
    OFF,

    /**
     * On, and the first answer has not arrived.
     *
     * Only ever entered when there is nothing drawn yet, so a top-up on a
     * trail already on screen does not flicker a message every thirty seconds.
     */
    LOADING,

    /** On, fetched, and there are lines. The lines are the message. */
    DRAWN,

    /**
     * On, fetched, and this trip has no history in the window.
     *
     * Nothing is written until somebody actually shares their position, so
     * this is where a rider testing the map lands — and it looks exactly like
     * a broken control unless the screen says so.
     */
    EMPTY,

    /** On, and the last fetch did not answer. Says so rather than nothing. */
    FAILED,
}
