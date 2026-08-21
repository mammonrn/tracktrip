package app.ptrip.tracktrip.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The two dates a finished trip is worth labelling with.
 *
 * ## Why a finished trip stops showing "x min ago"
 *
 * Every member row carries the age of that rider's last position report, and
 * while a ride is on that is the most useful number on the screen: it is the
 * difference between a pin that has not moved because the rider has not moved
 * and one that has not moved because their phone lost signal ten minutes ago.
 *
 * On a trip that has *ended* the same number is noise dressed as information.
 * Nobody has reported since the trip closed, so the figure only counts up —
 * "Owner · 1206 min ago" on a ride that finished yesterday, a number that says
 * nothing except that time passes, and that grows a digit every few days until
 * it is the widest thing on the row.
 *
 * What a rider opening a finished trip actually wants off that line is *when*:
 * the day it ran. So on an ended trip the age is replaced, in the line it
 * already occupied, by the day the trip was created and the day it ended.
 *
 * ## Why the year is conditional
 *
 * "21 Aug – 21 Aug" is a whole date for a ride last weekend and a riddle for
 * one from two summers ago. The year is added when it is load-bearing and left
 * off when it is not: when the two dates fall in different years, and when
 * either falls outside the year the rider is reading in. That second rule is
 * what keeps an archive honest — a trip from 2024 read in 2026 says 2024,
 * without every trip from this month carrying a year nobody needed.
 *
 * Formatting is a pure function of the strings, the clock and the locale, so
 * the rules above are testable without a screen.
 */
object TripDates {

    /**
     * The days to label a trip with, or null when the server sent nothing
     * usable.
     *
     * Null is the signal to leave the row exactly as it was — a build talking
     * to a backend older than `created_at`, or a stamp this build cannot
     * parse, must not turn into a blank where a date should be.
     *
     * [ended] is null on its own when the trip has no end stamp. That is the
     * ordinary state of a running trip, and callers that only ask about
     * finished ones treat it as "created, and nothing more to say".
     */
    data class Span(val created: String, val ended: String?)

    fun span(
        createdAtIso: String?,
        endedAtIso: String?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): Span? {
        val created = dateAt(createdAtIso, zone) ?: return null
        val ended = dateAt(endedAtIso, zone)

        val thisYear = Instant.ofEpochMilli(nowMs).atZone(zone).year
        val withYear = created.year != thisYear ||
            (ended != null && (ended.year != thisYear || ended.year != created.year))

        // Built per call rather than held as a constant: the pattern depends
        // on the year rule above, and the locale is the app's current one,
        // which a rider can change from settings without the process
        // restarting.
        val format = DateTimeFormatter.ofPattern(if (withYear) "d MMM yyyy" else "d MMM", locale)

        return Span(created = format.format(created), ended = ended?.let(format::format))
    }

    private fun dateAt(iso: String?, zone: ZoneId) = iso?.takeIf { it.isNotBlank() }?.let {
        try {
            Instant.parse(it).atZone(zone).toLocalDate()
        } catch (e: DateTimeParseException) {
            // The same rule [app.ptrip.tracktrip.map.FixAge] follows: a
            // timestamp this build cannot read is said nothing about rather
            // than guessed at.
            null
        }
    }
}
