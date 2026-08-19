package app.ptrip.tracktrip.location

/**
 * Which fix the live readout should be showing, and how old it is.
 *
 * ## Why this is not `System.currentTimeMillis() - location.time`
 *
 * Those are two different clocks. `Location.getTime()` on a GPS fix comes from
 * the satellites; the phone's own wall clock is set by the network and
 * routinely runs a second or several away from it, in either direction. The
 * subtraction is therefore wrong by that much on every fix — which the speed
 * readout used to paper over by clamping the negative case to zero.
 *
 * `Location.getElapsedRealtimeNanos()` and `SystemClock.elapsedRealtimeNanos()`
 * are the *same* clock: monotonic, counted since boot, immune to the network
 * moving the wall clock under it. The subtraction is then exactly the age of
 * the fix, with nothing to clamp and nothing to guess.
 *
 * Kept as plain arithmetic on `Long`s so it can be tested off a device —
 * `android.location.Location` is stubbed in JVM unit tests and every getter on
 * it answers zero.
 */
object LiveFix {

    private const val NANOS_PER_MILLI = 1_000_000L

    /**
     * How old a fix is, in milliseconds, on the monotonic clock.
     *
     * Never negative: both readings come from the same counter, so the only
     * way round is a caller passing them the wrong way, and answering that
     * with a negative age would look like a fix from the future.
     */
    fun ageMs(nowElapsedRealtimeNanos: Long, fixElapsedRealtimeNanos: Long): Long =
        ((nowElapsedRealtimeNanos - fixElapsedRealtimeNanos) / NANOS_PER_MILLI)
            .coerceAtLeast(0L)

    /**
     * Whether an arriving fix should replace the one on screen.
     *
     * Only if it is genuinely newer. A GNSS engine that has been batching —
     * which is what the platform is free to do the moment an app asks for
     * updates less often than once a second — flushes its queue oldest-first
     * *after* a newer fix has already been delivered. A readout that took
     * whatever arrived last would then show the speed from several seconds
     * ago, which is exactly the symptom of a speedometer that reads low and
     * lags behind the bike.
     *
     * Null [shownElapsedRealtimeNanos] is the first fix of all, which is
     * always accepted.
     */
    fun isNewer(shownElapsedRealtimeNanos: Long?, arrivingElapsedRealtimeNanos: Long): Boolean {
        if (shownElapsedRealtimeNanos == null) return true
        return arrivingElapsedRealtimeNanos > shownElapsedRealtimeNanos
    }
}
