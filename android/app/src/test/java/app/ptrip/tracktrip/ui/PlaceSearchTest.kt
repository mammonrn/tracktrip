package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.Place
import app.ptrip.tracktrip.data.PlaceLookup
import app.ptrip.tracktrip.data.SessionExpiredException
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
        override suspend fun search(query: String, limit: Int): List<Place> {
            queries += query
            return answer(query)
        }
    }

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
        assertNull(state.error)
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

    @Test
    fun `an unconfigured server's message is shown rather than "nothing found"`() = runTest {
        val message = "Place search is not configured on this server."
        val controller = PlaceSearchController(this, object : PlaceLookup {
            override suspend fun search(query: String, limit: Int): List<Place> =
                throw ApiException(message)
        })

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        // The likeliest failure by a distance, and the one that must not read
        // as "there is no such place".
        assertEquals(message, controller.state.value.error)
        assertFalse(controller.state.value.isEmpty)
        assertFalse(controller.state.value.searching)
    }

    @Test
    fun `an expired session is handed on rather than shown as a search failure`() = runTest {
        var expired = false
        val controller = PlaceSearchController(
            scope = this,
            api = object : PlaceLookup {
                override suspend fun search(query: String, limit: Int): List<Place> =
                    throw SessionExpiredException()
            },
            onSessionExpired = { expired = true },
        )

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        assertTrue(expired)
        // Signing out is about to happen; an error line about the search on
        // the way past would be noise.
        assertNull(controller.state.value.error)
    }

    @Test
    fun `a build with no search configured asks nothing and says nothing`() = runTest {
        val controller = PlaceSearchController(this, api = null)

        controller.onQueryChanged("Pai")
        advanceUntilIdle()

        assertFalse(controller.state.value.searching)
        assertTrue(controller.state.value.results.isEmpty())
        assertNull(controller.state.value.error)
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
}
