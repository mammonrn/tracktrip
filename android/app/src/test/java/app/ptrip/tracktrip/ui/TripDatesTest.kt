package app.ptrip.tracktrip.ui

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The days a finished trip is labelled with, and when the year is one of them.
 *
 * A pure test over the rules rather than a screenshot of them, because the
 * rules are the part that can be wrong: an archive of rides all reading
 * "21 Aug – 22 Aug" with no year is the failure this replaces, and it is not
 * one anybody would notice from a single trip on screen.
 */
class TripDatesTest {

    /** Bangkok, because that is where the riders are and where the days fall. */
    private val bangkok = ZoneId.of("Asia/Bangkok")

    private val english = Locale.ENGLISH

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun span(created: String?, ended: String?, now: String) = TripDates.span(
        createdAtIso = created,
        endedAtIso = ended,
        nowMs = at(now),
        zone = bangkok,
        locale = english,
    )

    @Test
    fun `a trip that ran this year needs no year on it`() {
        val span = span("2026-08-21T02:10:00Z", "2026-08-21T11:40:00Z", "2026-08-22T04:00:00Z")

        assertEquals(TripDates.Span(created = "21 Aug", ended = "21 Aug"), span)
    }

    @Test
    fun `a ride that crossed midnight says both days`() {
        // Two dates that happen to be one ride. The card cannot show a time —
        // there is one line and it is already carrying a role — so what it can
        // honestly say is that the trip started on one day and ended on the
        // next.
        val span = span("2026-08-21T15:00:00Z", "2026-08-21T19:30:00Z", "2026-08-23T04:00:00Z")

        // 22:00 and 02:30 Bangkok time.
        assertEquals(TripDates.Span(created = "21 Aug", ended = "22 Aug"), span)
    }

    @Test
    fun `a trip from an earlier year says which year`() {
        // The archive case. "21 Aug" on a ride from two summers ago is a
        // riddle, and it is exactly the row a rider scrolls back to.
        val span = span("2024-08-21T02:00:00Z", "2024-08-21T11:00:00Z", "2026-08-22T04:00:00Z")

        assertEquals(TripDates.Span(created = "21 Aug 2024", ended = "21 Aug 2024"), span)
    }

    @Test
    fun `a trip that crossed new year says both years`() {
        val span = span("2025-12-31T14:00:00Z", "2026-01-01T02:00:00Z", "2026-08-22T04:00:00Z")

        assertEquals(TripDates.Span(created = "31 Dec 2025", ended = "1 Jan 2026"), span)
    }

    @Test
    fun `a trip with no end stamp is still worth dating`() {
        // `ended_at` is null on every running trip, and on a finished one from
        // a database old enough to predate the column. Either way the day it
        // was created is a true thing the card can say.
        val span = span("2026-08-21T02:00:00Z", null, "2026-08-22T04:00:00Z")

        assertEquals(TripDates.Span(created = "21 Aug", ended = null), span)
    }

    @Test
    fun `nothing usable means nothing said`() {
        // Null rather than a placeholder date, so the caller leaves the row
        // exactly as it was. The same rule FixAge follows for a timestamp it
        // cannot parse: a confident wrong date is worse than no date.
        assertNull(span(null, "2026-08-21T11:00:00Z", "2026-08-22T04:00:00Z"))
        assertNull(span("", null, "2026-08-22T04:00:00Z"))
        assertNull(span("last Tuesday", null, "2026-08-22T04:00:00Z"))
    }

    @Test
    fun `an unreadable end stamp does not take the start down with it`() {
        val span = span("2026-08-21T02:00:00Z", "not a date", "2026-08-22T04:00:00Z")

        assertEquals(TripDates.Span(created = "21 Aug", ended = null), span)
    }

    @Test
    fun `the month name follows the locale the rider is reading in`() {
        val thai = TripDates.span(
            createdAtIso = "2026-08-21T02:00:00Z",
            endedAtIso = "2026-08-21T11:00:00Z",
            nowMs = at("2026-08-22T04:00:00Z"),
            zone = bangkok,
            locale = Locale.forLanguageTag("th"),
        )

        // Not asserted character by character — that would be pinning the
        // JDK's CLDR data rather than this code. What matters is that the
        // locale reached the formatter at all.
        assertEquals(false, thai?.created?.contains("Aug"))
    }
}
