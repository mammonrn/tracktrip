package app.ptrip.tracktrip.map

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * How old a rider's last reported position is.
 *
 * ## Why the screen needs this at all
 *
 * A phone reports its position on a fixed cadence, not continuously, so the
 * map can be a cycle or two behind what a rider is actually doing — and if
 * their phone is out of signal, considerably more. A pin that has not moved
 * for a few minutes is usually a report that has not landed yet rather than a
 * broken screen.
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

/**
 * Whole minutes since this phone last had a report accepted.
 *
 * The service has recorded this since it was written and nothing has ever
 * shown it, which is why a ride where positions stopped landing was
 * indistinguishable, from the rider's side, from one where they landed and the
 * screen failed to draw them. The two need completely different fixes, and
 * guessing between them cost a ride each time.
 *
 * Null when this phone is not sharing, or has not yet had a report accepted —
 * in both cases there is no number, and inventing a zero would claim a
 * successful report that never happened.
 */
fun FixAge.reportAgeMinutes(lastReportedAtMillis: Long?, nowMs: Long): Long? {
    if (lastReportedAtMillis == null) return null
    // Clamped for the same reason every other age in this app is: the two
    // clocks involved need not agree, and a negative number on screen is
    // nonsense where "just now" is the truth.
    return ((nowMs - lastReportedAtMillis).coerceAtLeast(0L)) / 60_000L
}
