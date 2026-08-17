package app.ptrip.tracktrip.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the app's reading of backend responses.
 *
 * Every payload below is copied verbatim from a running backend, so a field
 * renamed on the server shows up here as a failing test rather than as an
 * empty screen on someone's phone. That is the whole point: nothing else in
 * this app checks that `is_sharing` isn't spelled `isSharing`.
 */
class TripApiParsingTest {

    @Test
    fun `GET trips parses a trip and the caller's own role`() {
        val payload = """
            [{"id":1,"name":"Smoke ride","owner_id":1,"status":"active",
              "created_at":"2026-08-17T11:10:29.479Z","ended_at":null,"role":"owner"}]
        """.trimIndent()

        val trips = JSONArray(payload).map { it.toTrip() }

        assertEquals(1, trips.size)
        val trip = trips.first()
        assertEquals(1L, trip.id)
        assertEquals("Smoke ride", trip.name)
        assertEquals(1L, trip.ownerId)
        assertTrue(trip.isActive)
        assertTrue(trip.isOwner)
        assertFalse(trip.hasEnded)
    }

    @Test
    fun `an ended trip joined as a member reads as neither active nor owned`() {
        val payload = """
            {"id":2,"name":"Pai run","owner_id":9,"status":"ended",
             "created_at":"2026-08-17T11:10:29.479Z",
             "ended_at":"2026-08-17T11:10:29.876Z","role":"member"}
        """.trimIndent()

        val trip = JSONObject(payload).toTrip()

        assertFalse(trip.isActive)
        assertTrue(trip.hasEnded)
        assertFalse(trip.isOwner)
    }

    @Test
    fun `a trip with no name falls back rather than showing the word null`() {
        val trip = JSONObject("""{"id":3,"name":null,"owner_id":1,"status":"active","role":"owner"}""")
            .toTrip()

        assertEquals("Untitled trip", trip.name)
    }

    @Test
    fun `GET invites parses the trip name the list needs`() {
        val payload = """
            [{"id":1,"trip_id":1,"email":"friend@gmail.com","invited_by":1,"status":"pending",
              "created_at":"2026-08-17T11:10:29.678Z","accepted_at":null,"accepted_by":null,
              "trip_name":"Smoke ride"}]
        """.trimIndent()

        val invite = JSONArray(payload).map { it.toInvite() }.single()

        assertEquals(1L, invite.id)
        assertEquals(1L, invite.tripId)
        assertEquals("friend@gmail.com", invite.email)
        assertEquals("Smoke ride", invite.tripName)
    }

    @Test
    fun `accepting an invite yields the trip nested in the response`() {
        val payload = """
            {"trip":{"id":1,"name":"Smoke ride","owner_id":1,"status":"active",
                     "created_at":"2026-08-17T11:10:29.479Z","ended_at":null,"role":"member"},
             "invite":{"id":1,"trip_id":1,"email":"friend@gmail.com","invited_by":1,
                       "status":"accepted","created_at":"2026-08-17T11:10:29.678Z",
                       "accepted_at":"2026-08-17T11:10:29.826Z","accepted_by":2}}
        """.trimIndent()

        val trip = JSONObject(payload).getJSONObject("trip").toTrip()

        assertEquals(1L, trip.id)
        assertEquals("member", trip.role)
    }

    @Test
    fun `GET positions parses a rider who has reported`() {
        val payload = """
            [{"user_id":2,"display_name":"Friend","photo_url":"friend.jpg","role":"member",
              "is_sharing":true,"sharing_until":"2026-08-17T17:18:22.441Z",
              "lat":19.1,"lng":99.2,"accuracy":null,"speed":22.4,
              "heading":275.5,"battery_pct":63,"recorded_at":"2026-05-01T09:00:00.000Z"}]
        """.trimIndent()

        val member = JSONArray(payload).map { it.toMemberPosition() }.single()

        assertEquals(2L, member.userId)
        assertEquals("Friend", member.displayName)
        assertEquals("friend.jpg", member.photoUrl)
        assertEquals(null, member.username)
        assertTrue(member.isSharing)
        assertEquals("2026-08-17T17:18:22.441Z", member.sharingUntil)
        assertFalse(member.isSharingIndefinitely)
        assertTrue(member.hasPosition)
        assertEquals(19.1, member.lat!!, 1e-9)
        assertEquals(99.2, member.lng!!, 1e-9)
        assertEquals(63, member.batteryPct)
        assertEquals("2026-05-01T09:00:00.000Z", member.recordedAt)
        assertFalse(member.isOwner)
    }

