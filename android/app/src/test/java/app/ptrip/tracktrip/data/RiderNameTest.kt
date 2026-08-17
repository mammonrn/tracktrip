package app.ptrip.tracktrip.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One rule for what to call a rider, used by every screen that shows a name.
 *
 * The cases that matter are the empty ones: a display name Google supplied as
 * a blank string, and a username saved as whitespace, both have to fall
 * through rather than render as an empty line where a name should be.
 */
class RiderNameTest {

    @Test
    fun `a username wins over the name Google supplied`() {
        assertEquals("speedy", riderLabel("speedy", "Poom Sukjai", "Rider 7"))
    }

    @Test
    fun `without a username the display name is used`() {
        assertEquals("Poom Sukjai", riderLabel(null, "Poom Sukjai", "Rider 7"))
    }

    @Test
    fun `with neither, the fallback is used`() {
        assertEquals("Rider 7", riderLabel(null, null, "Rider 7"))
    }

    @Test
    fun `blank names fall through rather than rendering as nothing`() {
        assertEquals("Poom", riderLabel("", "Poom", "Rider 7"))
        assertEquals("Poom", riderLabel("   ", "Poom", "Rider 7"))
        assertEquals("Rider 7", riderLabel("", "  ", "Rider 7"))
    }

    @Test
    fun `every model that shows a name goes through the same rule`() {
        val member = MemberPosition(
            userId = 7,
            displayName = "Poom Sukjai",
            username = "speedy",
            photoUrl = null,
            role = "member",
            isSharing = true,
            sharingUntil = null,
            lat = null,
            lng = null,
            batteryPct = null,
            recordedAt = null,
        )
        assertEquals("speedy", member.label)
        assertEquals("Rider 7", member.copy(displayName = null, username = null).label)

        val invitee = SuggestedInvitee(
            userId = 7,
            email = "poom@gmail.com",
            displayName = "Poom Sukjai",
            username = "speedy",
            photoUrl = null,
            tripsTogether = 3,
        )
        assertEquals("speedy", invitee.label)
        assertEquals("poom@gmail.com", invitee.copy(displayName = null, username = null).label)

        val profile = UserProfile(
            id = 7,
            email = "poom@gmail.com",
            displayName = "Poom Sukjai",
            photoUrl = null,
            firstName = null,
            lastName = null,
            username = "speedy",
            phone = null,
            birthDate = null,
            totalKm = 0.0,
        )
        assertEquals("speedy", profile.label)
        assertEquals("poom@gmail.com", profile.copy(displayName = null, username = null).label)
    }
}
