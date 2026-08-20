package app.ptrip.tracktrip.data

import app.ptrip.tracktrip.map.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `GET /directions`, as the phone reads it.
 *
 * Mocked at the response body, which is where this app's other API parsers are
 * tested: the upstream call itself is mocked on the server side, in
 * test/directions.test.js, and testing it twice from two languages would prove
 * the same thing about a fake.
 *
 * The rule underneath every case here is the one the feature turns on: a
 * response this parser cannot use is null, and null means "draw the straight
 * line". Nothing about a missing route is an error a rider is told about.
 */
class DirectionsParsingTest {

    @Test
    fun `a route becomes a line, a distance and a duration`() {
        val body = """
            {
              "from": {"lat": 18.7883, "lng": 98.9853},
              "to": {"lat": 19.3583, "lng": 98.4406},
              "route": {
                "points": [
                  {"lat": 18.7883, "lng": 98.9853},
                  {"lat": 19.0, "lng": 98.7},
                  {"lat": 19.3583, "lng": 98.4406}
                ],
                "distance_km": 134.5,
                "duration_min": 210
              },
              "cached": false
            }
        """.trimIndent()

        val route = parseRouteLine(body)!!

        assertEquals(3, route.points.size)
        assertEquals(LatLng(18.7883, 98.9853), route.points.first())
        assertEquals(LatLng(19.3583, 98.4406), route.points.last())
        assertEquals(134.5, route.distanceKm!!, 0.001)
        assertEquals(210, route.durationMinutes)
    }

    @Test
    fun `no road between two points is a null route, not an error`() {
        // The server answers this as a 200 with `route: null` — a finish on an
        // island, or on the wrong side of a strait.
        val body = """{"from": {"lat": 1, "lng": 1}, "to": {"lat": 2, "lng": 2}, "route": null}"""

        assertNull(parseRouteLine(body))
    }

    @Test
    fun `a response from a build that does not know about routes is null`() {
        assertNull(parseRouteLine("""{"ok": true}"""))
    }

    @Test
    fun `one point is not a line`() {
        // Drawing a polyline through a single point puts a stray dot on the
        // map with no road under it.
        val body = """{"route": {"points": [{"lat": 18.0, "lng": 98.0}], "distance_km": 0}}"""

        assertNull(parseRouteLine(body))
    }

    @Test
    fun `a route with no points at all is null`() {
        assertNull(parseRouteLine("""{"route": {"points": [], "distance_km": 12}}"""))
        assertNull(parseRouteLine("""{"route": {"distance_km": 12}}"""))
    }

    @Test
    fun `a vertex missing a coordinate is dropped, and the rest still draws`() {
        val body = """
            {"route": {"points": [
              {"lat": 18.0, "lng": 98.0},
              {"lat": 18.1},
              {"lng": 98.2},
              {"lat": 18.3, "lng": 98.3}
            ]}}
        """.trimIndent()

        val route = parseRouteLine(body)!!

        assertEquals(listOf(LatLng(18.0, 98.0), LatLng(18.3, 98.3)), route.points)
    }

    @Test
    fun `a route with no distance still draws`() {
        // Null rather than zero: the screen falls back to the straight-line
        // distance for the number, and a zero would read as "you have
        // arrived".
        val body = """
            {"route": {"points": [{"lat": 18.0, "lng": 98.0}, {"lat": 18.1, "lng": 98.1}]}}
        """.trimIndent()

        val route = parseRouteLine(body)!!

        assertEquals(2, route.points.size)
        assertNull(route.distanceKm)
        assertNull(route.durationMinutes)
    }

    @Test
    fun `an explicit JSON null distance is read as absent, not as zero`() {
        val body = """
            {"route": {
              "points": [{"lat": 18.0, "lng": 98.0}, {"lat": 18.1, "lng": 98.1}],
              "distance_km": null,
              "duration_min": null
            }}
        """.trimIndent()

        val route = parseRouteLine(body)!!

        assertNull(route.distanceKm)
        assertNull(route.durationMinutes)
    }

    @Test
    fun `a long route is read in full`() {
        // The server thins the geometry to its own cap; whatever arrives is
        // what gets drawn, and the parser must not have a limit of its own
        // that silently cuts the line short.
        val points = (0 until 600).joinToString(",") { """{"lat": ${18.0 + it / 1000.0}, "lng": 98.0}""" }
        val route = parseRouteLine("""{"route": {"points": [$points], "distance_km": 60}}""")!!

        assertEquals(600, route.points.size)
    }
}