    @Test
    fun `on a running trip every member reads as sharing, session or not`() {
        // Verbatim GET /trips/1/positions on an ACTIVE trip: Friend has set a
        // 4-hour session, Owner has set nothing, Quiet has never reported.
        // Sharing is on by default, so having no session is not opting out.
        val payload = """
            [{"user_id":2,"display_name":"Friend","photo_url":"friend.jpg","role":"member",
              "is_sharing":true,"sharing_until":"2026-08-17T17:18:22.441Z","lat":19.1,"lng":99.2,
              "accuracy":null,"speed":22.4,"heading":275.5,"battery_pct":63,
              "recorded_at":"2026-05-01T09:00:00.000Z"},
             {"user_id":1,"display_name":"Owner","photo_url":"owner.jpg","role":"owner",
              "is_sharing":true,"sharing_until":null,"lat":18.79,"lng":98.98,"accuracy":null,
              "speed":null,"heading":null,"battery_pct":91,
              "recorded_at":"2026-05-01T08:00:00.000Z"},
             {"user_id":3,"display_name":"Quiet","photo_url":null,"role":"member",
              "is_sharing":true,"sharing_until":null,"lat":null,"lng":null,"accuracy":null,
              "speed":null,"heading":null,"battery_pct":null,"recorded_at":null}]
        """.trimIndent()

        val members = JSONArray(payload).map { it.toMemberPosition() }
        assertEquals(3, members.size)
        assertTrue(members.all { it.isSharing })

        // A null sharing_until on a sharing rider means "no end time", which
        // is why isSharing has to be read alongside it rather than inferred
        // from it.
        val owner = members.single { it.isOwner }
        assertNull(owner.sharingUntil)
        assertTrue(owner.isSharingIndefinitely)

        val friend = members.single { it.userId == 2L }
        assertFalse(friend.isSharingIndefinitely)
    }

    @Test
    fun `a paused rider reads as not sharing while the trip is still running`() {
        // Verbatim, same trip: Owner has pressed stop, so their session sits
        // spent. sharing_until is in the *past* here, which is what a pause
        // looks like on the wire.
        val payload = """
            [{"user_id":2,"display_name":"Friend","photo_url":"friend.jpg","role":"member",
              "is_sharing":true,"sharing_until":"2026-08-17T17:36:59.021Z","lat":19.1,"lng":99.2,
              "accuracy":null,"speed":22.4,"heading":275.5,"battery_pct":63,
              "recorded_at":"2026-05-01T09:00:00.000Z"},
             {"user_id":1,"display_name":"Owner","photo_url":"owner.jpg","role":"owner",
              "is_sharing":false,"sharing_until":"2026-08-17T13:36:59.047Z","lat":18.79,
              "lng":98.98,"accuracy":null,"speed":null,"heading":null,"battery_pct":91,
              "recorded_at":"2026-05-01T08:00:00.000Z"},
             {"user_id":3,"display_name":"Quiet","photo_url":null,"role":"member",
              "is_sharing":true,"sharing_until":null,"lat":null,"lng":null,"accuracy":null,
              "speed":null,"heading":null,"battery_pct":null,"recorded_at":null}]
        """.trimIndent()

        val members = JSONArray(payload).map { it.toMemberPosition() }

        val owner = members.single { it.isOwner }
        assertFalse(owner.isSharing)
        assertFalse(owner.isSharingIndefinitely)
        // Paused, not gone: their last position is still on the map.
        assertTrue(owner.hasPosition)

        // A rider who never touched the controls is still sharing, and one
        // who set a timer still has it running.
        assertTrue(members.single { it.userId == 3L }.isSharing)
        assertTrue(members.single { it.userId == 2L }.isSharing)
    }

    @Test
    fun `after the trip ends nobody reads as sharing`() {
        // Verbatim, same trip, after the owner ended it. Ending a trip stops
        // sharing for the whole group and clears every session, so both the
        // flag and the countdown come back empty for everyone — including
        // the rider who had four hours left.
        val payload = """
            [{"user_id":2,"display_name":"Friend","photo_url":"friend.jpg","role":"member",
              "is_sharing":false,"sharing_until":null,"lat":19.1,"lng":99.2,
              "accuracy":null,"speed":22.4,"heading":275.5,"battery_pct":63,
              "recorded_at":"2026-05-01T09:00:00.000Z"},
             {"user_id":1,"display_name":"Owner","photo_url":"owner.jpg","role":"owner",
              "is_sharing":false,"sharing_until":null,"lat":18.79,"lng":98.98,"accuracy":null,
              "speed":null,"heading":null,"battery_pct":91,
              "recorded_at":"2026-05-01T08:00:00.000Z"},
             {"user_id":3,"display_name":"Quiet","photo_url":null,"role":"member",
              "is_sharing":false,"sharing_until":null,"lat":null,"lng":null,"accuracy":null,
              "speed":null,"heading":null,"battery_pct":null,"recorded_at":null}]
        """.trimIndent()

        val members = JSONArray(payload).map { it.toMemberPosition() }

        assertEquals(3, members.size)
        assertTrue(members.none { it.isSharing })
        assertTrue(members.none { it.isSharingIndefinitely })
        assertTrue(members.all { it.sharingUntil == null })

        // The ride is still there to look back on.
        assertTrue(members.single { it.userId == 2L }.hasPosition)
    }

