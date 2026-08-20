package app.ptrip.tracktrip.data

/**
 * How often the map polls, and how long a dropped socket waits before trying
 * again.
 *
 * ## The socket is a shortcut, never the record
 *
 * Positions are written by `POST /trips/:id/positions` and read by
 * `GET /trips/:id/positions`, exactly as they were before the socket existed.
 * What the socket adds is that a fix stored on the server reaches a watching
 * phone at once instead of on that phone's next poll.
 *
 * So the poll never stops. It slows down while the socket is connected —
 * because the socket is doing the work, and a second copy of the same data
 * every twenty seconds is waste — and speeds back up the moment the socket is
 * gone. A rider who loses their connection in a valley loses immediacy and
 * nothing else; positions keep arriving, a poll behind, and the socket
 * reconnects underneath them.
 *
 * That is why this file has no Android in it: the two cadences and the backoff
 * are the whole behaviour, and they can be held to their promises on a laptop.
 */
object LiveCadence {

    /**
     * The poll when the socket is not carrying updates.
     *
     * ## Why this stayed at twenty seconds when reporting went to ten
     *
     * Reporting used to be every 45 seconds, so this poll was never the
     * limiting factor: a fix was on screen within twenty seconds of the server
     * having it, and the server only had a new one every forty-five. At a
     * ten-second cadence that reverses — the poll is now what a rider waits
     * for, on this path.
     *
     * It stays at twenty anyway, for two reasons.
     *
     * The first is that this is not the path. The socket delivers every stored
     * fix the moment it is stored, so a rider with a connection sees the whole
     * benefit of the faster cadence without this number moving at all. This is
     * the fallback for a socket that has dropped, and on that fallback the
     * worst-case staleness has just improved from about a minute to twenty
     * seconds without anybody changing anything.
     *
     * The second is what halving it would cost: every viewer's read traffic
     * doubles, permanently, to take at most ten seconds off a path that is
     * already better than it was yesterday. That is the wrong trade to make on
     * a phone that is also now posting three times as often.
     *
     * Deliberately not equal to the reporting period either, so the two timers
     * do not drift into lockstep.
     */
    const val POLL_MS = 20_000L

    /**
     * The poll while the socket is connected.
     *
     * Not zero. A socket delivers what happens *while it is listening*; it
     * says nothing about what it missed during the second it was reconnecting,
     * and nothing about a member who joined the trip, left it, or stopped
     * sharing. This is the reconciliation pass that makes those right, and at
     * two minutes it costs a rider about thirty requests over a long day's
     * ride.
     */
    const val LIVE_POLL_MS = 120_000L

    /** Which of the two applies right now. */
    fun pollIntervalMs(socketConnected: Boolean): Long =
        if (socketConnected) LIVE_POLL_MS else POLL_MS

    /** The first retry after a socket drops. Short: most drops are a blip. */
    const val RETRY_MIN_MS = 1_000L

    /**
     * The longest wait between retries.
     *
     * Thirty seconds, not minutes. A phone coming out of a tunnel should be
     * live again within a reasonable stretch of road, and while it is not, the
     * fast poll is already keeping the map fed — so the retry can afford to be
     * patient without the rider paying for the patience.
     */
    const val RETRY_MAX_MS = 30_000L

    /**
     * How long to wait before the [attempt]-th reconnection, counting from 1.
     *
     * Doubling, capped. Without the cap a phone that spent an hour out of
     * signal would come back and then sit for another hour before noticing;
     * without the doubling, a server that is genuinely down gets hammered by
     * every phone on the ride at once.
     */
    fun retryDelayMs(attempt: Int): Long {
        if (attempt <= 1) return RETRY_MIN_MS
        // Shifted rather than multiplied so a large attempt count cannot
        // overflow into a negative delay — which would be a retry storm, the
        // exact thing the backoff exists to prevent.
        val doublings = (attempt - 1).coerceAtMost(MAX_DOUBLINGS)
        return (RETRY_MIN_MS shl doublings).coerceAtMost(RETRY_MAX_MS)
    }

    /** Enough to reach [RETRY_MAX_MS] from [RETRY_MIN_MS], and no more. */
    private const val MAX_DOUBLINGS = 16
}
