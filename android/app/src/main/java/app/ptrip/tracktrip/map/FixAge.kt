package app.ptrip.tracktrip.map

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * How old a rider's last reported position is.
 *
 * ## Why the screen needs this at all
 *
 * A phone reports its position once every ten minutes — deliberately, it is
 * what keeps a battery alive for a day's ride. The map polls far more often
 * than that, so most refreshes bring back exactly the same coordinates, and a
 * pin that has not moved for three minutes is the *expected* picture rather
 * than a broken one.
 *
 * Nothing on the screen said so. A rider watching their own pin sit still
 * while they rode had no way to tell "reported four minutes ago, next one due
 * in six" from "this app has stopped working" — and reasonably concluded the
 * second. Showing the age of the fix is what makes the difference legible; it
 * is cheaper and more honest than pretending to a freshness the cadence does
 * not provide.
 */
object FixAge {

    /**
     * Whole minutes between [recordedAtIso] and [nowMs], or null when there is
     * no usable timestamp.
     *
     * Null covers a rider who has never reported and a timestamp this build
     * cannot parse; both mean "say nothing" rather than "say zero", because a
     * confident "just now" against an unknown time would be a lie.
     *
     * A fix stamped slightly in the future — the device clock and the server's
     * disagreeing by a second or two — reads as 0 rather than negative.
     */
    fun minutesAgo(recordedAtIso: String?, nowMs: Long): Long? {
        val recordedMs = epochMillis(recordedAtIso) ?: return null
        val elapsed = nowMs - recordedMs
        if (elapsed < 0) return 0
        return elapsed / 60_000
    }

    private fun epochMillis(iso: String?): Long? {
        val text = iso?.takeIf { it.isNotBlank() } ?: return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
