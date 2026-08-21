package app.ptrip.tracktrip.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.ApiClient
import app.ptrip.tracktrip.data.ApiException
import app.ptrip.tracktrip.data.AppSettings
import app.ptrip.tracktrip.data.Invite
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.SuggestedInvitee
import app.ptrip.tracktrip.data.TokenStore
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.location.SharingController
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the trip screen says back about the things it does to a trip.
 *
 * Ending a trip, leaving one and renaming one are all writes whose only signal
 * used to be the screen changing underneath — and two of them navigate away as
 * they land, so there was no screen left to put an answer on. That is why the
 * bar lives above the navigation stack and why these tests read the channel
 * rather than the screen: the question is whether the rider is told at all.
 *
 * Inviting is here for the case with two answers in it. One press of Invite is
 * one request per address, and a press that got three riders on and bounced one
 * must not read as either "sent" or "failed".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripActionFeedbackTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun releaseDispatcher() = Dispatchers.resetMain()

    private val running = Trip(
        id = 1L,
        name = "Sunday run",
        ownerId = 7L,
        status = Trip.STATUS_ACTIVE,
        role = Trip.ROLE_OWNER,
    )

    /**
     * A server that answers the reads the screen makes on the way in, and
     * whatever each test needs of the writes.
     *
     * Subclassing [TripApi] rather than an interface for the same reason the
     * route tests do: the real class is what the view model takes, and a
     * parallel interface would be a second definition of the same API to keep
     * in step.
     */
    private open class FakeServer(private val trip: Trip) : TripApi(
        ApiClient("http://localhost", TokenStore(ApplicationProvider.getApplicationContext()))
    ) {
        override suspend fun listTrips(all: Boolean) = listOf(trip)
        override suspend fun members(tripId: Long): List<MemberPosition> = emptyList()
        override suspend fun suggestedInvitees(tripId: Long): List<SuggestedInvitee> = emptyList()
    }

    private fun TestScope.collecting(center: FeedbackCenter): List<FeedbackMessage> {
        val seen = mutableListOf<FeedbackMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            center.messages.collect { seen += it }
        }
        return seen
    }

    private fun model(server: TripApi, center: FeedbackCenter): TripDetailViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = AppSettings(context)
        return TripDetailViewModel(
            tripId = 1L,
            tripApi = server,
            sharingController = SharingController(context, server, settings),
            settings = settings,
            feedback = center,
            onSessionExpired = {},
        )
    }

    @Test
    fun `ending a trip says so`() {
        runTest(dispatcher) {
            val center = FeedbackCenter()
            val seen = collecting(center)
            val server = object : FakeServer(running) {
                override suspend fun endTrip(tripId: Long) = running.copy(status = "ended")
            }

            model(server, center).endTrip()
            testScheduler.advanceUntilIdle()

            assertEquals(FeedbackText.Res(R.string.feedback_trip_ended), seen.single().text)
        }
    }

    @Test
    fun `a trip that would not end says why, and offers a real second attempt`() {
        runTest(dispatcher) {
            val center = FeedbackCenter()
            val seen = collecting(center)
            var attempts = 0
            val server = object : FakeServer(running) {
                override suspend fun endTrip(tripId: Long): Trip {
                    attempts += 1
                    throw ApiException("only the trip owner can do this", 403)
                }
            }

            model(server, center).endTrip()
            testScheduler.advanceUntilIdle()

            val message = seen.single()
            assertEquals(FeedbackTone.FAILURE, message.tone)
            assertEquals(FeedbackText.Plain("only the trip owner can do this"), message.text)

            assertNotNull(message.onRetry)
            message.onRetry!!.invoke()
            testScheduler.advanceUntilIdle()
            assertEquals(2, attempts)
        }
    }

    @Test
    fun `leaving is announced before the screen it was pressed on goes away`() {
        runTest(dispatcher) {
            val center = FeedbackCenter()
            val seen = collecting(center)
            var left = false
            val server = object : FakeServer(running.copy(role = "member")) {
                override suspend fun leaveTrip(tripId: Long) = Unit
            }

            // `onLeft` is what pops the back stack. The answer has to be
            // raised before it — and to survive it, which is the whole reason
            // the bar is drawn above the navigation stack rather than by this
            // screen.
            model(server, center).leaveTrip { left = true }
            testScheduler.advanceUntilIdle()

            assertTrue(left)
            assertEquals(FeedbackText.Res(R.string.feedback_trip_left), seen.single().text)
        }
    }

    @Test
    fun `renaming a trip says so once it has landed`() {
        runTest(dispatcher) {
            val center = FeedbackCenter()
            val seen = collecting(center)
            val server = object : FakeServer(running) {
                override suspend fun renameTrip(tripId: Long, name: String) = running.copy(name = name)
            }

            model(server, center).rename("Sunday morning run") {}
            testScheduler.advanceUntilIdle()

            assertEquals(FeedbackText.Res(R.string.feedback_trip_saved), seen.single().text)
        }
    }

    @Test
    fun `one press of Invite that all lands is one answer, not four`() {
        runTest(dispatcher) {
            val center = FeedbackCenter()
            val seen = collecting(center)
            val server = object : FakeServer(running) {
                override suspend fun invite(tripId: Long, email: String) =
                    Invite(id = 1L, tripId = tripId, email = email, tripName = "Sunday run")
            }

            model(server, center).sendInvites(listOf("a@gmail.com", "b@gmail.com", "c@gmail.com"))
            testScheduler.advanceUntilIdle()

            // The bar shows the newest message and nothing else, so three
            // answers to one press would be two flashes and a sentence.
            assertEquals(
                FeedbackText.Res(R.string.feedback_invites_sent, listOf(3)),
                seen.single().text,
            )
        }
    }

    @Test
    fun `a press with one refusal in it reports the refusal, and retries only that address`() {
        runTest(dispatcher) {
            val center = FeedbackCenter()
            val seen = collecting(center)
            val asked = mutableListOf<String>()
            val server = object : FakeServer(running) {
                override suspend fun invite(tripId: Long, email: String): Invite {
                    asked += email
                    if (email == "b@gmail.com") throw ApiException("already a member", 409)
                    return Invite(id = 1L, tripId = tripId, email = email, tripName = "Sunday run")
                }
            }
            val detail = model(server, center)

            detail.sendInvites(listOf("a@gmail.com", "b@gmail.com", "c@gmail.com"))
            testScheduler.advanceUntilIdle()

            // A failure does not stop the rest: the addresses are independent,
            // and one already on the trip must not cost the other two.
            assertEquals(listOf("a@gmail.com", "b@gmail.com", "c@gmail.com"), asked)
            // What landed is on the form, beside what did not.
            assertEquals(listOf("a@gmail.com", "c@gmail.com"), detail.uiState.value.inviteSent)

            val message = seen.single()
            assertEquals(FeedbackTone.FAILURE, message.tone)
            assertEquals(FeedbackText.Plain("b@gmail.com: already a member"), message.text)

            // Retry sends the bounce and nothing else. Re-inviting the two who
            // are already on the trip would earn "already a member" twice, for
            // no reason a rider asked for.
            asked.clear()
            message.onRetry!!.invoke()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("b@gmail.com"), asked)
        }
    }
}
