package app.ptrip.tracktrip.location

/**
 * How often a sharing phone reports where it is, and the constraints that
 * number has to satisfy.
 *
 * Kept apart from the service — which cannot be loaded without an Android
 * runtime — so the arithmetic that has to stay true can be checked on a
 * laptop. The one thing that must never drift silently is the relationship
 * between this cadence and the backend's rate limit: they live in two
 * languages and two repositories' worth of reasoning, and the only thing
 * holding them together is a test that fails when they part company.
 */
object ReportCadence {

    /**
     * The reporting period, in milliseconds.
     *
     * Forty-five seconds, chosen inside the thirty-to-sixty band this was
     * asked to land in, weighing three things against each other:
     *
     *  - **The rate limit.** The backend allows [BACKEND_MAX_POSTS_PER_MINUTE]
     *    posts a minute per rider. 45s is 1.33 — seven and a half times under
     *    the cap, so the ceiling keeps doing its real job of catching a client
     *    stuck in a retry loop instead of sitting close enough to trip on one
     *    slow cycle.
     *  - **The map.** At 80 km/h a rider covers about a kilometre between
     *    reports. The marker slides that in a second and a half and reads as
     *    travel; the thirteen kilometres a ten-minute cadence produced read as
     *    teleporting, which is what started this.
     *  - **The battery.** Every report is a GPS acquisition. At 45s the
     *    receiver stays warm, so each costs a second or two rather than a cold
     *    search — going to 15s would triple the wakeups to save a third of the
     *    distance.
     */
    const val INTERVAL_MS = 45_000L

    /**
     * How long to wait for a fix before giving up on this cycle.
     *
     * Comfortably inside one interval. At the old ten-minute cadence a
     * sixty-second wait was rounding error; at forty-five seconds it would be
     * most of the cycle, and one fix taken under a bridge would push every
     * later report off its beat. A warm receiver answers in a second or two,
     * so this only bites when the sky is genuinely blocked — and then skipping
     * to the next cycle beats blocking on it.
     */
    const val FIX_TIMEOUT_MS = 25_000L

    /**
     * `POSITION_RATE_LIMIT.max` in `src/routes/positions.js`. Duplicated here
     * because a constant in a Node file cannot be imported into Kotlin — the
     * test below is what keeps the copy honest.
     */
    const val BACKEND_MAX_POSTS_PER_MINUTE = 10

    /** Posts a minute this cadence produces, for one rider on one trip. */
    val postsPerMinute: Double get() = 60_000.0 / INTERVAL_MS
}
