package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.PersonalPlace
import app.ptrip.tracktrip.data.PersonalPlaceStore
import app.ptrip.tracktrip.data.SharedPlace
import app.ptrip.tracktrip.data.SharedPlaceStore
import app.ptrip.tracktrip.map.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deleting a place, and what the app says about it.
 *
 * This is the delete the whole feedback pass started from. It removes a row
 * everybody on the server can see, it has no undo, and until now it said
 * nothing either way — the list reloaded and that was the entire signal, so a
 * delete that failed looked exactly like a delete that had worked and not been
 * drawn yet. A rider's only move was to press the cross again.
 *
 * So there are four things to hold down, and they are the four tests below:
 * a delete that lands says so, a delete that fails says so *and* offers a real
 * second attempt, the row says it is going while it goes, and a second press
 * during that is refused rather than sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceDeleteFeedbackTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun releaseDispatcher() = Dispatchers.resetMain()

    private val pump = SharedPlace(
        id = 8L,
        name = "PTT Doi Saket",
        lat = 18.8700,
        lng = 99.1300,
        createdBy = 1L,
        createdByName = "Poom",
    )

    private val home = PersonalPlace(id = 3L, label = "Home", name = "Nimman Soi 5", lat = 18.79, lng = 98.96)

    /**
     * A shared list that counts deletes and can be told to refuse them.
     *
     * [held] is what makes the in-flight half testable: while it is true the
     * delete never returns, which is the state a rider is looking at when they
     * reach for the cross a second time.
     */
    private class FakeShared(
        private val places: List<SharedPlace>,
        var refuseWith: String? = null,
    ) : SharedPlaceStore {
        var deletes = 0
        var held = false

        override suspend fun search(query: String, limit: Int, near: LatLng?) = places
        override suspend fun add(name: String, point: LatLng) = places.first()
        override suspend fun remove(id: Long) {
            deletes += 1
            if (held) kotlinx.coroutines.awaitCancellation()
            refuseWith?.let { throw ApiException(it, 403) }
        }
    }

    private class FakePersonal(private val places: List<PersonalPlace>) : PersonalPlaceStore {
        var deletes = 0
        override suspend fun list() = places
        override suspend fun add(label: String, name: String, point: LatLng) = places.first()
        override suspend fun remove(id: Long) {
            deletes += 1
        }
    }

    private fun TestScope.collecting(center: FeedbackCenter): List<FeedbackMessage> {
        val seen = mutableListOf<FeedbackMessage>()
        // Unconfined on purpose. `backgroundScope` work is only advanced once
        // the test body itself is idle, so a collector launched on the standard
        // dispatcher would still not be subscribed by the time the first
        // message is raised — and the flow has no replay to save it. Unconfined
        // subscribes on the spot, which is what the activity's collector does
        // too: it is already running before any screen can press anything.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            center.messages.collect { seen += it }
        }
        return seen
    }

    private fun model(
        center: FeedbackCenter,
        shared: SharedPlaceStore? = null,
        personal: PersonalPlaceStore? = null,
    ) = PlacesViewModel(
        sharedPlacesApi = shared,
        personalPlacesApi = personal,
        placeSearchApi = null,
        currentUserId = 1L,
        feedback = center,
        onSessionExpired = {},
    )

    @Test
    fun `a shared place that goes says so`() = runTest(dispatcher) {
        val center = FeedbackCenter()
        val seen = collecting(center)
        val api = FakeShared(listOf(pump))
        val places = model(center, shared = api)
        testScheduler.advanceUntilIdle()

        places.removeShared(pump.id)
        testScheduler.advanceUntilIdle()

        assertEquals(1, api.deletes)
        val message = seen.single()
        assertEquals(FeedbackTone.SUCCESS, message.tone)
        assertEquals(FeedbackText.Res(R.string.feedback_place_deleted_shared), message.text)
    }

    @Test
    fun `a private shortcut that goes is said differently, because it is a different thing`() =
        runTest(dispatcher) {
            val center = FeedbackCenter()
            val seen = collecting(center)
            val api = FakePersonal(listOf(home))
            val places = model(center, personal = api)
            testScheduler.advanceUntilIdle()

            places.removePersonal(home.id)
            testScheduler.advanceUntilIdle()

            assertEquals(1, api.deletes)
            // Not "Place deleted": a shortcut nobody else could see going away
            // is not the same event as a row the whole server was using.
            assertEquals(
                FeedbackText.Res(R.string.feedback_place_deleted_personal),
                seen.single().text,
            )
        }

    @Test
    fun `a refused delete says why, and its Retry really re-sends`() = runTest(dispatcher) {
        val center = FeedbackCenter()
        val seen = collecting(center)
        val api = FakeShared(listOf(pump), refuseWith = "you didn't add this place")
        val places = model(center, shared = api)
        testScheduler.advanceUntilIdle()

        places.removeShared(pump.id)
        testScheduler.advanceUntilIdle()

        val message = seen.single()
        assertEquals(FeedbackTone.FAILURE, message.tone)
        // The server's own sentence, not a re-worded one: it says which of the
        // two rules refused this, and the app must not drift from it.
        assertEquals(FeedbackText.Plain("you didn't add this place"), message.text)

        assertNotNull("a delete is retryable — nothing has to be rebuilt first", message.onRetry)
        message.onRetry!!.invoke()
        testScheduler.advanceUntilIdle()
        assertEquals("Retry sent a second DELETE, not just a dismissal", 2, api.deletes)
    }

    @Test
    fun `the row says it is going while it goes, and a second press is refused`() =
        runTest(dispatcher) {
            val center = FeedbackCenter()
            val api = FakeShared(listOf(pump)).apply { held = true }
            val places = model(center, shared = api)
            testScheduler.advanceUntilIdle()

            places.removeShared(pump.id)
            testScheduler.advanceUntilIdle()

            // What the card reads off to draw a spinner instead of the cross.
            assertTrue(pump.id in places.uiState.value.removingShared)

            // The impatient second tap. Before this guard it was a second
            // DELETE against a row the first was already removing, answered
            // 404 — a red bar for a delete that had worked exactly as asked.
            places.removeShared(pump.id)
            testScheduler.advanceUntilIdle()
            assertEquals(1, api.deletes)
        }
}
