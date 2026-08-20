package app.ptrip.tracktrip.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search response, as the app reads it.
 *
 * The coordinates are numbers by the time they get here — the *server* is
 * what turns LocationIQ's strings into numbers, and this is the test that
 * fails on a laptop if either side of that contract moves, rather than on a
 * motorcycle with a marker in the Gulf of Guinea.
 */
class PlaceSearchParsingTest {

    private val body = """
        {
          "query": "Pai",
          "cached": false,
          "results": [
            {
              "name": "Pai",
              "address": "Pai, Mae Hong Son, Thailand",
              "lat": 19.3583,
              "lng": 98.4406,
              "kind": "town",
              "osm_id": "1234567"
            },
            {
              "name": "Pai River",
              "address": "Pai River, Mae Hong Son, Thailand",
              "lat": 19.36,
              "lng": 98.44,
              "kind": null,
              "osm_id": null
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a result carries its name, address and coordinates`() {
        val places = parsePlaces(body)

        assertEquals(2, places.size)
        assertEquals("Pai", places[0].name)
        assertEquals("Pai, Mae Hong Son, Thailand", places[0].address)
        assertEquals(19.3583, places[0].lat, 1e-9)
        assertEquals(98.4406, places[0].lng, 1e-9)
        assertEquals("town", places[0].kind)
        assertEquals("1234567", places[0].osmId)
    }

    @Test
    fun `absent optional fields come back as null, not as the string "null"`() {
        val places = parsePlaces(body)

        assertNull(places[1].kind)
        assertNull(places[1].osmId)
    }

    @Test
    fun `an empty result set is an empty list, not a failure`() {
        // The ordinary answer while somebody is still typing the second half
        // of a name.
        assertTrue(parsePlaces("""{"query":"zzz","results":[]}""").isEmpty())
    }

    @Test
    fun `a payload with no results key is an empty list`() {
        assertTrue(parsePlaces("""{"query":"zzz"}""").isEmpty())
    }

    @Test
    fun `a result that cannot be placed on the map is dropped`() {
        // The server drops these too. Belt and braces, because the one thing
        // this list must never contain is a row that goes nowhere when tapped.
        val places = parsePlaces(
            """
            {"results":[
              {"name":"Nowhere","address":"Nowhere"},
              {"name":"Pai","address":"Pai","lat":19.3,"lng":98.4}
            ]}
            """.trimIndent()
        )

        assertEquals(1, places.size)
        assertEquals("Pai", places[0].name)
    }

    @Test
    fun `a result with only an address still has a name`() {
        val places = parsePlaces("""{"results":[{"address":"Somewhere","lat":1.0,"lng":2.0}]}""")

        assertEquals("Somewhere", places[0].name)
        assertEquals("Somewhere", places[0].address)
    }

    @Test
    fun `two results with the same name still get distinct list keys`() {
        // There are a great many 7-Elevens, and a LazyColumn with duplicate
        // keys throws rather than drawing.
        val places = parsePlaces(
            """
            {"results":[
              {"name":"7-Eleven","address":"A","lat":19.1,"lng":98.1},
              {"name":"7-Eleven","address":"B","lat":19.2,"lng":98.2}
            ]}
            """.trimIndent()
        )

        assertEquals(2, places.map { it.key }.distinct().size)
    }

    @Test
    fun `a key prefers the OSM id, and falls back to the coordinate`() {
        val withId = Place("A", "A", 1.0, 2.0, kind = null, osmId = "99")
        val without = Place("A", "A", 1.0, 2.0, kind = null, osmId = null)

        assertEquals("GEOCODER:99", withId.key)
        assertEquals("GEOCODER:1.0,2.0", without.key)
    }

    @Test
    fun `a shared place and an OSM object cannot collide on the same key`() {
        // The two lists are merged into one and their ids mean different
        // things — shared place 99 and OSM object 99 are unrelated. Without
        // the source in the key a LazyColumn handed the same key twice drops a
        // row, and the row it drops is the one a rider is reaching for.
        val osm = Place("A", "A", 1.0, 2.0, kind = null, osmId = "99")
        val shared = Place(
            "A", "A", 1.0, 2.0,
            kind = null,
            osmId = null,
            source = PlaceSource.SHARED,
            sharedId = 99L,
        )

        assertNotEquals(osm.key, shared.key)
    }

    @Test
    fun `only a rider's own shared place offers them a cross`() {
        // Everyone can see every row, so a cross on somebody else's would
        // invite removing something the group is still using. Mirrors
        // canDeleteSharedPlace in src/places/shared.js.
        val mine = Place(
            "A", "anne", 1.0, 2.0,
            kind = null, osmId = null,
            source = PlaceSource.SHARED, sharedId = 1L, createdBy = 7L,
        )
        assertTrue(mine.isRemovableBy(7L))
        assertFalse(mine.isRemovableBy(8L))
        assertFalse(mine.isRemovableBy(null))

        // An orphan — the account that typed it is gone — belongs to nobody.
        val orphan = mine.copy(createdBy = null)
        assertFalse(orphan.isRemovableBy(7L))

        // And a geocoder row is not on any list this app can write to.
        val osm = Place("A", "A", 1.0, 2.0, kind = null, osmId = "99")
        assertFalse(osm.isRemovableBy(7L))
    }
}
