package app.ptrip.tracktrip.location

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The arithmetic of a sharing session's lifetime.
 *
 * Split out from the service so it can be tested without a device: the service
 * itself needs a real Android runtime, but *when to stop* is the part most
 * worth being sure about, and it is pure.
 */

/**
 * The server's expiry as epoch millis; null for an open-ended session.
 *
 * The server's own timestamp is used rather than "now plus the duration the
 * rider picked", so the phone and the server stop at the same moment instead
 * of at two clocks' idea of an hour. An unparseable value reads as expired
 * rather than as unlimited — the safe direction to be wrong in when the
 * subject is broadcasting somebody's location.
 */
fun parseExpiryMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * How long to wait before the next fix.
 *
 * Normally the fixed cadence, but never past the expiry: a session with four
 * minutes left should end in four minutes, not sit through a full interval and
 * report once more after it has lapsed.
 */
fun nextDelayMillis(nowMillis: Long, expiresAtMillis: Long?, intervalMillis: Long): Long {
    if (expiresAtMillis == null) return intervalMillis
    val remaining = expiresAtMillis - nowMillis
    return remaining.coerceIn(0L, intervalMillis)
}

/** Whether a session with this expiry has run out at [nowMillis]. */
fun hasExpired(nowMillis: Long, expiresAtMillis: Long?): Boolean =
    expiresAtMillis != null && nowMillis >= expiresAtMillis
