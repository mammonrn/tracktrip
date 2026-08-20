package app.ptrip.tracktrip.location

import app.ptrip.tracktrip.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 *
 * The fix that first landed kept both: a device switch above the per-trip
 * rows. That was one question too many — a rider is on one ride at a time, and
 * the phone is on one by construction — so the rows are gone and this is the
 * whole of the decision now.
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
    fun `on with two somehow-running trips takes the newest rather than asking`() {
        // A rider is on one ride at a time, so this should not arise — but
        // nothing enforces it: `trips` has no such constraint, `POST /trips`
        // no such guard, and `sharing_sessions` is keyed per trip *and* rider.
        // `GET /trips` orders `created_at DESC, id DESC`, so the first is the
        // most recently started, which is the one somebody with two open trips
        // is riding. Deliberately not a picker — the picker is what this
        // change removed.
        val effect = DeviceSharing.onSwitched(
            on = true,
            sharingTripId = null,
            running = listOf(running, alsoRunning),
        )

        assertEquals(DeviceSharing.Effect.Start(running), effect)
    }

    @Test
    fun `on while already sharing changes nothing`() {
        val effect = DeviceSharing.onSwitched(on = true, sharingTripId = 1L, running = listOf(running))

        assertEquals(DeviceSharing.Effect.Remember, effect)
        assertEquals(DeviceSharing.Status.ON_SHARING, DeviceSharing.status(on = true, sharingTripId = 1L))
    }

    @Test
    fun `an ended trip never reaches the decision`() {
        // "On" now starts on the first trip it is handed without asking
        // anything else about it, so the `isActive` filter that builds the
        // list is the only thing keeping out a finished trip — which the
        // server refuses a session on. Worth holding the two together.
        val list = listOf(running, ended).filter { it.isActive }

        assertFalse(ended in list)
        assertEquals(
            DeviceSharing.Effect.Start(running),
            DeviceSharing.onSwitched(on = true, sharingTripId = null, running = list),
        )
    }

    @Test
    fun `off reads as off even while the service is still winding down`() {
        // The status line follows the switch, not the service: a rider who
        // has just turned it off should not read "on" back at themselves
        // because the stop call has not returned yet.
        assertEquals(DeviceSharing.Status.OFF, DeviceSharing.status(on = false, sharingTripId = 1L))
    }
}
