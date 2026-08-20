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
     * ## Ten seconds, and why that is not the same decision as before
     *
     * This was 45 seconds, chosen inside a thirty-to-sixty band on three
     * arguments. Two of them still hold and the third has changed underneath.
     *
     *  - **The map.** At 80 km/h a rider covers about a kilometre between
     *    45-second reports, and about 220 metres between ten-second ones. The
     *    marker slides either in a second and a half; the difference is
     *    whether a friend's pin is a street behind them or a village behind
     *    them when you glance at it.
     *  - **The rate limit.** The backend allows
     *    [BACKEND_MAX_POSTS_PER_MINUTE] posts a minute per rider. Ten seconds
     *    is 6 a minute — five times under the cap. That ceiling was raised
     *    from 10 to 30 in the same change, precisely so this cadence would
     *    keep the headroom the old one had rather than brushing a limit whose
     *    whole job is to catch a client stuck in a loop. Changing one without
     *    the other is what ReportCadenceTest exists to stop.
     *  - **The battery, which is the argument that changed.** The old note
     *    said "every report is a GPS acquisition", and at the time it was.
     *    Since the live speed feed landed, the map screen holds a continuous
     *    1 Hz location subscription for as long as it is composed — so while
     *    a rider is actually looking at the map, the receiver is *already*
     *    running flat out and a ten-second report costs one HTTP POST and
     *    exactly zero extra acquisitions.
     *
     *    What it does cost is the screen-off case: a phone in a tank bag now
     *    wakes the receiver 4.5 times as often. That is a real cost and it is
     *    not being pretended away. It is smaller than 4.5x in practice —
     *    ten-second cycles keep the GNSS engine warm, and a warm fix is a
     *    second or two against the cold search a 45-second gap can fall back
     *    into — but a long day in a pocket will show it.
     */
    const val INTERVAL_MS = 10_000L

    /**
     * How long to wait for a fix before giving up on this cycle.
     *
     * ## Why this stayed at 55 seconds when the interval went to 10
     *
     * It is longer than five intervals now, and that is correct rather than
     * an oversight. The budget is not what paces the reports —
     * [nextDelayMillis] is, and it makes a cycle take `max(interval,
     * elapsed)`, so a slow fix delays its own report and nothing after it and
     * reports can never bunch up. What the budget decides is only whether a
     * slow fix produces a report at all.
     *
     * A phone with a warm receiver — which, with the map open, is every phone
     * — answers in well under a second and reports every ten. A phone that has
     * been asleep in a tank bag takes thirty or forty seconds for its first
     * fix, gets one report for that, and is then on the ten-second beat like
     * everyone else. Shortening the budget to fit inside two intervals would
     * throw that first fix away and report nothing at all, which is the exact
     * bug the note below is about.
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
    const val BACKEND_MAX_POSTS_PER_MINUTE = 30

    /** Posts a minute this cadence produces, for one rider on one trip. */
    val postsPerMinute: Double get() = 60_000.0 / INTERVAL_MS
}
