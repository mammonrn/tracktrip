package app.ptrip.tracktrip.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager

/**
 * Which provider gets asked, and by whom.
 *
 * ## Why this exists
 *
 * "Centre on me" was reported dead on a phone that was sharing its position
 * perfectly well in another trip. Part of the answer is here: [LocationFix
 * .current] asks GPS and *only* GPS whenever the phone has one, so a phone
 * indoors — a GPS that exists and cannot fix, a network provider that could
 * answer in a second — spends the entire timeout on the wrong provider. At
 * `QUICK_TIMEOUT_MS` that is fifteen seconds of a button doing nothing.
 *
 * The two callers want opposite things and now have a function each, which is
 * the whole point of these tests: a position that goes on somebody else's map
 * is worth waiting for the accurate provider, and a rider pressing a button on
 * their own map wants an answer. Both halves are asserted, because "fix the
 * button" is exactly the change that would otherwise arrive one day as "and
 * while I was there I made the ride report faster fixes too".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationFixProviderTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val manager
        get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val shadow: ShadowLocationManager get() = shadowOf(manager)

    @Before
    fun grantAndEnableBothProviders() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    /** A phone indoors: the mast knows where it is, the sky does not. */
    private fun onlyTheNetworkCanAnswer() {
        shadow.simulateLocation(fix(LocationManager.NETWORK_PROVIDER, lat = 13.7563))
    }

    private fun fix(provider: String, lat: Double) = Location(provider).apply {
        latitude = lat
        longitude = 100.5018
        time = 1_000L
        elapsedRealtimeNanos = 1_000_000L
    }

    @Test
    fun `the ride's own reporting still holds out for GPS`() = runBlocking {
        onlyTheNetworkCanAnswer()

        // Unchanged, and asserted so it stays that way: the sharing service
        // calls this one, its fixes are drawn on other riders' maps as "where
        // they are", and a minute of DEFAULT_TIMEOUT_MS is the price of that
        // being true. Taking the network's answer here would quietly make
        // every reported position coarser.
        assertNull(LocationFix.current(app, timeoutMs = 500L))
    }

    @Test
    fun `a button takes whichever provider answers`() = runBlocking {
        onlyTheNetworkCanAnswer()

        val found = LocationFix.fastest(app, timeoutMs = 5_000L)

        assertNotNull("the network provider was never asked", found)
        assertEquals(LocationManager.NETWORK_PROVIDER, found!!.provider)
        assertEquals(13.7563, found.latitude, 0.0001)
    }

    @Test
    fun `GPS is still asked, and still answers when it can`() = runBlocking {
        shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER, lat = 18.7883))

        val found = LocationFix.fastest(app, timeoutMs = 5_000L)

        assertNotNull(found)
        assertEquals(LocationManager.GPS_PROVIDER, found!!.provider)
    }

    @Test
    fun `a provider that never answers is given up on, not waited out`() = runBlocking {
        // Nothing simulated: both providers hold the request open, which is
        // what a real one does while it keeps trying. The rider's timeout is
        // the whole contract — the button has to come back and say so rather
        // than sit there for ever.
        val startedAt = System.nanoTime()
        val found = LocationFix.fastest(app, timeoutMs = 300L)
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNull(found)
        assertTrue("waited ${tookMs}ms on a 300ms budget", tookMs < 5_000)
    }

    @Test
    fun `a phone with no providers at all is answered without waiting`() = runBlocking {
        shadow.removeProvider(LocationManager.GPS_PROVIDER)
        shadow.removeProvider(LocationManager.NETWORK_PROVIDER)

        val startedAt = System.nanoTime()
        val found = LocationFix.fastest(app, timeoutMs = 30_000L)
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNull(found)
        // There is nobody to ask, so there is nothing to wait for: this must
        // not spend the timeout discovering that.
        assertTrue("waited ${tookMs}ms with nothing to ask", tookMs < 5_000)
    }

    @Test
    fun `no permission is null from both, without asking the phone`() = runBlocking {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        onlyTheNetworkCanAnswer()

        assertNull(LocationFix.fastest(app, timeoutMs = 5_000L))
        assertNull(LocationFix.current(app, timeoutMs = 500L))
    }
}
