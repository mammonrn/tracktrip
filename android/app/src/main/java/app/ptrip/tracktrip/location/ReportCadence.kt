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
     * ## This was 25 seconds, and 25 seconds was wrong
     *
     * It was shortened from sixty when the cadence went from ten minutes to
     * forty-five seconds, out of a worry that a slow fix would push every
     * later report off its beat. That worry was already answered, in the same
     * change, by [nextDelayMillis] subtracting how long the
     * cycle took — so a fix that takes fifty seconds delays *that* report and
     * nothing after it. Shortening the budget as well did not protect the
     * cadence; it only threw away fixes.
     *
     * And it threw away the ones that matter. A warm receiver answers in a
     * second or two — those were never at risk. The fix that needs thirty or
     * forty seconds is the cold one: a phone that has been in a tank bag with
     * the screen off, which is precisely the phone this service exists for. At
     * 25 seconds those cycles timed out and reported **nothing**, silently, and
     * a rider's pin and speed sat unchanged on everybody's map while their
     * phone insisted it was sharing.
     *
     * Fifty-five seconds: enough for a cold acquisition outdoors, and still
     * short enough that a fix which is never coming does not hold a cycle past
     * the next one.
     */
    const val FIX_TIMEOUT_MS = 55_000L

    /**
     * `POSITION_RATE_LIMIT.max` in `src/routes/positions.js`. Duplicated here
     * because a constant in a Node file cannot be imported into Kotlin — the
     * test below is what keeps the copy honest.
     */
    const val BACKEND_MAX_POSTS_PER_MINUTE = 10

    /** Posts a minute this cadence produces, for one rider on one trip. */
    val postsPerMinute: Double get() = 60_000.0 / INTERVAL_MS
}
