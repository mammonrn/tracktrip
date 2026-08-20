package app.ptrip.tracktrip.map

import app.ptrip.tracktrip.data.TrailPoint
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
 * Three things push against each other here.
 *
 *  - **Readability.** A whole day's ride drawn behind eight riders is not a
 *    map any more, it is a ball of wool. The trail exists to answer "which way
 *    did they come, and did they take the turn?" — a question about the last
 *    few minutes, not about breakfast.
 *  - **The endpoint's shape.** The history query is `ORDER BY recorded_at ASC
 *    ... LIMIT`, so a request without `since` returns the *oldest* points, not
 *    the newest. Asking for "the last 45 minutes" is therefore not an
 *    optimisation, it is the only way to get the recent tail at all — see
 *    [sinceIso].
 *  - **What a phone can draw.** Forty-five minutes at the app's 45-second
 *    reporting cadence is sixty points per rider, or about five hundred for a
 *    full group. That is a polyline, not a problem.
 *
 * [MAX_POINTS_PER_RIDER] is the guard for the case the cadence does not
 * describe: a phone that lost signal in the hills and flushed a backlog of
 * fixes at once, which can put several minutes of points into one second.
 */
object Breadcrumbs {

    /** How far back a trail reaches. */
    const val WINDOW_MS = 45 * 60 * 1000L

    /**
     * The most points one rider's line is drawn from.
     *
     * Sixty would cover the window at the reporting cadence; this is double
     * that, so an ordinary ride is never trimmed and a flushed backlog still
     * cannot grow the line without limit. When it does bite, the *newest*
     * points are the ones kept — the near end of the line is the half a rider
     * is looking at.
     */
    const val MAX_POINTS_PER_RIDER = 120

    /**
     * How often the trail is topped up while the map is open.
     *
     * Slower than the position poll and much slower than the socket: a
     * breadcrumb that arrives thirty seconds late is a line thirty seconds
     * short at its near end, which nobody can see, and the pins are already
     * live. Each top-up asks only for what has been added since the last point
     * already held, so the usual answer is one or two rows per rider.
     */
    const val REFRESH_MS = 30_000L

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
