package app.ptrip.tracktrip.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scanner points at whatever is in front of it, so most of what this
 * parser sees will not be a join code at all. Recognising ours and quietly
 * declining everything else is the whole job.
 */
class JoinLinkTest {

    @Test
    fun `a link round-trips back to the code it was built from`() {
        assertEquals("K7M2QRX9", joinCodeFrom(joinLinkFor("K7M2QRX9")))
    }

    @Test
    fun `a bare code is accepted, since one can be read aloud`() {
        assertEquals("K7M2QRX9", joinCodeFrom("K7M2QRX9"))
        assertEquals("K7M2QRX9", joinCodeFrom("k7m2qrx9"))
        assertEquals("K7M2QRX9", joinCodeFrom("  K7M2-QRX9 "))
    }

    @Test
    fun `extra query parameters do not confuse it`() {
        assertEquals("K7M2QRX9", joinCodeFrom("tracktrip://join?code=K7M2QRX9&from=qr"))
    }

    @Test
    fun `somebody else's QR code is not an error, just not ours`() {
        assertNull(joinCodeFrom("https://example.com/"))
        assertNull(joinCodeFrom("WIFI:S:CafeWifi;T:WPA;P:hunter2;;"))
        assertNull(joinCodeFrom("867530912345678"))
        assertNull(joinCodeFrom(""))
        assertNull(joinCodeFrom(null))
    }

    @Test
    fun `a code of the wrong shape is refused before it reaches the server`() {
        assertNull(joinCodeFrom("K7M2QRX"))
        assertNull(joinCodeFrom("K7M2QRX99"))
        // I, O, 0 and 1 are not in the alphabet — a code containing one was
        // misread, and sending it would spend an attempt from the rate limit.
        assertNull(joinCodeFrom("K7M2QRXO"))
        assertNull(joinCodeFrom("K7M2QRX0"))
        assertNull(joinCodeFrom("tracktrip://join?code=NOPE"))
    }
}
