package app.ptrip.tracktrip.location

import app.ptrip.tracktrip.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-wide sharing switch, and the bug it exists to end.
 *
 * Settings used to ask "is this phone sharing?" once per running trip, which
 * is not that question at all. A rider whose trip had ended went looking for
 * the switch and found an empty list: the trip was filtered out before the
 * rows were drawn, and there was no other control on the screen.
 *
 * The first two cases below are that report, written down. They fail against
 * the old screen not because it computed the wrong answer but because it had
 * nowhere to compute one — every switch it drew was keyed on a trip.
 */
class DeviceSharingTest {

    private fun trip(id: Long, name: String, status: String = Trip.STATUS_ACTIVE) =
        Trip(id = id, name = name, ownerId = 1L, status = status, role = Trip.ROLE_OWNER)

    private val running = trip(1L, "Chiang Mai loop")
    private val alsoRunning = trip(2L, "Pai run")
    private val ended = trip(3L, "Last summer", status = "ended")

    // --- the report -------------------------------------------------------

    @Test
    fun `the switch turns on with every trip ended`() {
        // The repro: one trip, finished, and a rider who wants sharing on.
        // There is nothing to start, and that is not a refusal — the phone's
        // answer is recorded and the phone is willing.
        val effect = DeviceSharing.onSwitched(on = true, sharingTripId = null, running = emptyList())

        assertEquals(DeviceSharing.Effect.Remember, effect)
        assertEquals(DeviceSharing.Status.ON_IDLE, DeviceSharing.status(on = true, sharingTripId = null))
    }

    @Test
    fun `the switch turns off with no trip anywhere in sight`() {
        val effect = DeviceSharing.onSwitched(on = false, sharingTripId = null, running = emptyList())

        assertEquals(DeviceSharing.Effect.Remember, effect)
        assertEquals(DeviceSharing.Status.OFF, DeviceSharing.status(on = false, sharingTripId = null))
    }

    // --- the kill switch --------------------------------------------------

    @Test
    fun `off stops whatever is live`() {
        val effect = DeviceSharing.onSwitched(on = false, sharingTripId = 7L, running = listOf(running))

        assertEquals(DeviceSharing.Effect.Stop(7L), effect)
    }

    @Test
    fun `off stops a session whose trip is no longer on the list`() {
        // The case a trip-shaped control could not reach: the trip ended, or
        // was left, while this phone was still reporting to it. The id comes
        // from the service, so the list being empty changes nothing.
        val effect = DeviceSharing.onSwitched(on = false, sharingTripId = 3L, running = emptyList())

        assertEquals(DeviceSharing.Effect.Stop(3L), effect)
    }

    // --- turning it on ----------------------------------------------------

    @Test
    fun `on with one running trip starts there`() {
        // The ordinary case, and the one the per-trip toggle used to serve:
        // one trip, so the switch is the whole gesture.
        val effect = DeviceSharing.onSwitched(on = true, sharingTripId = null, running = listOf(running))

        assertEquals(DeviceSharing.Effect.Start(running), effect)
    }

    @Test
    fun `on with several running trips starts nothing`() {
        // Which trip is a second question, and the rows below ask it.
        // Guessing would broadcast a rider's position to the wrong group.
        val effect = DeviceSharing.onSwitched(
            on = true,
            sharingTripId = null,
            running = listOf(running, alsoRunning),
        )

        assertEquals(DeviceSharing.Effect.Remember, effect)
    }

    @Test
    fun `on while already sharing changes nothing`() {
        val effect = DeviceSharing.onSwitched(on = true, sharingTripId = 1L, running = listOf(running))

        assertEquals(DeviceSharing.Effect.Remember, effect)
        assertEquals(DeviceSharing.Status.ON_SHARING, DeviceSharing.status(on = true, sharingTripId = 1L))
    }

    // --- what the trip rows may do ---------------------------------------

    @Test
    fun `a trip row obeys the phone before it obeys the trip`() {
        assertTrue(DeviceSharing.canShareOn(running, on = true))
        assertFalse(DeviceSharing.canShareOn(running, on = false))
    }

    @Test
    fun `an ended trip cannot be shared on, switch or no switch`() {
        // Not the same fact as the switch, and not the one the report was
        // about: the server refuses a session on a finished trip, so a row
        // offering it would be offering a 409.
        assertFalse(DeviceSharing.canShareOn(ended, on = true))
        assertFalse(DeviceSharing.canShareOn(ended, on = false))
    }

    @Test
    fun `off reads as off even while the service is still winding down`() {
        // The status line follows the switch, not the service: a rider who
        // has just turned it off should not read "on" back at themselves
        // because the stop call has not returned yet.
        assertEquals(DeviceSharing.Status.OFF, DeviceSharing.status(on = false, sharingTripId = 1L))
    }
}