    @Test
    fun `a member row missing is_sharing is read as not sharing`() {
        // Not a shape the backend sends, but the fallback matters: guessing
        // "true" would paint a rider as live on the strength of a missing
        // field.
        val member = JSONObject(
            """{"user_id":4,"display_name":"Unknown","role":"member","lat":null,"lng":null}"""
        ).toMemberPosition()

        assertFalse(member.isSharing)
        assertNull(member.sharingUntil)
    }

    @Test
    fun `a member who has never reported parses with no position, not zeroes`() {
        // The backend lists every member, nulling the position fields for
        // anyone yet to report. Reading those as 0.0 would drop the rider on
        // the map off the coast of Africa.
        val payload = """
            {"user_id":3,"display_name":"Silent","photo_url":null,"role":"owner",
             "is_sharing":true,"sharing_until":null,"lat":null,"lng":null,"accuracy":null,
             "speed":null,"heading":null,"battery_pct":null,"recorded_at":null}
        """.trimIndent()

        val member = JSONObject(payload).toMemberPosition()

        assertFalse(member.hasPosition)
        assertNull(member.lat)
        assertNull(member.lng)
        assertNull(member.batteryPct)
        assertNull(member.recordedAt)
        assertTrue(member.isOwner)
    }

    @Test
    fun `a member with no display name still gets something to show`() {
        val member = JSONObject(
            """{"user_id":7,"display_name":null,"role":"member","is_sharing":true,
                "sharing_until":null,"lat":null,"lng":null,"battery_pct":null,
                "recorded_at":null}"""
        ).toMemberPosition()

        assertEquals("Rider 7", member.label)
    }

    @Test
    fun `GET me-level parses progress towards the next level`() {
        val payload = """
            {"total_km":589.01,"level":{"name":"Rookie Rider","min_km":500},
             "next_level":{"name":"Wanderer","min_km":1500},"km_to_next":910.99}
        """.trimIndent()

        val progress = JSONObject(payload).toLevelProgress()

        assertEquals(589.01, progress.totalKm, 1e-9)
        assertEquals("Rookie Rider", progress.levelName)
        assertEquals(500.0, progress.levelMinKm, 1e-9)
        assertEquals("Wanderer", progress.nextLevelName)
        assertEquals(1500.0, progress.nextLevelMinKm!!, 1e-9)
        assertEquals(910.99, progress.kmToNext!!, 1e-9)
    }

    @Test
    fun `the top level parses as having nothing left to chase`() {
        // null here means "no next level", which the profile screen renders
        // differently from a 0 km countdown.
        val payload = """
            {"total_km":25000.0,"level":{"name":"Legendary","min_km":20000},
             "next_level":null,"km_to_next":null}
        """.trimIndent()

        val progress = JSONObject(payload).toLevelProgress()

        assertEquals("Legendary", progress.levelName)
        assertNull(progress.nextLevelName)
        assertNull(progress.nextLevelMinKm)
        assertNull(progress.kmToNext)
    }

    @Test
    fun `a brand new rider parses as zero kilometres`() {
        val payload = """
            {"total_km":0,"level":{"name":"Novice","min_km":0},
             "next_level":{"name":"Rookie Rider","min_km":500},"km_to_next":500}
        """.trimIndent()

        val progress = JSONObject(payload).toLevelProgress()

        assertEquals(0.0, progress.totalKm, 1e-9)
        assertEquals("Novice", progress.levelName)
        assertEquals(500.0, progress.kmToNext!!, 1e-9)
    }

    @Test
    fun `an empty list parses as an empty list, not a failure`() {
        assertTrue(JSONArray("[]").map { it.toTrip() }.isEmpty())
        assertTrue(JSONArray("[]").map { it.toInvite() }.isEmpty())
        assertTrue(JSONArray("[]").map { it.toMemberPosition() }.isEmpty())
    }
}
