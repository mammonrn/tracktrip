package app.ptrip.tracktrip.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.ptrip.tracktrip.R
import app.ptrip.tracktrip.data.ApiClient
import app.ptrip.tracktrip.data.AppSettings
import app.ptrip.tracktrip.data.JoinCode
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.SuggestedInvitee
import app.ptrip.tracktrip.data.TokenStore
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.location.SharingController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * One press of "share invite" is one invitation, said once.
 *
 * The bug these were written for did not send twice and did not call the
 * builder twice: a single press produced a single message whose text contained
 * the invitation twice over. `ACTION_SEND` carries two text extras, and outside
 * email nothing keeps them apart — SMS/MMS, LINE and most chat targets paste
 * `EXTRA_SUBJECT` on the front of `EXTRA_TEXT` and send the result as one
 * message. The subject restated the body's opening sentence, so that is what
 * arrived: the greeting, then the greeting again with the trip name in it.
 *
 * Counting sends would not have caught it — there was only ever one. Reading
 * the message the way a receiving app assembles it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InviteMessageTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun releaseDispatcher() = Dispatchers.resetMain()

    /** The `ACTION_SEND` inside the chooser the app actually starts. */
    private fun sendIntent(tripName: String = TRIP, code: String = CODE): Intent =
        requireNotNull(
            inviteShareIntent(context, tripName, code)
                .getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        ) { "the chooser has no target intent" }

    /**
     * The message as a receiving app builds it: every text extra it understands,
     * in the order they get pasted together. For a target that honours
     * `EXTRA_SUBJECT` as a header this is one line too long; for the chat apps
     * riders actually use, it is the message.
     */
    private fun Intent.asOneMessage(): String = listOfNotNull(
        getStringExtra(Intent.EXTRA_SUBJECT),
        getStringExtra(Intent.EXTRA_TEXT),
    ).joinToString("\n")

    @Test
    fun `one press produces the invitation exactly once`() {
        val message = sendIntent().asOneMessage()

        // Built here from the template rather than compared to a literal, so
        // this stays a statement about duplication and not about wording.
        val expected = context.getString(
            R.string.invite_share_body,
            TRIP,
            CODE,
            "https://ptrip.app/join/$CODE",
        )
        assertEquals(expected, message)

        // The opening sentence is the part that was arriving twice.
        val opening = expected.substringBefore("\n")
        assertEquals("'$opening' in: $message", 1, message.occurrencesOf(opening))
    }

    @Test
    fun `nothing is carried in a second extra for a chat app to fold in`() {
        // The fix, stated as the thing that must stay true. EXTRA_SUBJECT is a
        // header only to email; everywhere else it becomes the first line of
        // the body, which is how one press came to say it twice. Anything the
        // invite needs to say belongs in invite_share_body.
        assertNull(sendIntent().getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(sendIntent().getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `the Thai invite is not doubled either`() {
        // The Thai body opens with its own greeting, so a subject in Thai would
        // have doubled it in Thai. Worth its own case because the strings are
        // per-locale and the duplication was reported on a Thai phone.
        val thai = inThai()
        val message = requireNotNull(
            inviteShareIntent(thai, TRIP, CODE)
                .getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        ).asOneMessage()

        val expected = thai.getString(
            R.string.invite_share_body,
            TRIP,
            CODE,
            "https://ptrip.app/join/$CODE",
        )
        assertTrue("Thai strings did not load: $expected", expected.contains("ทริป"))
        assertEquals(expected, message)
        assertEquals(1, message.occurrencesOf(expected.substringBefore("\n")))
    }

    @Test
    fun `no line of the message is repeated`() {
        // Broader than the opening sentence: whatever a later change adds, it
        // must not be added in two places.
        val lines = sendIntent().asOneMessage()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val repeated = lines.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals("repeated: $repeated", emptySet<String>(), repeated)
    }

    @Test
    fun `the message carries the code both ways round`() {
        // Not duplication: the code is readable text for somebody typing it in
        // and part of the link for somebody tapping it. Asserted so that a
        // future de-duplication does not "fix" one of them away.
        val message = sendIntent().asOneMessage()

        assertTrue(message, message.contains("Code: $CODE"))
        assertTrue(message, message.contains("https://ptrip.app/join/$CODE"))
        assertEquals(CODE, joinCodeFrom("https://ptrip.app/join/$CODE"))
    }

    @Test
    fun `one press of the button issues one code and opens one sheet`() {
        // The other half of "once": the screen asks for a code, the sheet is
        // opened for it, and clearing it is what stops a recomposition from
        // opening a second sheet on the same code.
        runTest(dispatcher) {
            var issued = 0
            val server = object : FakeServer(RUNNING) {
                override suspend fun createJoinCode(tripId: Long): JoinCode {
                    issued++
                    return JoinCode(tripId, CODE, "2026-08-21T12:00:00Z")
                }
            }
            val model = model(server)

            model.requestShareLink()
            testScheduler.advanceUntilIdle()

            assertEquals(1, issued)
            val pending = requireNotNull(model.uiState.value.pendingShareCode)
            assertEquals(CODE, pending.code)

            model.shareLinkConsumed()
            assertNull(model.uiState.value.pendingShareCode)
        }
    }

    @Test
    fun `holding the button down does not stack up codes`() {
        // Two presses landing inside one in-flight request. The guard is in the
        // view model; without it the second press would issue a second code and
        // retire the first, so the link already in the share sheet would be dead.
        runTest(dispatcher) {
            var issued = 0
            val server = object : FakeServer(RUNNING) {
                override suspend fun createJoinCode(tripId: Long): JoinCode {
                    issued++
                    return JoinCode(tripId, CODE, "2026-08-21T12:00:00Z")
                }
            }
            val model = model(server)

            model.requestShareLink()
            model.requestShareLink()
            testScheduler.advanceUntilIdle()

            assertEquals(1, issued)
        }
    }

    @Test
    fun `a trip with no name still gets a message and not a crash`() {
        // The screen falls back to "Untitled trip" before it gets here, but the
        // builder is a function anyone can call, and %1$s with an empty string
        // must not turn into a message about nothing.
        val message = sendIntent(tripName = "").asOneMessage()

        assertFalse(message.isBlank())
        assertTrue(message, message.contains("https://ptrip.app/join/$CODE"))
    }

    private fun String.occurrencesOf(needle: String): Int =
        if (needle.isEmpty()) 0 else split(needle).size - 1

    /** The same app, read in Thai. */
    private fun inThai(): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag("th"))
        return context.createConfigurationContext(configuration)
    }

    private fun model(server: TripApi): TripDetailViewModel {
        val settings = AppSettings(context)
        return TripDetailViewModel(
            tripId = 1L,
            tripApi = server,
            sharingController = SharingController(context, server, settings),
            settings = settings,
            feedback = FeedbackCenter(),
            onSessionExpired = {},
        )
    }

    private open class FakeServer(private val trip: Trip) : TripApi(
        ApiClient("http://localhost", TokenStore(ApplicationProvider.getApplicationContext()))
    ) {
        override suspend fun listTrips(all: Boolean) = listOf(trip)
        override suspend fun members(tripId: Long): List<MemberPosition> = emptyList()
        override suspend fun suggestedInvitees(tripId: Long): List<SuggestedInvitee> = emptyList()
    }

    private companion object {
        const val TRIP = "Sunday run"
        const val CODE = "K7M2QRX9"

        val RUNNING = Trip(
            id = 1L,
            name = TRIP,
            ownerId = 7L,
            status = Trip.STATUS_ACTIVE,
            role = Trip.ROLE_OWNER,
        )
    }
}
