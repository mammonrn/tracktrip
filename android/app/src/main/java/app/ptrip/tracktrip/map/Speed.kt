package app.ptrip.tracktrip.map

import kotlin.math.roundToInt

/**
 * Speeds, in the one unit riders read them in.
 *
 * ## Metres per second on the wire, km/h on the screen
 *
 * Android's `Location.getSpeed()` is metres per second, so that is what the
 * phone reports and what `member_positions.speed` stores — converting before
 * sending would put a unit into the database that nothing else in the system
 * uses, and the first client that forgot would be silently 3.6× out.
 *
 * The conversion therefore happens once, here, at the point of display.
 */
object Speed {

    private const val MPS_TO_KMH = 3.6

    /**
     * A stored speed in km/h, rounded, or null when there is nothing to show.
     *
     * Null covers a rider whose phone never sent a speed at all — an older
     * build, or a fix from a provider that had none. That is a different
     * thing from a rider stopped at a red light, who reports a real 0.
     */
    fun kmh(metresPerSecond: Double?): Int? {
        if (metresPerSecond == null || !metresPerSecond.isFinite() || metresPerSecond < 0) {
            return null
        }
        return (metresPerSecond * MPS_TO_KMH).roundToInt()
    }

    /**
     * How old this phone's own fix may be before its speed stops being worth
     * showing.
     *
     * A fix carries the speed the rider was doing when it was taken, and
     * showing that as "now" is worse than showing nothing.
     *
     * Twelve seconds, down from two minutes. Two minutes was set when the top
     * bar read whatever fix the device happened to be holding — a cache the
     * sharing service refreshed once a reporting cycle — and at that cadence
     * anything tighter would have shown a dash most of the time. The readout
     * has been on a live 1 Hz feed since; against that, twelve seconds is a
     * dozen missed fixes in a row, which is a phone in a tunnel and not a
     * phone doing 60. Anything longer is the readout quietly holding a stale
     * number, which is precisely the "reads behind the bike" complaint.
     */
    const val OWN_SPEED_MAX_AGE_MS = 12_000L

    /**
     * This phone's own speed in km/h, or null when the fix is too old, has no
     * speed, or does not exist.
     *
     * ## A fix stamped in the future is a fix from a moment ago
     *
     * [fixAgeMs] is `System.currentTimeMillis() - fix.time`, and those two
     * clocks are not the same clock. `Location.getTime()` on a GPS fix comes
     * from the satellites, which are the more accurate of the two; the phone's
     * own clock is set by the network and routinely runs a second or several
     * behind. The subtraction then comes out **negative**, on every fix, for
     * as long as the phone's clock is behind — and this used to answer that
     * with null, which is a permanent dash on a phone that is working
     * perfectly and reporting a real speed to everybody else's map.
     *
     * Clamped to zero instead, which is what [app.ptrip.tracktrip.map.FixAge]
     * has always done with the same skew on the other side of the wire: a fix
     * from the near future is a fix from now. The staleness guard below is
     * unaffected — it is there to stop a half-hour-old reading being presented
     * as current, and a fix stamped ahead of the clock is the opposite of
     * stale.
     */
    fun ownKmh(
        metresPerSecond: Float?,
        fixAgeMs: Long,
        maxAgeMs: Long = OWN_SPEED_MAX_AGE_MS,
    ): Int? {
        if (fixAgeMs.coerceAtLeast(0L) > maxAgeMs) return null
        return kmh(metresPerSecond?.toDouble())
    }
}
