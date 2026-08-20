package app.ptrip.tracktrip.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.ptrip.tracktrip.data.ApiClient
import app.ptrip.tracktrip.data.Invite
import app.ptrip.tracktrip.data.MemberPosition
import app.ptrip.tracktrip.data.TokenStore
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripApi
import app.ptrip.tracktrip.data.TripEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which trips are worth a podium read, and how few of them there are.
 *
 * `GET /trips` says nothing about who is where, so a podium costs one
 * `members` call per trip. That is affordable only because of what this pins
 * down: the trips on screen and no others, none twice, none at all without a
 * destination to have arrived at, and a ceiling on the burst when a rider
 * opens an archive with a year of riding in it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripPodiumLoadTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun releaseDispatcher() = Dispatchers.resetMain()

    private val pai = TripEndpoint(19.3583, 98.4400, "Pai")

    private fun trip(id: Long, destination: TripEndpoint? = pai) = Trip(
        id = id,
        name = "Ride $id",
        ownerId = 1L,
        status = "ended",
        role = Trip.ROLE_OWNER,
        destination = destination,
    )

    /** A server that counts who asked it for what. */
    private class CountingServer(
        private val trips: List<Trip>,
        private val atTheFinish: TripEndpoint?,
    ) : TripApi(
        ApiClient(
            baseUrl = "http://127.0.0.1:1/",
            tokenStore = TokenStore(ApplicationProvider.getApplicationContext<Application>()),
        )
    ) {
        val memberReads = mutableListOf<Long>()

        override suspend fun listTrips(all: Boolean): List<Trip> = trips
        override suspend fun listInvites(): List<Invite> = emptyList()

        override suspend fun members(tripId: Long): List<MemberPosition> {
            memberReads += tripId
            val at = atTheFinish ?: return emptyList()
            return listOf(
                MemberPosition(
                    userId = 5L,
                    displayName = "Poom",
                    username = null,
                    photoUrl = null,
                    role = "owner",
                    isSharing = false,
                    sharingUntil = null,
                    lat = at.lat,
                    lng = at.lng,
                    speedMps = null,
                    batteryPct = null,
                    recordedAt = "2026-08-20T14:00:00Z",
                )
            )
        }
    }

    private fun model(server: CountingServer) =
        TripsViewModel(tripApi = server, onSessionExpired = {})

    @Test
    fun `only the trips on screen are asked about`() = runTest(dispatcher) {
        val server = CountingServer((1L..7L).map { trip(it) }, pai)
        val model = model(server)
        testScheduler.advanceUntilIdle()

        // Three, because three is what the list draws — the archive is shut.
        assertEquals(listOf(1L, 2L, 3L), server.memberReads)
        assertEquals(listOf("Poom"), model.uiState.value.podiums[1L]?.map { it.label })
        assertEquals(null, model.uiState.value.podiums[4L])
    }

    @Test
    fun `opening the archive reads the rest, and nothing twice`() = runTest(dispatcher) {
        val server = CountingServer((1L..7L).map { trip(it) }, pai)
        val model = model(server)
        testScheduler.advanceUntilIdle()

        model.setArchiveOpen(true)
        testScheduler.advanceUntilIdle()

        // The four that just came on screen, and not the three already
        // answered: a rider toggling the archive back and forth pays once.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), server.memberReads)

        model.setArchiveOpen(false)
        model.setArchiveOpen(true)
        testScheduler.advanceUntilIdle()
        assertEquals(7, server.memberReads.size)
    }

    @Test
    fun `a trip with no destination is never asked about`() = runTest(dispatcher) {
        val server = CountingServer(
            listOf(trip(1L), trip(2L, destination = null), trip(3L)),
            pai,
        )
        model(server)
        testScheduler.advanceUntilIdle()

        // Without a finish there is nothing to have reached, and most trips in
        // an old archive never had one.
        assertEquals(listOf(1L, 3L), server.memberReads)
    }

    @Test
    fun `an archive of forty is not forty requests`() = runTest(dispatcher) {
        val server = CountingServer((1L..40L).map { trip(it) }, pai)
        val model = model(server)
        testScheduler.advanceUntilIdle()
        server.memberReads.clear()

        model.setArchiveOpen(true)
        testScheduler.advanceUntilIdle()

        // A ceiling on the burst rather than on the feature: the cards nearest
        // the top get podiums, the rest look exactly as they did last release.
        assertTrue("too many reads: ${server.memberReads.size}", server.memberReads.size <= 8)
    }

    @Test
    fun `a trip nobody finished is recorded as empty rather than asked again`() =
        runTest(dispatcher) {
            val server = CountingServer(listOf(trip(1L)), atTheFinish = null)
            val model = model(server)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList<Any>(), model.uiState.value.podiums[1L])

            model.setArchiveOpen(true)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(1L), server.memberReads)
        }

    @Test
    fun `pressing refresh reads them again`() = runTest(dispatcher) {
        val server = CountingServer(listOf(trip(1L)), pai)
        val model = model(server)
        testScheduler.advanceUntilIdle()

        model.refresh()
        testScheduler.advanceUntilIdle()

        // The rider asking for the current truth. A podium read minutes ago is
        // exactly what they pressed the button about.
        assertEquals(listOf(1L, 1L), server.memberReads)
    }
}
