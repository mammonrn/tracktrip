package app.ptrip.tracktrip.ui

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.ptrip.tracktrip.map.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The button's half of "centre on me", one rule at a time.
 *
 * ## Why this exists
 *
 * "Centre on me" was reported dead a second time, from a build that already
 * carried the spinner meant to answer the first report. It was not dead: a
 * press reached the callback, the lookup ran, the answer came back. What was
 * missing is that **nothing the press did could be seen**.
 *
 * Half of that was here. `LocationFix.lastKnown` is a synchronous cache read,
 * and on any phone that has used a map recently it answers immediately — so
 * the busy flag went up and came down inside a single dispatch of the main
 * thread, with no suspension between them. Compose draws the result of a
 * frame, not each write within it, so the spinner was never rendered: not
 * briefly, not for one frame, never. A recording of every composition proved
 * it, and the same recording is what these tests assert on.
 *
 * The other half is the camera, and it is not fixable here — see
 * `PlacesCentreButtonTest` for the button state that answers it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class CentreOnMeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * A phone with the permission granted and, optionally, a fix already in
     * the platform's cache — which is the ordinary state of any phone that has
     * opened a map today, and so the case the button has to work in.
     */
    private fun phone(cachedFix: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val manager = app.getSystemService(LocationManager::class.java)!!
        shadowOf(manager).apply {
            setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            if (cachedFix) {
                setLastKnownLocation(
                    LocationManager.GPS_PROVIDER,
                    Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = 18.7883
                        longitude = 98.9853
                        time = 1_000L
                    },
                )
            }
        }
    }

    /**
     * Wait out the busy floor, which is a real delay rather than a frame.
     *
     * The floor is the point of the whole class, so a test that stubbed it
     * out would be testing something else.
     */
    private fun settle(centre: () -> CentreOnMe, ms: Long = CENTRE_BUSY_MIN_MS * 2) {
        compose.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
        assertEquals("still busy after the floor", false, centre().busy)
    }

    /** Every value of `busy` that reached a composition, in order. */
    private val drawn = mutableListOf<Boolean>()

    private fun show(
        onFix: (LatLng) -> Unit = {},
        onNoLocation: () -> Unit = {},
        content: @Composable (CentreOnMe) -> Unit = {},
    ): () -> CentreOnMe {
        lateinit var held: CentreOnMe
        compose.setContent {
            val centre = rememberCentreOnMe(onFix = onFix, onNoLocation = onNoLocation)
            held = centre
            drawn += centre.busy
            Box {}
            content(centre)
        }
        compose.waitForIdle()
        drawn.clear()
        return { held }
    }

    @Test
    fun `a press is drawn as busy even when the cache answers instantly`() {
        phone(cachedFix = true)
        var found: LatLng? = null
        val centre = show(onFix = { found = it })

        centre().press()
        settle(centre)

        // The regression this file exists for. Before the busy floor this
        // read `[false]`: the spinner the previous fix added could not be
        // rendered on a phone with a cached fix, which is every phone.
        assertTrue("the busy state was never drawn: $drawn", drawn.contains(true))
        assertEquals(LatLng(18.7883, 98.9853), found)

        // And it comes back down, rather than leaving a spinner on screen.
        assertEquals(false, centre().busy)
    }

    @Test
    fun `a second press while the first is still asking is ignored`() {
        phone(cachedFix = true)
        var fixes = 0
        val centre = show(onFix = { fixes += 1 })

        // Both presses land inside the busy floor, so the second arrives while
        // the first is still out. Without the guard it would queue a second
        // location request, with its own timeout, to answer a question that
        // had already been asked.
        centre().press()
        centre().press()
        settle(centre)

        assertEquals(1, fixes)
    }

    @Test
    fun `presses after the answer are not swallowed`() {
        phone(cachedFix = true)
        var fixes = 0
        val centre = show(onFix = { fixes += 1 })

        centre().press()
        settle(centre)
        centre().press()
        settle(centre)

        // The single-flight guard is about one press's lifetime, not about
        // the button ever working twice — which is exactly what a rider does
        // when the first press looked like it did nothing.
        assertEquals(2, fixes)
    }

    @Test
    fun `a phone that cannot say where it is says so`() {
        phone(cachedFix = false)
        var fixes = 0
        var refusals = 0
        val centre = show(onFix = { fixes += 1 }, onNoLocation = { refusals += 1 })

        centre().press()
        // Past the whole of `QUICK_TIMEOUT_MS`: a provider that never calls
        // back is the shape of a phone with location switched off, and giving
        // up is the answer rather than a failure of the test.
        settle(centre, ms = app.ptrip.tracktrip.location.LocationFix.QUICK_TIMEOUT_MS + 1_000)

        // Every provider answers null at once on a phone with location
        // switched off, and `LocationFix.fastest` returns rather than sitting
        // on its timeout. A sentence on screen, never a button that silently
        // does nothing.
        assertEquals(0, fixes)
        assertEquals(1, refusals)
        assertEquals(false, centre().busy)
    }
}
