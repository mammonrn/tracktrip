package app.ptrip.tracktrip.location

/**
 * What a battery percentage means to a group on a ride.
 *
 * A number on its own is a number: 37 reads the same as 87 to somebody glancing
 * at a screen through a visor. What the group actually needs to know is whether
 * a rider is about to drop off the map, because a phone that dies stops
 * reporting and the pin freezes exactly where the battery went — and nobody can
 * tell that from a rider who has stopped.
 *
 * So the number gets an icon that fills and a colour that changes, and the two
 * thresholds live here rather than in either screen: the map and the member
 * list showed the same field two different ways once already, and this is the
 * seam where that started.
 */
object BatteryLevel {

    /**
     * Worth noticing. About an hour and a half of sharing at the app's
     * reporting cadence with the screen off — enough to plan a stop around,
     * which is what noticing is for.
     */
    const val LOW_PCT = 20

    /** Worth acting on now: this phone will not finish the ride. */
    const val CRITICAL_PCT = 10

    /** A reading that could not have come from a battery. Never drawn. */
    fun isValid(percent: Int): Boolean = percent in 0..100

    fun isLow(percent: Int): Boolean = percent <= LOW_PCT

    fun isCritical(percent: Int): Boolean = percent <= CRITICAL_PCT

    /**
     * How much of the icon to fill, from 0 to 1.
     *
     * Clamped rather than trusted: the value comes off another rider's phone,
     * through a server, and a vendor that reports 105 should give a full
     * battery rather than one drawn past its own outline.
     */
    fun fillFraction(percent: Int): Float = (percent / 100f).coerceIn(0f, 1f)
}
