package app.ptrip.tracktrip.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.ptrip.tracktrip.data.Trip
import app.ptrip.tracktrip.data.TripEndpoint
import app.ptrip.tracktrip.map.LatLng
import app.ptrip.tracktrip.ui.theme.TracktripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The route on the edit screen, while the name above it is being saved.
 *
 * `EditTripActionsTest` covers what the press is allowed to call. This is the
 * other end of the same bug: that the section a rider was looking at is still
 * the section they are looking at afterwards — the rows, and the row the
 * picker was open on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EditTripRouteTest {

    @get:Rule
    val compose = createComposeRule()

    private val chiangMai = LatLng(18.7883, 98.9853)
    private val pai = LatLng(19.3583, 98.4400)
    private val lampang = LatLng(18.2888, 99.4908)

    private fun trip(name: String) = Trip(
        id = 1L,
        name = name,
        ownerId = 7L,
        status = Trip.STATUS_ACTIVE,
        role = Trip.ROLE_OWNER,
        origin = TripEndpoint(chiangMai.lat, chiangMai.lng, "Home"),
        destination = TripEndpoint(pai.lat, pai.lng, "Pai"),
    )

    /** The route as a rider who has confirmed one would find it. */
    private val confirmed = RouteDraft(
        from = RoutePoint(chiangMai, "Home"),
        to = RoutePoint(pai, "Pai"),
        stops = listOf(RoutePoint(lampang, "Lampang")),
    )

    private var saves = 0
    private var backs = 0

    /**
     * The screen, with the draft held outside it exactly as the navigation
     * graph holds it — on the trip map's view model, which is the object the
     * old `leave()` reset out from under this screen.
     */
    private fun show(
        draft: RouteDraft = confirmed,
        onSave: (String) -> Unit = { saves += 1 },
    ) {
        compose.setContent {
            TracktripTheme {
                EditTripScreen(
                    trip = trip("Sunday run"),
                    saving = false,
                    error = null,
                    onSave = onSave,
                    routeDraft = draft,
                    routePlan = null,
                    routeLine = null,
                    routePreviewLoading = false,
                    routeSaving = false,
                    searchState = PlaceSearchState(),
                    personalPlaces = emptyList(),
                    myLocation = null,
                    hasLocationPermission = false,
                    currentUserId = 7L,
                    onOpenRouteSetup = {},
                    onSearchQueryChanged = {},
                    onSearchCleared = {},
                    onPickRoutePoint = { _, _ -> },
                    onRemoveRouteRow = {},
                    onMoveRoutePoint = { _, _ -> },
                    onConfirmRoute = {},
                    onBack = { backs += 1 },
                )
            }
        }
        compose.waitForIdle()
    }

    /**
     * The name field, by the one thing only it can do. Not by its text: the
     * trip's name is also the header's subtitle, so "Sunday run" matches two
     * nodes.
     */
    private fun typeName(name: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        compose.waitForIdle()
    }

    @Test
    fun `the route a rider confirmed is still on screen after saving the name`() {
        show()

        // What they came in looking at.
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Pai").assertIsDisplayed()
        compose.onNodeWithText("Lampang").assertIsDisplayed()

        typeName("Sunday morning run")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals(1, saves)

        // And still are. The report was these three replaced by "Choose a
        // starting point" and "Choose where you're going" — the two hints the
        // route section draws over an empty draft.
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Pai").assertIsDisplayed()
        compose.onNodeWithText("Lampang").assertIsDisplayed()
        compose.onNodeWithText("Choose a starting point").assertDoesNotExist()
    }

    @Test
    fun `saving the name does not send the rider off the screen`() {
        show()

        typeName("Sunday morning run")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        // `onBack` is the callback that discards the draft. A Save that
        // reached it is the bug, whatever it did on the way.
        assertEquals(0, backs)
    }

    @Test
    fun `a half-edited route survives a save too`() {
        // One end chosen and the other not: a rider part-way through planning
        // who stopped to fix the name. The draft is the same object either
        // way, so a save that reset it would take this with it.
        show(draft = RouteDraft(from = RoutePoint(chiangMai, "Home")))

        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Choose where you're going").assertIsDisplayed()

        typeName("Next Sunday")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Home").assertIsDisplayed()
        // Still the one empty end it had, not two.
        compose.onNodeWithText("Choose a starting point").assertDoesNotExist()
    }

    @Test
    fun `a save that lands says so, and stops saying so when the name changes`() {
        // Leaving the screen used to be the answer to "did that work?". It is
        // not any more, so the screen has to answer.
        var saved by mutableStateOf(false)
        compose.setContent {
            TracktripTheme {
                EditTripScreen(
                    trip = trip(if (saved) "Sunday morning run" else "Sunday run"),
                    saving = false,
                    error = null,
                    onSave = { saved = true },
                    routeDraft = confirmed,
                    routePlan = null,
                    routeLine = null,
                    routePreviewLoading = false,
                    routeSaving = false,
                    searchState = PlaceSearchState(),
                    personalPlaces = emptyList(),
                    myLocation = null,
                    hasLocationPermission = false,
                    currentUserId = 7L,
                    onOpenRouteSetup = {},
                    onSearchQueryChanged = {},
                    onSearchCleared = {},
                    onPickRoutePoint = { _, _ -> },
                    onRemoveRouteRow = {},
                    onMoveRoutePoint = { _, _ -> },
                    onConfirmRoute = {},
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Name saved").assertDoesNotExist()
        typeName("Sunday morning run")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Name saved").assertIsDisplayed()

        // A claim about the name in the box, so it goes when the box changes.
        typeName("Something else")
        compose.waitForIdle()
        compose.onNodeWithText("Name saved").assertDoesNotExist()
    }
}
