package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.Place
import app.ptrip.tracktrip.data.PlaceLookup
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.data.PlaceSearchProblem
import app.ptrip.tracktrip.data.PlaceSource
import app.ptrip.tracktrip.data.SessionExpiredException
import app.ptrip.tracktrip.data.SharedPlace
import app.ptrip.tracktrip.data.SharedPlaceStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When typing costs a request, and when it does not.
 *
 * This is the test that guards the quota. The search runs on LocationIQ's
 * free tier — 5,000 requests a day for the whole server — and "Chiang Mai" is
 * nine keystrokes: a controller that fired on each of them would spend nine
 * requests to answer one question, and six riders planning a ride would empty
 * a day's budget in an afternoon.
 *
 * Run on virtual time, so a 450 ms debounce costs nothing to assert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchTest {

    private val pai = Place("Pai", "Pai, Mae Hong Son, Thailand", 19.3583, 98.4406, "town", "1")

    /** A stub upstream that counts what it was asked, and when. */
    private class Recorder(
        private val answer: suspend (String) -> List<Place> = { emptyList() },
    ) : PlaceLookup {
        val queries = mutableListOf<String>()

        /** The bias each search went out with, so a test can pin it. */
        val biases = mutableListOf<LatLng?>()

        override suspend fun search(query: String, limit: Int, near: LatLng?): List<Place> {
            queries += query
            biases += near
            return answer(query)
        }
    }

    /** A stub shared list, which fails or answers independently of the geocoder. */
    private class Shelf(
        private val answer: suspend (String) -> List<SharedPlace> = { emptyList() },
    ) : SharedPlaceStore {
        val queries = mutableListOf<String>()
        val added = mutableListOf<Pair<String, LatLng>>()
        val removed = mutableListOf<Long>()

        override suspend fun search(query: String, limit: Int, near: LatLng?): List<SharedPlace> {
            queries += query
            return answer(query)
        }

        override suspend fun add(name: String, point: LatLng): SharedPlace {
            added += name to point
            return SharedPlace(1L, name, point.lat, point.lng, createdBy = 7L, createdByName = "anne")
        }

        override suspend fun remove(id: Long) {
            removed += id
        }
    }

    /** The petrol station that started all of this. OSM has no row for it. */
    private val suanDok =
        SharedPlace(1L, "ปตท สวนดอก", 18.789, 98.971, createdBy = 7L, createdByName = "anne")

    // --- the rules --------------------------------------------------------

    @Test
    fun `a query is trimmed and its whitespace collapsed`() {
        // Matched to the server's own normalisation, which is what makes a
        // stray double space hit the same cache entry rather than costing a
        // second request.
        assertEquals("chiang mai", PlaceSearchRules.normalize("  chiang   mai "))
    }

    @Test
    fun `a query too short to be worth a request is not searchable`() {
        assertFalse(PlaceSearchRules.isSearchable("a"))
        assertFalse(PlaceSearchRules.isSearchable("   "))
        assertFalse(PlaceSearchRules.isSearchable(""))
        assertTrue(PlaceSearchRules.isSearchable("Pa"))
        // Whitespace does not count towards the length.
        assertFalse(PlaceSearchRules.isSearchable(" a "))
    }

    @Test
    fun `a query longer than the server will take is not searchable`() {
        assertTrue(PlaceSearchRules.isSearchable("x".repeat(PlaceSearchRules.MAX_QUERY_LENGTH)))
        assertFalse(PlaceSearchRules.isSearchable("x".repeat(PlaceSearchRules.MAX_QUERY_LENGTH + 1)))
    }

    // --- the debounce -----------------------------------------------------

    @Test
    fun `typing a name costs one request, not one per keystroke`() = runTest {
        val api = Recorder { listOf(pai) }
        val controller = PlaceSearchController(this, api)

        "Chiang Mai".forEachIndexed { index, _ ->
            controller.onQueryChanged("Chiang Mai".take(index + 1))
            // Faster than the debounce, which is what typing is.
            advanceTimeBy(100)
        }
        advanceUntilIdle()

        assertEquals(listOf("Chiang Mai"), api.queries)
    }

    @Test
    fun `nothing is sent until the typing stops`() = runTest {
        val api = Recorder { listOf(pai) }
        val controller = PlaceSearchController(this, api)

        controller.onQueryChanged("Pai")
        advanceTimeBy(PlaceSearchRules.DEBOUNCE_MS - 1)
        assertEquals(emptyList<String>(), api.queries)

        advanceTimeBy(2)
        assertEquals(listOf("Pai"), api.queries)
    }

    @Test
    fun `a query below the threshold is never sent at all`() = runTest {
        val api = Recorder()
        val controller = PlaceSearchController(this, api)

        controller.onQueryChanged("P")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), api.queries)
        // And the field still shows what was typed: only the request waits.
        assertEquals("P", controller.state.value.query)
        assertFalse(controller.state.value.searching)
    }

    @Test
    fun `deleting back below the threshold closes the panel`() = runTest {
        val api = Recorder { listOf(pai) }
        val controller = PlaceSearchController(this, api)

        controller.onQueryChanged("Pai")
        advanceUntilIdle()
        assertEquals(1, controller.state.value.results.size)

        controller.onQueryChanged("P")
        advanceUntilIdle()

        // Results for a query that is no longer on screen are worse than none.
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.hasPanel)
    }

    @Test
    fun `the spinner shows from the keystroke, not from the request`() = runTest {
        val controller = PlaceSearchController(this, Recorder { listOf(pai) })

        controller.onQueryChanged("Pai")

        // Otherwise the field sits there doing visibly nothing for the whole
        // debounce, which reads as a broken search rather than a patient one.
        assertTrue(controller.state.value.searching)
        advanceUntilIdle()
        assertFalse(controller.state.value.searching)
    }

    @Test
    fun `the query is normalised before it is sent`() = runTest {
        val api = Recorder()
        val controller = PlaceSearchController(this, api)

        controller.onQueryChanged("  chiang   mai  ")
        advanceUntilIdle()

        assertEquals(listOf("chiang mai"), api.queries)
    }

    @Test
    fun `text longer than the field allows is cut rather than sent`() = runTest {
        val api = Recorder()
        val controller = PlaceSearchController(this, api)

        controller.onQueryChanged("x".repeat(500))
        advanceUntilIdle()

        assertEquals(PlaceSearchRules.MAX_QUERY_LENGTH, controller.state.value.query.length)
        assertEquals(1, api.queries.size)
    }

    // --- results ----------------------------------------------------------

    @Test
    fun `results land against the query that asked for them`() = runTest {
        val controller = PlaceSearchController(this, Recorder { listOf(pai) })

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf(pai), state.results)
        assertTrue(state.searched)
        assertNull(state.problem)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `an answer for an abandoned query never overwrites a newer one`() = runTest {
        // The classic search-as-you-type bug: the reply to "chian" arrives
        // after the reply to "chiang mai" and paints over it, which looks
        // exactly like the search being wrong.
        val slowFirst = Recorder { query ->
            if (query == "Pai") {
                kotlinx.coroutines.delay(5_000)
                listOf(pai.copy(name = "stale"))
            } else {
                listOf(pai.copy(name = "fresh"))
            }
        }
        val controller = PlaceSearchController(this, slowFirst)

        controller.onQueryChanged("Pai")
        advanceTimeBy(PlaceSearchRules.DEBOUNCE_MS + 1)
        controller.onQueryChanged("Pai City")
        advanceUntilIdle()

        assertEquals(listOf("fresh"), controller.state.value.results.map { it.name })
    }

    @Test
    fun `nothing found is said out loud, not left blank`() = runTest {
        val controller = PlaceSearchController(this, Recorder { emptyList() })

        controller.onQueryChanged("zzzzz")
        advanceUntilIdle()

        // "Nothing is called that" and "nothing has been asked yet" look
        // identical in the fields above and read completely differently.
        assertTrue(controller.state.value.isEmpty)
        assertTrue(controller.state.value.hasPanel)
    }

    /** A lookup that fails the way the real one does, with a status. */
    private fun failingWith(status: Int?, message: String = "boom") = object : PlaceLookup {
        override suspend fun search(query: String, limit: Int, near: LatLng?): List<Place> =
            throw ApiException(message, status)
    }

    @Test
    fun `a server with no search route is not reported as a missing place`() = runTest {
        // The bug, exactly: the backend had never been deployed, Express 404ed
        // an unknown path, and the panel said "That's no longer there." about
        // Bangkok. A 404 here means the route is absent, and nothing else —
        // the search route answers no-matches with 200 and an empty list.
        val controller = PlaceSearchController(this, failingWith(404))

        controller.onQueryChanged("Bangkok")
        advanceUntilIdle()

        assertEquals(PlaceSearchProblem.NOT_DEPLOYED, controller.state.value.problem)
        // And emphatically not "nothing is called that".
        assertFalse(controller.state.value.isEmpty)
    }

    @Test
    fun `an unconfigured server is told apart from an undeployed one`() = runTest {
        val controller = PlaceSearchController(this, failingWith(503))

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        assertEquals(PlaceSearchProblem.NOT_CONFIGURED, controller.state.value.problem)
        assertFalse(controller.state.value.isEmpty)
        assertFalse(controller.state.value.searching)
    }

    @Test
    fun `losing signal is not reported as the server refusing`() = runTest {
        // ApiClient throws with no status when the request never got an
        // answer. That is worth retrying; a server that said no is not.
        val controller = PlaceSearchController(this, failingWith(null))

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        assertEquals(PlaceSearchProblem.OFFLINE, controller.state.value.problem)
    }

    @Test
    fun `a rate limit and an upstream failure are different problems`() = runTest {
        val limited = PlaceSearchController(this, failingWith(429))
        limited.onQueryChanged("Pai")
        advanceUntilIdle()
        assertEquals(PlaceSearchProblem.TOO_MANY, limited.state.value.problem)

        val upstream = PlaceSearchController(this, failingWith(502))
        upstream.onQueryChanged("Pai")
        advanceUntilIdle()
        assertEquals(PlaceSearchProblem.UPSTREAM, upstream.state.value.problem)
    }

    @Test
    fun `an unrecognised failure keeps the server's own sentence`() = runTest {
        val controller = PlaceSearchController(this, failingWith(418, "the server is a teapot"))

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        assertEquals(PlaceSearchProblem.UNKNOWN, controller.state.value.problem)
        assertEquals("the server is a teapot", controller.state.value.serverMessage)
    }

    @Test
    fun `a failure clears when the next search succeeds`() = runTest {
        var fail = true
        val controller = PlaceSearchController(this, object : PlaceLookup {
            override suspend fun search(query: String, limit: Int, near: LatLng?): List<Place> {
                if (fail) throw ApiException("boom", 502)
                return listOf(pai)
            }
        })

        controller.onQueryChanged("Pai")
        advanceUntilIdle()
        assertEquals(PlaceSearchProblem.UPSTREAM, controller.state.value.problem)

        fail = false
        controller.onQueryChanged("Pai City")
        advanceUntilIdle()

        assertNull(controller.state.value.problem)
        assertNull(controller.state.value.serverMessage)
        assertEquals(listOf(pai), controller.state.value.results)
    }

    @Test
    fun `an expired session is handed on rather than shown as a search failure`() = runTest {
        var expired = false
        val controller = PlaceSearchController(
            scope = this,
            api = object : PlaceLookup {
                override suspend fun search(query: String, limit: Int, near: LatLng?): List<Place> =
                    throw SessionExpiredException()
            },
            onSessionExpired = { expired = true },
        )

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        assertTrue(expired)
        // Signing out is about to happen; an error line about the search on
        // the way past would be noise.
        assertNull(controller.state.value.problem)
    }

    @Test
    fun `a build with no search configured asks nothing and says nothing`() = runTest {
        val controller = PlaceSearchController(this, api = null)

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        assertFalse(controller.state.value.searching)
        assertTrue(controller.state.value.results.isEmpty())
        assertNull(controller.state.value.problem)
    }

    @Test
    fun `clearing empties the box and cancels what was pending`() = runTest {
        val api = Recorder { listOf(pai) }
        val controller = PlaceSearchController(this, api)

        controller.onQueryChanged("Pai")
        controller.clear()
        advanceUntilIdle()

        assertEquals("", controller.state.value.query)
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.hasPanel)
        assertEquals(emptyList<String>(), api.queries)
    }
    // --- biasing towards the rider ---------------------------------------

    @Test
    fun `a search carries the rider's position when there is one`() = runTest {
        val api = Recorder()
        val controller = PlaceSearchController(this, api)
        controller.near = LatLng(18.7883, 98.9853)

        controller.onQueryChanged("วัดร่องขุ่น")
        advanceUntilIdle()

        // Thai reaches the controller intact — nothing here trims, truncates
        // or re-encodes it — and the bias rides along with it.
        assertEquals(listOf("วัดร่องขุ่น"), api.queries)
        assertEquals(listOf(LatLng(18.7883, 98.9853)), api.biases)
    }

    @Test
    fun `a phone with no fix searches unbiased rather than not at all`() = runTest {
        val api = Recorder()
        val controller = PlaceSearchController(this, api)

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        // The bias is a hint. A missing hint must never cost a rider a search.
        assertEquals(listOf("Pai"), api.queries)
        assertEquals(listOf<LatLng?>(null), api.biases)
    }

    @Test
    fun `a search takes the position that was current when it went out`() = runTest {
        val api = Recorder()
        val controller = PlaceSearchController(this, api)

        controller.near = LatLng(18.7883, 98.9853)
        controller.onQueryChanged("market")
        advanceUntilIdle()

        // The rider moved a province between searches; the second one is
        // ranked against where they are now, not where they opened the map.
        controller.near = LatLng(13.7563, 100.5018)
        controller.onQueryChanged("market again")
        advanceUntilIdle()

        assertEquals(
            listOf(LatLng(18.7883, 98.9853), LatLng(13.7563, 100.5018)),
            api.biases,
        )
    }

    @Test
    fun `a Thai name is long enough to search and is not normalised away`() {
        // The reported symptom was "วัดร่องขุ่น finds nothing", and the first
        // two things to rule out are the two this app controls: a length rule
        // that refuses it, and a normaliser that changes it.
        assertTrue(PlaceSearchRules.isSearchable("วัดร่องขุ่น"))
        assertEquals("วัดร่องขุ่น", PlaceSearchRules.normalize("  วัดร่องขุ่น  "))
        // Two Thai characters is a searchable query, the same as two Latin ones.
        assertTrue(PlaceSearchRules.isSearchable("วัด"))
    }

    // --- two sources, one list --------------------------------------------

    @Test
    fun `the riders own places come first and are marked as theirs`() = runTest {
        val api = Recorder { listOf(pai) }
        val shelf = Shelf { listOf(suanDok) }
        val controller = PlaceSearchController(this, api, shelf)

        controller.onQueryChanged("ปตท สวนดอก")
        advanceUntilIdle()

        val results = controller.state.value.results
        // First, because they are the answer the geocoder could not give. A
        // row somebody wrote down because searching found nothing must not sit
        // below eight towns whose names merely resemble it.
        assertEquals(listOf("ปตท สวนดอก", "Pai"), results.map { it.name })
        assertEquals(PlaceSource.SHARED, results[0].source)
        assertEquals(PlaceSource.GEOCODER, results[1].source)
        assertEquals(1L, results[0].sharedId)
        assertTrue(controller.state.value.hasShared)
    }

    @Test
    fun `both lists are asked at once, and both get the query`() = runTest {
        val api = Recorder { listOf(pai) }
        val shelf = Shelf { listOf(suanDok) }
        val controller = PlaceSearchController(this, api, shelf)

        controller.onQueryChanged("ปตท สวนดอก")
        advanceUntilIdle()

        assertEquals(listOf("ปตท สวนดอก"), api.queries)
        assertEquals(listOf("ปตท สวนดอก"), shelf.queries)
    }

    @Test
    fun `a geocoder that is down does not hide the riders own places`() = runTest {
        // The whole reason this list is not folded into /geocode/search. That
        // route answers 503 on a server with no API key, 429 when the day's
        // quota is gone and 502 when LocationIQ is unreachable — and on every
        // one of those days the place somebody wrote down is still here.
        val api = Recorder { throw ApiException("nope", 503) }
        val shelf = Shelf { listOf(suanDok) }
        val controller = PlaceSearchController(this, api, shelf)

        controller.onQueryChanged("ปตท สวนดอก")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf("ปตท สวนดอก"), state.results.map { it.name })
        // And no error over the top of it: a failure with the answer behind it
        // would send somebody looking for a fault while the row they wanted is
        // on screen.
        assertNull(state.problem)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `a geocoder failure with nothing behind it is still reported`() = runTest {
        val api = Recorder { throw ApiException("nope", 503) }
        val shelf = Shelf { emptyList() }
        val controller = PlaceSearchController(this, api, shelf)

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        // Nothing found anywhere, so the reason matters again — and 503 means
        // the server has no key, which is somebody's to fix rather than the
        // rider's to retry.
        assertEquals(PlaceSearchProblem.NOT_CONFIGURED, controller.state.value.problem)
        assertTrue(controller.state.value.results.isEmpty())
    }

    @Test
    fun `a shared list that is down does not break the search`() = runTest {
        // The mirror case, and the one a backend older than this app produces:
        // /places is not there yet, so it 404s. That must not read as a broken
        // search when the geocoder is answering perfectly well.
        val api = Recorder { listOf(pai) }
        val shelf = Shelf { throw ApiException("no such route", 404) }
        val controller = PlaceSearchController(this, api, shelf)

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf("Pai"), state.results.map { it.name })
        assertFalse(state.hasShared)
        assertNull(state.problem)
    }

    @Test
    fun `a shared list that answers nonsense does not take the search down`() = runTest {
        // Anything at all, not just ApiException: a malformed body throws a
        // JSONException, and an async that throws would cancel the search
        // around it rather than losing one of its two halves.
        val api = Recorder { listOf(pai) }
        val shelf = Shelf { throw IllegalStateException("garbage") }
        val controller = PlaceSearchController(this, api, shelf)

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        assertEquals(listOf("Pai"), controller.state.value.results.map { it.name })
    }

    @Test
    fun `with no geocoder at all the riders own places still answer`() = runTest {
        // A build or a preview with no search configured. Not a failure, and
        // not reported as one.
        val shelf = Shelf { listOf(suanDok) }
        val controller = PlaceSearchController(this, api = null, shared = shelf)

        controller.onQueryChanged("ปตท สวนดอก")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf("ปตท สวนดอก"), state.results.map { it.name })
        assertNull(state.problem)
    }

    @Test
    fun `the debounce covers both lists, not just the geocoder`() = runTest {
        // Otherwise typing nine letters would be one geocoder request and nine
        // requests to this app's own server — which is cheaper but is still a
        // rider's battery and a phone's radio.
        val api = Recorder { listOf(pai) }
        val shelf = Shelf { listOf(suanDok) }
        val controller = PlaceSearchController(this, api, shelf)

        "Chiang Mai".forEachIndexed { index, _ ->
            controller.onQueryChanged("Chiang Mai".take(index + 1))
            advanceTimeBy(100)
        }
        advanceUntilIdle()

        assertEquals(1, api.queries.size)
        assertEquals(1, shelf.queries.size)
    }

    @Test
    fun `a stale answer never paints over a newer query`() = runTest {
        // The classic search-as-you-type bug, checked with two sources in
        // flight rather than one: the guard has to compare against the field,
        // not against whichever half came back last.
        val api = Recorder { query -> if (query == "Pai") listOf(pai) else emptyList() }
        val shelf = Shelf { emptyList() }
        val controller = PlaceSearchController(this, api, shelf)

        controller.onQueryChanged("Pai")
        advanceUntilIdle()
        controller.onQueryChanged("Nan")
        advanceUntilIdle()

        assertEquals("Nan", controller.state.value.query)
        assertTrue(controller.state.value.results.isEmpty())
    }

    @Test
    fun `merging keeps the riders own places ahead without dropping duplicates`() {
        // A shared place and an OSM object at the same spot are two different
        // claims about it, and collapsing them silently would hide whichever
        // one a rider was looking for. They read differently on screen, which
        // is enough.
        val shared = suanDok.asPlace()
        val same = Place("ปตท สวนดอก", "somewhere", 18.789, 98.971, kind = null, osmId = "5")

        assertEquals(
            listOf(PlaceSource.SHARED, PlaceSource.GEOCODER),
            mergePlaceResults(listOf(shared), listOf(same)).map { it.source },
        )
        assertEquals(2, mergePlaceResults(listOf(shared), listOf(same)).size)
    }

}
