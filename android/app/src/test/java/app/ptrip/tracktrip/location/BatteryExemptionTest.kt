package app.ptrip.tracktrip.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When to put Android's battery-exemption dialog in front of a rider.
 *
 * A small rule with two ways of being annoying: asking somebody who has
 * already said yes — where the dialog offers to take the exemption *away* —
 * and asking again after they said no, which is how an app teaches people to
 * dismiss its prompts unread.
 */
class BatteryExemptionTest {

    @Test
    fun `a rider who has never been asked and is not exempt gets the dialog`() {
        assertTrue(BatteryExemption.shouldAsk(isExempt = false, alreadyAsked = false))
    }

    @Test
    fun `nobody is asked twice`() {
        // The answer lives in Android's settings, not here, and the settings
        // row is how somebody changes their mind.
        assertFalse(BatteryExemption.shouldAsk(isExempt = false, alreadyAsked = true))
    }

    @Test
    fun `an exempt rider is never asked`() {
        // In this state the system dialog reads "stop allowing this app to
        // run in the background" — showing it would invite the opposite of
        // what it is for. Both records agree on that, whichever they say
        // about having asked before.
        assertFalse(BatteryExemption.shouldAsk(isExempt = true, alreadyAsked = false))
        assertFalse(BatteryExemption.shouldAsk(isExempt = true, alreadyAsked = true))
    }
}
