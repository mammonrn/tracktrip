package app.ptrip.tracktrip.ui

import app.ptrip.tracktrip.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one channel every write in the app answers on.
 *
 * Held here rather than left to each view model's own test because the two
 * properties that matter are properties of the channel: that a failure with no
 * sentence behind it still says something, and that Retry is a real second
 * attempt rather than a button that closes the bar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackCenterTest {

    /**
     * Collects into a list, the way the activity collects into a snackbar.
     *
     * The collector has to be running *before* anything is raised: the flow
     * has no replay on purpose — an answer nobody was there for is not saved
     * up to be announced later — so a test that emitted first would be
     * asserting against an empty list and calling it a bug.
     */
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

    @Test
    fun `a success carries its wording as a resource, so it can be translated`() = runTest {
        val center = FeedbackCenter()
        val seen = collecting(center)

        center.succeeded(R.string.feedback_trip_joined, "Pai run")
        testScheduler.advanceUntilIdle()

        val message = seen.single()
        assertEquals(FeedbackTone.SUCCESS, message.tone)
        // A resource and its arguments, not a sentence: a view model has no
        // Context, and `StringCoverageTest` only sees strings in the XML.
        assertEquals(
            FeedbackText.Res(R.string.feedback_trip_joined, listOf("Pai run")),
            message.text,
        )
        assertNull("a success has nothing to retry", message.onRetry)
    }

    @Test
    fun `a failure shows the server's own sentence`() = runTest {
        val center = FeedbackCenter()
        val seen = collecting(center)

        center.failed("only the trip owner can do this")
        testScheduler.advanceUntilIdle()

        val message = seen.single()
        assertEquals(FeedbackTone.FAILURE, message.tone)
        assertEquals(FeedbackText.Plain("only the trip owner can do this"), message.text)
    }

    @Test
    fun `a failure with no sentence behind it still says something`() = runTest {
        val center = FeedbackCenter()
        val seen = collecting(center)

        // `ApiException.message` is nullable on Throwable, and a request that
        // died before any server had an opinion carries nothing. A blank red
        // bar would be worse than no bar.
        center.failed(null)
        center.failed("   ")
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                FeedbackText.Res(R.string.feedback_failed),
                FeedbackText.Res(R.string.feedback_failed),
            ),
            seen.map { it.text },
        )
    }

    @Test
    fun `retry runs the action it was given, not merely the bar away`() = runTest {
        val center = FeedbackCenter()
        val seen = collecting(center)

        var attempts = 0
        center.failed("timed out") { attempts += 1 }
        testScheduler.advanceUntilIdle()

        assertEquals(0, attempts)
        seen.single().onRetry!!.invoke()
        assertEquals(1, attempts)
    }

    @Test
    fun `a burst of answers all arrive, in the order they happened`() = runTest {
        val center = FeedbackCenter()
        val seen = collecting(center)

        // One press of Invite is one request per address, so four answers in a
        // row is a thing the app really does.
        repeat(4) { center.succeeded(R.string.feedback_invite_sent, "rider$it@gmail.com") }
        testScheduler.advanceUntilIdle()

        assertEquals(4, seen.size)
        assertTrue(
            seen.map { (it.text as FeedbackText.Res).args.single() } ==
                listOf("rider0@gmail.com", "rider1@gmail.com", "rider2@gmail.com", "rider3@gmail.com")
        )
    }
}
