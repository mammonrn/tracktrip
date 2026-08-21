package app.ptrip.tracktrip.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rider's own places, as they arrive off the wire.
 *
 * The contract worth pinning is what is *absent*: the server never sends
 * `user_id` back, because the caller is the owner and a field that says
 * nothing is a field that ends up in a log. A parser that started depending on
 * one would be a parser that expects private rows to identify their owner —
 * which is the shape of a leak, and this test is where that shows up.
 *
 * See src/routes/places.js and src/places/personal.js.
 */
class PersonalPlacesParsingTest {

    private val body = """
        {
          "results": [
            {
              "id": 3,
              "label": "บ้าน",
              "name": "คอนโด ดิ แอสตร้า",
              "lat": 18.789,
              "lng": 98.971,
              "created_at": "2026-08-20T07:49:12.345Z"
            },
            {
              "id": 4,
              "label": "ที่ทำงาน",
              "name": "นิมมานเหมินท์ ซอย 9",
              "lat": 18.7965,
              "lng": 98.9673,
              "created_at": "2026-08-19T07:49:12.345Z"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a shortcut carries its label, its name and its point`() {
        val places = parsePersonalPlaces(body)

        assertEquals(2, places.size)
        assertEquals(3L, places[0].id)
        // The label is what the chip says; the name is what the route says.
        // They answer different questions and are not interchangeable.
        assertEquals("บ้าน", places[0].label)
        assertEquals("คอนโด ดิ แอสตร้า", places[0].name)
        assertEquals(18.789, places[0].lat, 1e-9)
        assertEquals(98.971, places[0].lng, 1e-9)
        assertEquals(18.789, places[0].point.lat, 1e-9)
        // When the rider saved it, for the detail card behind the row. Note
        // what is still absent beside it: there is no author field and never
        // will be — the owner is the caller.
        assertEquals("2026-08-20T07:49:12.345Z", places[0].createdAt)
    }

    @Test
    fun `a row with no label or no name is dropped, not guessed at`() {
        // A chip with no caption is a chip nobody can read, and a place with
        // no name lands on a route as a blank row.
        val broken = """
            {"results":[
              {"id":1,"name":"no label","lat":18.0,"lng":98.0},
              {"id":2,"label":"no name","lat":18.0,"lng":98.0},
              {"id":3,"label":"บ้าน","name":"fine","lat":18.0,"lng":98.0}
            ]}
        """.trimIndent()

        assertEquals(listOf(3L), parsePersonalPlaces(broken).map { it.id })
    }

    @Test
    fun `a row that cannot be placed on a map is dropped`() {
        val broken = """
            {"results":[
              {"id":1,"label":"บ้าน","name":"nowhere"},
              {"id":2,"label":"บ้าน","name":"somewhere","lat":18.0,"lng":98.0}
            ]}
        """.trimIndent()

        assertEquals(listOf(2L), parsePersonalPlaces(broken).map { it.id })
    }

    @Test
    fun `an empty or absent list is a list, not a crash`() {
        assertEquals(emptyList<PersonalPlace>(), parsePersonalPlaces("""{"results":[]}"""))
        assertEquals(emptyList<PersonalPlace>(), parsePersonalPlaces("""{}"""))
    }

    @Test
    fun `a personal place is not a search result and cannot become one`() {
        // There is deliberately no asPlace() here, unlike SharedPlace. A
        // private row must not be able to enter the merged search list — the
        // one place both sources meet — because everything in that list is
        // drawn with a source tag that says who can see it, and there is no
        // honest tag for "nobody but you" in a list of things everybody has.
        val place = parsePersonalPlaces(body).first()
        val methods = place::class.java.methods.map { it.name }

        assertTrue("asPlace" !in methods)
        // What it does offer is a coordinate, which is all the route needs.
        assertEquals(18.789, place.point.lat, 1e-9)
    }

    @Test
    fun `the wire shape carries no owner`() {
        // If the server ever started sending user_id, nothing here would read
        // it — and this asserts that the model has nowhere to put one.
        val fields = PersonalPlace::class.java.declaredFields.map { it.name }

        assertTrue("userId" !in fields)
        assertTrue("user_id" !in fields)
        assertTrue("createdBy" !in fields)
        assertNull(parsePersonalPlaces("""{"results":[{"id":1}]}""").firstOrNull())
    }
}
