package app.ptrip.tracktrip.map

/**
 * When asking for a road route is worth a request, and when it is not.
 *
 * ## Why this is a rule and not an `if`
 *
 * The map polls every twenty seconds. Routing runs on the same LocationIQ free
 * tier as place search — 5,000 requests a day for the whole server, shared by
 * every rider on it — and a route asked for on every poll would be about 180
 * requests an hour *per rider watching a map*. Four riders on one trip would
 * empty the day's budget before lunch, and the thing they emptied it on is a
 * line between two coordinates that had not moved.
 *
 * So the rule is written down where it can be held to: a route is fetched when
 * the route the trip describes is not the one it was last fetched for, and
 * otherwise only after a failure has had time to become worth retrying.
 *
 * The route's own coordinates are the key rather than a "have we fetched yet"
 * flag, because the owner moving the finish — or adding a stop — must re-fetch
 * and everything else must not.
 */
object RouteRequests {

    /** How long a failed attempt waits before another is allowed. */
    const val RETRY_AFTER_MS = 5 * 60 * 1000L

    /**
     * Whether to spend a request now.
     *
     * [wanted] is the route the trip currently describes — see [RoutePlan] —
     * or null when there is nothing to route between and so nothing to ask.
     * [fetchedFor] is what the held route was fetched for. [retryAtMs] is set
     * only when the last attempt for [fetchedFor] failed, and is when another
     * may be tried.
     *
     * Generic in what a route *is*, because this rule never cared: it is about
     * two descriptions of a route being equal or not. That is what let stops
     * join the key without touching a line of the rule — a [RoutePlan] whose
     * `via` list grew is simply not equal to the one already fetched, so the
     * road is re-asked for exactly once, which is the bug this rule would
     * otherwise have hidden.
     */
    fun <T> shouldFetch(
        wanted: T?,
        fetchedFor: T?,
        retryAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (wanted == null) return false
        // Something about the route moved — including the first time, when
        // nothing has been fetched at all.
        if (wanted != fetchedFor) return true
        // Same two ends, and the last attempt succeeded. Nothing to ask.
        val retryAt = retryAtMs ?: return false
        // Same two ends, last attempt failed. A failure must not be permanent
        // — a phone in a valley when the map opened would otherwise have no
        // route for the rest of the ride — but it must not retry on every poll
        // either, because a server with no key answers 503 for ever.
        return nowMs >= retryAt
    }
}
