package app.ptrip.tracktrip.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promise that makes the socket safe to add.
 *
 * A live feed that replaced polling would mean a rider who lost their
 * connection in a valley watched a frozen map without being told. These are
 * the numbers that stop that happening: the poll never reaches zero, and it
 * returns to its old rate the moment the socket is not carrying anything.
 */
class LiveCadenceTest {

    @Test
    fun `losing the socket restores the cadence the app has always used`() {
        // 20s is what the map polled at before any of this existed. A rider
        // whose socket drops is exactly as well served as they were then.
        assertEquals(20_000L, LiveCadence.pollIntervalMs(socketConnected = false))
    }

    @Test
    fun `the poll slows while the socket works but never stops`() {
        val live = LiveCadence.pollIntervalMs(socketConnected = true)

        assertTrue("a live map still reconciles", live > 0)
        assertTrue("and does so less often than a blind one", live > LiveCadence.POLL_MS)
    }

    @Test
    fun `the reconciliation pass is often enough to notice a roster change`() {
        // The socket carries positions and nothing else — not somebody joining
        // the trip, leaving it, or switching sharing off. Two minutes is the
        // longest those can go unnoticed, and it is a bound worth stating.
        assertTrue(LiveCadence.LIVE_POLL_MS <= 120_000L)
    }

    @Test
    fun `the first retry after a drop is quick`() {
        // Most drops are a blip — a cell handover, a moment in a dip. Waiting
        // out a long backoff for those would make the feed feel unreliable
        // when the network was merely ordinary.
        assertEquals(1_000L, LiveCadence.retryDelayMs(1))
    }

    @Test
    fun `retries double`() {
        assertEquals(2_000L, LiveCadence.retryDelayMs(2))
        assertEquals(4_000L, LiveCadence.retryDelayMs(3))
        assertEquals(8_000L, LiveCadence.retryDelayMs(4))
    }

    @Test
    fun `retries stop growing at half a minute`() {
        // A phone coming out of a tunnel has to be live again within a
        // reasonable stretch of road. The fast poll is feeding the map in the
        // meantime, so the ceiling costs the rider nothing.
        assertEquals(30_000L, LiveCadence.retryDelayMs(20))
        assertEquals(30_000L, LiveCadence.retryDelayMs(1_000))
    }

    @Test
    fun `an absurd attempt count cannot produce a negative delay`() {
        // A shift that overflowed would come back negative, and a negative
        // delay is no delay — a retry storm, which is the one thing a backoff
        // exists to prevent.
        listOf(30, 62, 64, 1_000, Int.MAX_VALUE).forEach { attempt ->
            val delay = LiveCadence.retryDelayMs(attempt)
            assertTrue("attempt $attempt gave $delay", delay in 1_000L..30_000L)
        }
    }

    @Test
    fun `an attempt count below one is still a real wait`() {
        // Defensive: a caller resetting a counter should never be able to turn
        // the backoff into a spin.
        assertEquals(1_000L, LiveCadence.retryDelayMs(0))
        assertEquals(1_000L, LiveCadence.retryDelayMs(-5))
    }
}
