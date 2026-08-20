package app.ptrip.tracktrip.location

import org.junit.Assert.assertEquals
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

    /**
     * Where the settings row sends somebody who presses it.
     *
     * The row was reported dead: it reads "Unrestricted", and pressing it does
     * nothing at all. It always launched the same request-the-exemption
     * intent, and that intent's system activity checks first and finishes
     * without drawing anything when there is nothing left to grant — so on
     * exactly the phones whose row says "Unrestricted", the press was a no-op
     * by construction.
     */
    @Test
    fun `an exempt rider is sent somewhere they can actually change it`() {
        val destinations = BatteryExemption.destinations(isExempt = true)

        // The regression: asking for an exemption this rider already has is
        // the press that does nothing. It must not be the first thing tried,
        // and there is no state in which it is worth trying at all here.
        assertFalse(BatterySettingsTarget.REQUEST_EXEMPTION in destinations)
        assertEquals(BatterySettingsTarget.APP_DETAILS, destinations.first())
    }

    @Test
    fun `a rider who is not exempt still gets the one-tap dialog first`() {
        // One yes/no beats a list of every app on the phone, and while the app
        // is optimised the dialog really is a dialog.
        assertEquals(
            BatterySettingsTarget.REQUEST_EXEMPTION,
            BatteryExemption.destinations(isExempt = false).first(),
        )
    }

    @Test
    fun `every state has somewhere to fall back to`() {
        // Each of these can be absent — a build that strips the dialog, a
        // managed device with the list disabled — and a press that finds its
        // first choice missing has to land somewhere rather than nowhere.
        // That is the failure this whole list exists to stop repeating.
        listOf(true, false).forEach { exempt ->
            val destinations = BatteryExemption.destinations(exempt)
            assertTrue("no fallback for isExempt=$exempt", destinations.size >= 2)
            assertEquals(
                "the fallbacks repeat for isExempt=$exempt",
                destinations.size,
                destinations.toSet().size,
            )
        }
    }
}
