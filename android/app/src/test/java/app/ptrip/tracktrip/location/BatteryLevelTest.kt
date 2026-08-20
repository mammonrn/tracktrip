package app.ptrip.tracktrip.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a battery percentage means, and what it must never claim.
 *
 * Small rules, but this is the file two screens now share instead of each
 * drawing their own bare `"$it%"` — which is how the map and the member list
 * came to show the same rider's battery two different ways in the first place.
 */
class BatteryLevelTest {

    @Test
    fun `a reading that could not have come from a battery is refused`() {
        // The value arrives off another rider's phone, through a server, and
        // some vendors have been known to report over 100.
        assertFalse(BatteryLevel.isValid(-1))
        assertFalse(BatteryLevel.isValid(101))
        assertTrue(BatteryLevel.isValid(0))
        assertTrue(BatteryLevel.isValid(100))
    }

    @Test
    fun `low and critical are separate answers`() {
        assertFalse(BatteryLevel.isLow(21))
        assertTrue(BatteryLevel.isLow(20))
        assertTrue(BatteryLevel.isLow(10))

        assertFalse(BatteryLevel.isCritical(11))
        assertTrue(BatteryLevel.isCritical(10))
        assertTrue(BatteryLevel.isCritical(0))
    }

    @Test
    fun `everything critical is also low`() {
        for (percent in 0..100) {
            if (BatteryLevel.isCritical(percent)) {
                assertTrue("$percent% is critical but not low", BatteryLevel.isLow(percent))
            }
        }
    }

    @Test
    fun `the fill follows the number`() {
        assertEquals(0f, BatteryLevel.fillFraction(0), 0.001f)
        assertEquals(0.37f, BatteryLevel.fillFraction(37), 0.001f)
        assertEquals(1f, BatteryLevel.fillFraction(100), 0.001f)
    }

    @Test
    fun `a nonsense reading never draws past the icon's own outline`() {
        assertEquals(1f, BatteryLevel.fillFraction(140), 0.001f)
        assertEquals(0f, BatteryLevel.fillFraction(-5), 0.001f)
    }
}
