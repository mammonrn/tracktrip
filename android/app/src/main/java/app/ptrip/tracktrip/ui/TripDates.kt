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
 * the day it ran. So on an ended trip the age is replaced by the day the trip
 * was created and the day it ended.
 *
 * ## Two ways of writing a day
 *
 * [Style.NUMERIC] — "21/08/2026" — is what the member row uses. It is the same
 * eleven characters in every language and for every month, which is what lets
 * two of them share one line under a rider's name.
 *
 * [Style.DAY_MONTH] — "21 Aug" — is what a dialog with room uses, and it is
 * where the conditional year lives: the year is added when it is load-bearing
 * (the two dates in different years, or either outside the year the rider is
 * reading in) and left off when it is not. That is what keeps an archive
 * honest — a trip from 2024 read in 2026 says 2024, without every trip from
 * this month carrying a year nobody needed.
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

    /**
     * How a day is written out.
     *
     * Two, because the two places that show a date want different things from
     * it, and one of them is on a row that has no width to spare.
     */
    enum class Style {
        /**
         * "21 Aug", and "21 Aug 2024" when the year is load-bearing — the two
         * dates in different years, or either outside the year the rider is
         * reading in. Short, readable, and translated, which is what a line in
         * a dialog wants.
         */
        DAY_MONTH,

        /**
         * "21/08/2026". Always the full date, always the same eleven
         * characters, and the same eleven in every language.
         *
         * That fixed width is the point rather than a side effect: on a member
         * row this is drawn twice on one line, in the space a two-word phrase
         * used to occupy, and a format whose length depends on the month name
         * ("1 May" against "21 September") would fit in English and overflow in
         * Thai. The conditional year is dropped with it — an ended trip's card
         * is the one place a rider is reading history, and there is no longer a
         * width argument for leaving the year off.
         */
        NUMERIC,
    }

    fun span(
        createdAtIso: String?,
        endedAtIso: String?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        style: Style = Style.DAY_MONTH,
    ): Span? {
        val created = dateAt(createdAtIso, zone) ?: return null
        val ended = dateAt(endedAtIso, zone)

        val format = when (style) {
            Style.NUMERIC ->
                // Locale.ROOT, deliberately, where the other branch takes the
                // rider's own locale. There is no text in this pattern — only
                // digits and two slashes — so there is nothing for a locale to
                // translate, and passing one in would only invite a numbering
                // system or a calendar that renders 2026 as 2569.
                NUMERIC_FORMAT

            Style.DAY_MONTH -> {
                val thisYear = Instant.ofEpochMilli(nowMs).atZone(zone).year
                val withYear = created.year != thisYear ||
                    (ended != null && (ended.year != thisYear || ended.year != created.year))

                // Built per call rather than held as a constant: the pattern
                // depends on the year rule above, and the locale is the app's
                // current one, which a rider can change from settings without
                // the process restarting.
                DateTimeFormatter.ofPattern(if (withYear) "d MMM yyyy" else "d MMM", locale)
            }
        }

        return Span(created = format.format(created), ended = ended?.let(format::format))
    }

    /** Fixed, and shared: it depends on nothing a caller could vary. */
    private val NUMERIC_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

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
