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
 * the two ends of the trip are not the two ends it was last fetched for, and
 * otherwise only after a failure has had time to become worth retrying.
 *
 * The endpoints are the key rather than a "have we fetched yet" flag, because
 * the owner moving the finish must re-fetch and everything else must not.
 */
object RouteRequests {

    /** How long a failed attempt waits before another is allowed. */
    const val RETRY_AFTER_MS = 5 * 60 * 1000L

    /**
     * Whether to spend a request now.
     *
     * [wanted] is the pair of ends the trip currently has, or null when it has
     * fewer than two — nothing to route between, and nothing to ask.
     * [fetchedFor] is the pair the held route was fetched for. [retryAtMs] is
     * set only when the last attempt for [fetchedFor] failed, and is when
     * another may be tried.
     */
    fun shouldFetch(
        wanted: Pair<LatLng, LatLng>?,
        fetchedFor: Pair<LatLng, LatLng>?,
        retryAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (wanted == null) return false
        // Either end moved — including the first time, when nothing has been
        // fetched at all.
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
