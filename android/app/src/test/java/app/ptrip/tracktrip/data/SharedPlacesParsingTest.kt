package app.ptrip.tracktrip.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The riders' own places, as they arrive off the wire.
 *
 * The contract worth pinning here is the one that is easy to get wrong in a
 * way nothing complains about: `created_by` is null for a place whose author
 * has since been deleted, and JSON null is `JSONObject.NULL` rather than
 * Kotlin's null. Read carelessly that comes back as 0, which is not a user id
 * and would put a cross on somebody else's row.
 *
 * See src/routes/places.js and src/db/migrations/0011_shared_places.sql.
 */
class SharedPlacesParsingTest {

    private val body = """
        {
          "query": "ปตท สวนดอก",
          "results": [
            {
              "id": 12,
              "name": "ปตท สวนดอก",
              "lat": 18.789,
              "lng": 98.971,
              "created_by": 7,
              "created_by_name": "anne",
              "created_at": "2026-08-20T07:49:12.345Z"
            },
            {
              "id": 13,
              "name": "จุดชมวิวดอยสุเทพ",
              "lat": 18.8047,
              "lng": 98.9217,
              "created_by": null,
              "created_by_name": null,
              "created_at": "2026-08-19T07:49:12.345Z"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a place carries its id, its point and who typed it`() {
        val places = parseSharedPlaces(body)

        assertEquals(2, places.size)
        assertEquals(12L, places[0].id)
        assertEquals("ปตท สวนดอก", places[0].name)
        assertEquals(18.789, places[0].lat, 1e-9)
        assertEquals(98.971, places[0].lng, 1e-9)
        assertEquals(7L, places[0].createdBy)
        assertEquals("anne", places[0].createdByName)
    }

    @Test
    fun `a place whose author is gone comes back with nobody, not with zero`() {
        // ON DELETE SET NULL keeps the place and drops only the claim on it.
        // Read as 0 this would be a user id, and a rider whose own id happened
        // to be 0 would be offered a cross on a row that is not theirs.
        val orphan = parseSharedPlaces(body)[1]

        assertNull(orphan.createdBy)
        assertNull(orphan.createdByName)
        assertTrue(orphan.asPlace().address.isEmpty())
    }

    @Test
    fun `a place becomes a search result of its own kind`() {
        val place = parseSharedPlaces(body)[0].asPlace()

        assertEquals(PlaceSource.SHARED, place.source)
        assertEquals(12L, place.sharedId)
        assertEquals(7L, place.createdBy)
        // No address to show, so the line under the name says who wrote it —
        // the one thing worth knowing about a row that did not come from a map
        // company.
        assertEquals("anne", place.address)
        assertNull(place.osmId)
    }

    @Test
    fun `a row that cannot be placed on a map is dropped`() {
        // The list is a list of things to tap, and a row that goes nowhere is
        // a bug report waiting to be filed.
        val broken = """
            {"results":[
              {"id":1,"name":"no point","created_by":1},
              {"id":2,"lat":18.0,"lng":98.0,"created_by":1},
              {"id":3,"name":"fine","lat":18.0,"lng":98.0,"created_by":1}
            ]}
        """.trimIndent()

        assertEquals(listOf(3L), parseSharedPlaces(broken).map { it.id })
    }

    @Test
    fun `an envelope with no results at all is an empty list, not a crash`() {
        assertEquals(emptyList<SharedPlace>(), parseSharedPlaces("""{"query":"x"}"""))
        assertEquals(emptyList<SharedPlace>(), parseSharedPlaces("""{"results":[]}"""))
    }
}
