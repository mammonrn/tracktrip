package app.ptrip.tracktrip.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a failed search actually means.
 *
 * This is the mapping that was missing, and its absence did real damage: with
 * the backend not yet deployed, `/geocode/search` 404ed, the generic wording
 * for a 404 is "That's no longer there.", and three searches for Bangkok,
 * Chiang Rai and เซ็นทรัลเชียงราย all reported that the place was gone. The
 * message looked like ordinary app copy, so the search was blamed instead of
 * the deploy.
 */
class PlaceSearchProblemTest {

    @Test
    fun `a 404 means the server has no search route, not a missing place`() {
        // The search route never answers 404 itself — no matches is a 200
        // with an empty list, and the backend has a test that says so. A 404
        // therefore only ever comes from Express having no such route, i.e. a
        // server older than this app.
        assertEquals(PlaceSearchProblem.NOT_DEPLOYED, placeSearchProblem(404))
    }

    @Test
    fun `a 503 means the key is missing, which is a different fix`() {
        // Both this and NOT_DEPLOYED are "somebody has to fix the server",
        // but one needs a deploy and the other needs a line in .env — and the
        // person reading the message is usually the one who applies it.
        assertEquals(PlaceSearchProblem.NOT_CONFIGURED, placeSearchProblem(503))
    }

    @Test
    fun `a 429 is a budget, not a failure`() {
        assertEquals(PlaceSearchProblem.TOO_MANY, placeSearchProblem(429))
    }

    @Test
    fun `no status at all means the request never arrived`() {
        // ApiClient throws without a status when the call threw IOException:
        // there was no response to take one from. That is "no signal", which
        // is worth retrying, and nothing like a server that said no.
        assertEquals(PlaceSearchProblem.OFFLINE, placeSearchProblem(null))
    }

    @Test
    fun `the server's own trouble is reported as the upstream's`() {
        assertEquals(PlaceSearchProblem.UPSTREAM, placeSearchProblem(502))
        assertEquals(PlaceSearchProblem.UPSTREAM, placeSearchProblem(504))
        assertEquals(PlaceSearchProblem.UPSTREAM, placeSearchProblem(500))
    }

    @Test
    fun `anything unrecognised falls through to the server's own words`() {
        assertEquals(PlaceSearchProblem.UNKNOWN, placeSearchProblem(418))
        assertEquals(PlaceSearchProblem.UNKNOWN, placeSearchProblem(400))
    }

    @Test
    fun `an ApiException carries the status it came from`() {
        // Without this the mapping above has nothing to map. It used to carry
        // only a sentence, which is why the wrong sentence was all there was.
        assertEquals(404, ApiException("gone", 404).status)
        assertEquals(null, ApiException("no connection").status)
        assertEquals(401, SessionExpiredException().status)
    }

    @Test
    fun `a place search failure keeps both the reason and the wording`() {
        val e = PlaceSearchException(PlaceSearchProblem.NOT_DEPLOYED, "Cannot GET", 404)

        assertEquals(PlaceSearchProblem.NOT_DEPLOYED, e.problem)
        assertEquals(404, e.status)
        assertEquals("Cannot GET", e.message)
    }
}
