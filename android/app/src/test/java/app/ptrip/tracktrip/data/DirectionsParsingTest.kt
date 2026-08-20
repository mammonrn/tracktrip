package app.ptrip.tracktrip.data

import app.ptrip.tracktrip.map.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

/**
 * The request path, which is the half of this file that fails silently.
 *
 * A wrong separator, a missing parameter or a locale-formatted number all come
 * back from the server as a 400, and the map answers a 400 by drawing no line
 * at all — indistinguishable from a server with no routing key. So the path is
 * built by a pure function and asserted whole here rather than trusted.
 */
class DirectionsPathTest {

    private val chiangMai = LatLng(18.7883, 98.9853)
    private val pai = LatLng(19.3583, 98.4406)
    private val lampang = LatLng(18.2888, 99.4908)

    @Test
    fun `a route with no stops asks for exactly what it always asked for`() {
        assertEquals(
            "/directions?from=18.78830%2C98.98530&to=19.35830%2C98.44060",
            directionsPath(chiangMai, pai),
        )
    }

    @Test
    fun `stops travel in one parameter, semicolon separated, in order`() {
        val path = directionsPath(chiangMai, pai, listOf(lampang, chiangMai))

        // %2C is a comma inside a coordinate, %3B the separator between them.
        assertEquals(
            "/directions?from=18.78830%2C98.98530&to=19.35830%2C98.44060" +
                "&waypoints=18.28880%2C99.49080%3B18.78830%2C98.98530",
            path,
        )
    }

    @Test
    fun `an empty stop list adds no parameter at all`() {
        // A server older than this app has no `waypoints` to read; a trip with
        // no stops must ask it exactly the question it already understands.
        assertFalse("waypoints" in directionsPath(chiangMai, pai, emptyList()))
    }

    @Test
    fun `coordinates are locale-fixed, decimal point and all`() {
        val thai = java.util.Locale.getDefault()
        try {
            // A phone whose locale writes 18,7883 would otherwise send a
            // four-part coordinate and be refused, silently.
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("th-TH-u-nu-thai"))
            val path = directionsPath(chiangMai, pai, listOf(lampang))
            assertTrue("18.78830" in java.net.URLDecoder.decode(path, "UTF-8"))
            assertTrue("18.28880,99.49080" in java.net.URLDecoder.decode(path, "UTF-8"))
        } finally {
            java.util.Locale.setDefault(thai)
        }
    }

    @Test
    fun `what the server sends back is what comes out the other end`() {
        // The round trip this file's two halves make between them: the path is
        // built here, and the answer to it parses into the line the map draws.
        val line = parseRouteLine(
            """{"route":{"points":[{"lat":18.7,"lng":98.9},{"lat":18.8,"lng":99.0}],
               "distance_km":12.5,"duration_min":25}}"""
        )
        assertEquals(2, line?.points?.size)
        assertEquals(25, line?.durationMinutes)
    }
}
