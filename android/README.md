# Tracktrip Android app

Kotlin + Jetpack Compose client for the trip-tracker backend. Lives in the
same monorepo as the Node backend; this folder is a self-contained Gradle
project.

**Current state:** signed-in riders can see their trips, create one, accept
an invitation, and open a trip to invite riders by email, QR code or a shared
link, see who's on it, and (as owner) end it. **Location sharing works**: a
rider picks how long to share for and a foreground service reports their
position every 10 seconds until it lapses, they stop it, or the trip ends.
On the map, pressing and holding places a point: the owner sets where the
trip starts and where it is going, and anyone on it can drop a stop. There is
a settings screen for the profile, language, sharing defaults, per-trip
sharing toggles, background battery use, and signing out. **The app is fully
translated into Thai** — `StringCoverageTest` fails the build if an English
string arrives without one.

Google sign-in is wired end to end — Credential Manager obtains a Google ID
token, it's exchanged at the backend's `POST /auth/google`, and the returned
tokens are stored in `EncryptedSharedPreferences`. On failure the app shows an
inline message rather than crashing.

## Screens

| Screen | Backend it talks to |
|---|---|
| Sign-in | `POST /auth/google` |
| Trip list, with pending invitations above it | `GET /trips`, `GET /invites`, `POST /invites/:id/accept` |
| Create trip | `POST /trips` |
| Trip detail — members, sharing, invite, end trip | `GET /trips/:id/positions`, `POST /trips/:id/invites`, `POST /trips/:id/end`, `GET /trips/:id/suggested-invitees`, `POST /trips/:id/share/start`, `/share/stop` |
| Settings — profile, language, sharing default, sharing toggles, sign out | `GET /me`, `GET /trips`, `POST /trips/:id/share/start`, `/share/stop` |
| Profile — photo, name, username, phone, date of birth | `GET /me`, `PATCH /me`, `POST /me/avatar` |
| Live map — everyone's position, with the member list under it and the route card over it | `GET /trips/:id/positions` + the `/ws` socket, `GET /trips/:id/waypoints`, `PATCH /trips/:id`, `POST /trips/:id/waypoints`, `DELETE /trips/:id/waypoints/:wpId`, `GET /geocode/search`, `GET /directions` |
| Invite with QR — a code to hold up | `POST /trips/:id/join-code` |
| Scan to join — the camera | `POST /trips/join` |

Settings is reached by the gear in the trip list's header, and the scanner by
the viewfinder icon beside it. Signing out lives in settings, not on the trip
list: it is the app's one destructive control, it belongs behind a screen the
rider opens deliberately, and it asks for confirmation before it runs.

`GET /me` is loaded once into a `ProfileViewModel` scoped to the activity.
Three screens need it — settings shows the name and photo, the profile screen
edits them, and the trip screen needs the rider's own user id to find their row
in the member list and show whether their location is going out. It is also
what fills the profile back in after a restart: the sign-in response only
exists in the session that signed in.

Navigation is a plain sealed `Screen` hierarchy over a small [`BackStack`](
app/src/main/java/app/ptrip/tracktrip/ui/Navigation.kt), wired to the system
back button through `BackHandler` — not Navigation Compose. Five screens and
one argument don't need a route DSL, and at the time of writing the only
published `navigation-compose` builds were 2.10.0 pre-releases.

## The look

A heads-up display: deep navy ground, amber for primary actions and headings,
cyan for secondary actions and anything live, ember for destructive ones.
Buttons are pills with a drawn glow, dividers are translucent accent rather
than grey, and headings run monospaced and widely tracked.

Every colour is defined in [`ui/theme/Theme.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/theme/Theme.kt) and nowhere else, so
retuning the palette is one file. Screens compose the shared pieces in
[`ui/theme/HudComponents.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/theme/HudComponents.kt) — buttons,
panels, dividers, top bar — rather than styling controls themselves. The icons
in [`ui/theme/HudIcons.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/theme/HudIcons.kt) are drawn on a
`Canvas`, which keeps their stroke weight on the same footing as the rest of
the line work and saves pulling in an icon dependency for six glyphs.

The one colour outside `Theme.kt` is `hud_background` in `res/values/colors.xml`:
the window background the system paints before Compose starts. Keep it in step
with `HudBlack`.

## Location sharing

Starting sharing is three things in a fixed order, which is why the sequence
lives in one place ([`location/SharingController.kt`](
app/src/main/java/app/ptrip/tracktrip/location/SharingController.kt)) rather
than in each screen that offers it:

1. Ask for `ACCESS_FINE_LOCATION` (coarse is accepted) and, on Android 13+,
   `POST_NOTIFICATIONS` — in one prompt sequence, because the notification is
   not decoration: it is how a rider knows they are being tracked and how they
   stop it.
2. `POST /trips/:id/share/start` with the chosen duration. The **server**
   decides the expiry.
3. Start [`LocationSharingService`](
   app/src/main/java/app/ptrip/tracktrip/location/LocationSharingService.kt)
   with that expiry, so the phone and the server stop at the same moment rather
   than at two clocks' idea of an hour.

Stopping runs the other way — service down first, then the server — so a
failed network call still leaves the phone silent.

The service reports every 10 seconds via `LocationManager` (not Play
Services' fused provider: at that cadence the accuracy is not worth another
dependency, and this keeps working on a phone whose Play Services are
unhealthy, which is the phone that ends up on a mountain road).

That was 45 seconds. Two things made 10 the right number now, and one of them
is new. At 80 km/h, 45 seconds is about a kilometre between reports and 10 is
about 220 metres — the difference between a friend's pin being a village
behind them and a street behind them. And the old objection ("every report is
a GPS acquisition") no longer applies while anybody is looking: since the live
speed feed landed, the map screen holds a continuous 1 Hz location
subscription for as long as it is composed, so a report on the map screen
costs one HTTP POST and **zero** extra acquisitions. The cost that is real is
screen-off — a phone in a tank bag now wakes the receiver 4.5× as often, less
than 4.5× in practice because ten-second cycles keep the GNSS engine warm and
a warm fix is far cheaper than the cold search a 45-second gap can fall into,
but a long day in a pocket will show it.

The backend's per-rider ceiling went from 10 to 30 reports a minute in the
same change, so 6 a minute keeps the 5× headroom the old cadence had rather
than brushing a limit whose whole job is to catch a runaway client.
[`location/ReportCadence.kt`](
app/src/main/java/app/ptrip/tracktrip/location/ReportCadence.kt) holds a copy
of that ceiling and `ReportCadenceTest` fails when the two part company —
which is the only thing connecting a Kotlin constant to a Node one.

The **viewer's** poll deliberately did not move: `LiveCadence.POLL_MS` is
still 20 seconds. The socket delivers every stored fix the moment it is
stored, so a rider with a connection already gets the whole benefit; the poll
is the fallback for a dropped socket, and on that path worst-case staleness
just improved from about a minute to twenty seconds for free. Halving it
would double every viewer's read traffic permanently to save at most ten
seconds on a path that is already better than it was. It stops
itself when the session lapses, when the rider stops it from the notification,
and when a report comes back saying the trip has ended.

**The service is the app's authority on whether this phone is sharing.**
[`SharingState`](app/src/main/java/app/ptrip/tracktrip/location/SharingState.kt)
publishes it and every screen reads from there. Deliberately *not* the server's
`is_sharing`, which answers a different question — "would a report from this
rider be accepted" — and is `true` on every running trip for a rider who has
never touched the controls, since sharing is the default there. A toggle built
on that would read ON while the phone sent nothing.

This is also why [`AppContainer`](
app/src/main/java/app/ptrip/tracktrip/data/AppContainer.kt) is a process
singleton: the service and the UI are separate entry points into the same
process, and two `ApiClient`s would mean two refresh mutexes — enough for a
401 in each to rotate the refresh token twice, which the backend treats as
theft and answers by revoking every token the rider has.

## The map

OpenStreetMap tiles through **osmdroid**, chosen over the Maps SDK for one
reason: no API key and no billing account to attach to a hobby project.

That comes with an obligation. The tiles are donated bandwidth run by a
charity, and their [usage policy](
https://operations.osmfoundation.org/policies/tiles/) requires a User-Agent
identifying the app. osmdroid's default is the literal string `osmdroid`,
shared with every app that never changed it and **blocked at the server** for
exactly that reason — an app sending it collects 429s and eventually a ban on
the shared identity. [`map/MapConfig.kt`](
app/src/main/java/app/ptrip/tracktrip/map/MapConfig.kt) sets it to
`app.ptrip.tracktrip/<version>` before any map is built, and points the tile
cache at the app's own cache directory rather than osmdroid's default of
external storage.

There is no dark tile source without running a tile server, so the standard
tiles go through osmdroid's own night-mode colour matrix
(`TilesOverlay.INVERT_COLORS`) and then a thin navy scrim, which lands close
enough to the HUD palette to belong to the app. Labels stay readable, which
was the thing to protect.

Pins are drawn at runtime in [`map/RiderMarker.kt`](
app/src/main/java/app/ptrip/tracktrip/map/RiderMarker.kt) from
`riderColor(userId)` — the same function the member list's dots use, so a
rider is one colour everywhere and stays that colour across refreshes and
restarts. Panning follows an explicit tap, never the poll: re-centring every
45 seconds would fight a rider who has dragged the map somewhere.

### Setting a route up

Two fields, `From` and `To`, stacked under the top bar, the way every map app
asks. [`ui/RouteSetup.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/RouteSetup.kt) holds the rules;
`RouteSetupCard` and `RouteSummarySheet` in [`ui/TripMapScreen.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/TripMapScreen.kt) draw them.

What was here before was one search box, and it asked the question backwards.
A rider found a place, and *then* a three-button dialog asked whether it was
the start, the finish or a stop — an answer they had decided before they
started typing. Every route went through that twice, and the second time it
asked the same question again, having just been told.

Tapping a field answers it in advance. The picker that opens is the same one
as before — the same search, the same debounce, the same "use my current
location" row, the same long press — and it has nothing left to ask, because
the field the rider tapped already said what the point is for.

Once both ends are set, a sheet rises off the bottom edge with the distance and
the time from `GET /directions`, and three things to do with it:

- **Confirm route** — one `PATCH /trips/:id` per end that actually moved, and
  one `POST /trips/:id/waypoints` per stop. Not "Start": this app has no
  turn-by-turn navigation, and a button saying "Start" on a screen shaped like
  this one is read by anyone who has used Google Maps as "begin guiding me".
- **Add stops** — the same picker again, appending a `planned` waypoint per
  pick, `order_index` counting on from the highest already on the trip. No
  re-ordering: stops go in the order they were added. The sheet's distance and
  time re-route as each one lands, so the figures above the confirm button are
  the ride the button is about to save.

  **While the sheet is up, pressing and holding the map is the short way in.**
  The two ends are already set at that point, so the only point left to add is
  a stop, and the gesture drops one where the finger is — straight to the
  naming dialog, which prefills "Stop 3" so confirming takes no typing. That is
  a hold and a tap, against opening a search panel nobody was going to use and
  then holding: the sheet says so, because a gesture with nothing on screen to
  suggest it is a gesture nobody finds.
- **Close** — throws the draft away. Nothing in it was ever sent.

**Nothing is written until Confirm.** That is the point of holding a draft
rather than saving each end as it is picked, which is what the old flow did: a
rider planning a route used to commit half of it to everybody else's map and
then go looking for the other half, and a rider who changed their mind had
already published the wrong start. While the card is open the map draws the
draft — its flags, its stops, its road — and closing it puts the trip back
exactly as it was.

**The routing quota is defended the same way it was.** A preview is one request
per route a rider actually settles on, keyed on the whole `RoutePlan` through
the same [`map/RouteRequests.kt`](
app/src/main/java/app/ptrip/tracktrip/map/RouteRequests.kt) rule the trip's own
route uses, with the same five-minute wait after a failure. A draft that is the
route the trip already has is not fetched at all — that one has already been
fetched — which is the common case of opening the card to look at what is set.

Adding a stop *is* a new route and does cost a request, which is the price of
the sheet quoting the right distance. The server's limit of six routes a minute
per rider is unchanged, so a rider adding stops faster than one every ten
seconds will hit it; the sheet then falls back to the leg measure captioned
"direct" until the five-minute wait is up.

**Editing after confirming is the same card.** It opens seeded from the trip,
so changing one end is one tap. Removing a stop is still a tap on its pin.

**A member who does not own the trip** sees the two fields greyed with a line
saying why: `PATCH /trips/:id` is owner-only, and offering them a field whose
save comes back 403 would read as a broken app rather than as a rule. Adding
stops, which the server does allow them, still works — from the sheet, and from
the long press, which is unchanged.

### The route line

With a road route fetched (`GET /directions` — see the backend README), the
map draws it from the trip's start, **through its planned stops in order**, to
its finish: a wide white casing with the orange fill on top, the same trick a
road atlas uses and the same one the progress bar down the edge of the map
uses.

**The stops used not to be in it.** They were drawn as pins and left out of the
routing call entirely, so the line ran start to finish past everything the ride
had been planned around — and the distance and the time were the wrong
journey's, not just the drawing. What goes into the call is now
[`map/RoutePlan.kt`](app/src/main/java/app/ptrip/tracktrip/map/RoutePlan.kt):
the two ends and the planned waypoints, sorted by `order_index`, in one request
that returns one geometry through all of them.

Only *planned* waypoints. A `live` one is a point somebody dropped because they
stopped there — the viewpoint, the place the group actually turned round — and
routing through those would redraw everyone's route the moment one rider marked
where they had a coffee.

**The first version of this drew a nearly straight line, and the drawing code
was not why.** The server asked LocationIQ for `overview=simplified`, which is
an overview-grade geometry — 45 vertices for 130 km of mountain road, a
three-kilometre chord per segment. The map drew exactly what it was given. The
fix is on the server (`overview=full`, then Douglas–Peucker down to the cap);
nothing on this side changed, which is worth remembering the next time a line
looks wrong: this code draws the points it is handed and adds nothing. A single coloured line over a
daylight OSM tile disappears the moment it crosses a motorway drawn in a
similar colour; with a casing the fill only has to contrast with its own
outline.

The route is fetched **once per route**, not once per poll —
[`map/RouteRequests.kt`](
app/src/main/java/app/ptrip/tracktrip/map/RouteRequests.kt) is that rule, and
`RouteRequestsTest` is what holds it. The rule is keyed on the whole
`RoutePlan`, which is what makes adding a stop re-ask for the road exactly once
while a poll still never does.

When there is a route, the progress bar measures along it
([`map/RouteGeometry.kt`](
app/src/main/java/app/ptrip/tracktrip/map/RouteGeometry.kt)) and its caption
reads "by road" — along the whole line, stops included, so a ride planned round
a viewpoint is measured as that ride.

Without one — no key on the server, no road between the points, or a rider more
than 20 km off the line — it falls back to a straight-line measure captioned
"direct", as before. On a trip with stops that fallback measures the *legs*
(start → stop → stop → finish) rather than the gap between the two ends: see
[`map/DirectProgress.kt`](
app/src/main/java/app/ptrip/tracktrip/map/DirectProgress.kt). On a trip with no
stops it is the same gap-closing measure it has always been, to the last
decimal place — that is what `DirectProgressTest` pins.

Note that the fallback is a *measure*, not a drawn line. Nothing is drawn on
the map when there is no road route: an invented straight line between two
towns is not a road, and drawing one would make a claim the app cannot back.

### Breadcrumb trails — removed

The map used to draw each rider's recent trail behind them, behind a toggle in
the corner. It is gone: the control was reported as doing nothing three times,
for three different reasons, and each fix bought one ride before the next one.
A feature whose failure mode is indistinguishable from a dead button, and which
kept finding new ways into that state, was costing more attention than the line
was worth.

`GET /trips/:id/positions/history` **stays on the server**, along with
`position_history` and its cleanup job — the data is still recorded and still
readable, so this is a UI decision and not a schema one. Nothing in the app
calls it now.


### One button for the camera

The corner used to hold three buttons: trails on/off, frame the whole route,
and find me. The trail one is gone with its feature; the other two became one.

They were never two jobs a rider alternates between at random — they are the
two ends of one question, *am I looking at the journey or at myself*, and the
answer is always whichever one is not on screen. So the button offers the other
one, and its icon shows where the next press goes rather than where the last
one went: the route glyph while the camera is on the rider, the pin while it is
holding the overview. Following returns at `FOLLOW_ZOOM` (17), unchanged.

`CameraRules.nextAction` in [`map/RouteProgress.kt`](
app/src/main/java/app/ptrip/tracktrip/map/RouteProgress.kt) is the rule, and
the icon, the content description and the click all read it — so they cannot
disagree about what the button is for. With fewer than two points to frame (no
ends set, no position reported) it keeps the "find me" job rather than becoming
a control that does nothing, which is what the old overview button avoided by
hiding itself. One button cannot hide.

### The progress bar down the edge

Where the bar starts is worked out from the header's **measured** height
([`map/ProgressBarLayout.kt`](
app/src/main/java/app/ptrip/tracktrip/map/ProgressBarLayout.kt)), not from a
constant. It used to be a flat 76dp inset, on the strength of the header being
"about 68dp tall" — which it is, until the trip name wraps to two lines, or the
subtitle carries "Sent 4 min ago", or a failed poll adds an error row, or the
rider has turned the system font scale up. Any one of those pushed the header
past the guess and put the bar's label plate underneath it.

Below a floor the bar is not drawn at all rather than squeezed into a smear:
the distance and its caption are on the header already.

## Language

The language setting is stored in `SharedPreferences` and applied through
`AppCompatDelegate.setApplicationLocales` — Android's own per-app language API.
On Android 13+ it hands the choice to the system, so it also appears under
Settings › Apps › Tracktrip › Language; below that, AppCompat stores it and
recreates the activity. That is why `MainActivity` is an `AppCompatActivity`
and the theme has an AppCompat parent, and why the locale is applied in
[`TracktripApplication`](
app/src/main/java/app/ptrip/tracktrip/TracktripApplication.kt) — before any
activity exists, so there is nothing on screen to recreate.

`values-th/strings.xml` covers **every** translatable string. It did not
always, and that is worth knowing about: Android falls back per *string*, not
per file, so the untranslated majority quietly showed English and the switch
looked like a stub that did nothing. It never was one — there was simply
nothing behind most of the screens. [`StringCoverageTest`](
app/src/test/java/app/ptrip/tracktrip/ui/StringCoverageTest.kt) now compares
the two files on every build, and also checks that a translation has not
dropped a `%1$s` on its way across.

`app_name` is the one deliberate exception, marked `translatable="false"`: a
launcher icon whose caption changes with the phone's language is, to the
person looking at it, a different app.

## Navigation and going back

The back stack is a plain list of [`Screen`](
app/src/main/java/app/ptrip/tracktrip/ui/Screen.kt) values, saved through
`rememberSaveable` as one short token each. That saving is the point: Android
rebuilds this activity for reasons that have nothing to do with the rider
navigating — a language change is one of them, and it is one they make
deliberately — and until it was saved, the history was rebuilt empty and
dropped them on the trip list. Every screen except the trip list has a back
control in its header, and the system back button is handled whenever there is
history to go back through.

## Live positions

The map subscribes to the backend's WebSocket and folds each fix into the list
as the server stores it, so a friend's pin moves the moment the server hears
about it rather than on this phone's next poll.

**The poll never stops.** It slows to two minutes while the socket is
connected and returns to twenty seconds the moment it is not — see
[`LiveCadence`](app/src/main/java/app/ptrip/tracktrip/data/LiveCadence.kt).
That is not a fallback bolted on; it falls out of what the socket is. The
socket carries a copy of data that is already stored and already readable over
REST, so it adds nothing to the app's state and losing it cannot lose any. A
rider who rides through a valley goes from instant to a-poll-behind and back,
and is never told, because there is nothing they can do about it and nothing
they need to.

The reconciliation poll is not zero for a reason of its own: the socket
delivers positions and nothing else — not somebody joining the trip, leaving
it, or switching sharing off — so something has to notice those.

Reconnection backs off from one second to thirty, doubling. A connection that
worked and then dropped starts again at the shortest wait; one that never
became usable keeps counting, so a phone with a stale token does not hammer a
server that is refusing it.

Reporting stays on REST. The foreground service posts on its own cadence and
the server refuses positions over the socket outright — see the backend README
for why one write path is worth keeping.

## Battery

### Each rider's battery level, and why two screens once disagreed about it

The battery percentage beside a rider's name is not a live reading. It is
whatever their phone reported alongside its **last position fix**, stored in
`member_positions.battery_pct` and served by `GET /trips/:id/positions` — the
one endpoint both the map and the members list read.

They still managed to show different numbers for the same rider, minutes
apart: 76% on the map against 37% on the members list. Neither was wrong about
the field; they were reading it at different *ages*. The map polls every twenty
seconds and folds live socket frames in on top, while `TripDetailViewModel`
called `refresh()` exactly once, from `init` — and that view model is scoped to
the activity and keyed by trip id, so "once" meant once per app launch. Open
the members list in the morning, ride all day with the phone on a charger on
the handlebars, come back to it: 37% from before breakfast, sitting next to a
map showing 76% from twenty seconds ago.

The fix is two things. The members list polls on the same twenty-second beat
(quietly — a background refresh never raises the loading flag or clears an
error), and both screens now say **how old the reading is** next to it. The
percentage itself is drawn by one shared component,
`HudBatteryReadout` — an icon that fills, and a colour that goes red at 10%.
Two screens each drawing their own `"$it%"` is the seam this drifted apart
along, and there is only one now.

### Keeping this phone alive long enough to report

The foreground service is what lets the app read location with the phone in a
pocket, and it is not enough on its own: Doze and app-standby still throttle a
backgrounded app, and a 10-second reporting loop quietly becomes a several-
minute one. The app asks for Android's standard battery-optimisation exemption
once, at the moment sharing first starts, and leaves a row in settings for
anyone who said no or wants to check. On top of that, Settings shows the
**manufacturer's own** killer where there is one — Samsung's sleeping apps,
Xiaomi's autostart, Oppo/Realme/Vivo's background permissions, OnePlus's deep
optimisation, Huawei's app launch — because Android's exemption tells those
layers nothing at all.

That advice is **written out as a path to follow, not a deep link**. Intents
that open those screens exist, but they are undocumented, differ across skin
versions, and are removed without notice: a link that worked on MIUI 12 throws
on 14, and the rider is left with a button that does nothing. A sentence naming
the setting keeps working when the phone is updated. Phones close to stock are
shown nothing extra — there is nothing to say, and a section that appeared
everywhere with generic advice would teach riders to skip it.

## Session handling

Access tokens last an hour, which a long ride outlives. Every authenticated
call goes through [`ApiClient`](
app/src/main/java/app/ptrip/tracktrip/data/ApiClient.kt), which on a `401`
refreshes once and replays the request. Screens never see that; they only see
`SessionExpiredException` when the refresh itself fails, which drops the user
back to sign-in.

Refreshes are serialised behind a mutex. The backend rotates the refresh token
on every use and treats a re-presented old one as theft — by revoking every
token the user has — so two screens refreshing at once would sign the rider
out rather than keep them in.

## Configuration — where the Client IDs live

**Everything environment-specific is in one file:
[`app/src/main/res/values/config.xml`](app/src/main/res/values/config.xml).**
If the Google Cloud project or the API host ever changes, that file is the
only thing to edit.

| Resource | What it is | Used where |
|---|---|---|
| `google_web_client_id` | **Web** OAuth client ID | Passed to `GetGoogleIdOption.setServerClientId(...)` in `MainActivity.kt`, and verified by the backend |
| `api_base_url` | Backend base URL (`https://api.ptrip.app`) | `AuthApi` |

### Google Cloud OAuth clients (project `471850622906`)

| Client | ID | Referenced in code? |
|---|---|---|
| **Web** | `471850622906-8erl86uso7f64td0evq363k3tpfekedd.apps.googleusercontent.com` | **Yes** — `config.xml` → `google_web_client_id` |
| **Android** | `471850622906-lek8ce0l0kaohchi242a17rlbg6sabta.apps.googleusercontent.com` | **No** — recorded here only |

Neither is a secret; OAuth client IDs are public identifiers that ship inside
the app. The private key that proves the app's identity is the release
keystore, which never enters this repo.

Two things that routinely trip people up:

**The Web client ID is the correct one to pass to Credential Manager**, not
the Android one. `setServerClientId()` wants the client ID of the *server*
that will verify the token. The returned Google ID token carries that value
in its `aud` claim, and the backend checks it against its own
`GOOGLE_CLIENT_ID`. So the same web client ID must appear in **both**
`config.xml` here and the backend's `.env`.

**The Android client ID is never passed to any API**, which is why it lives
in this README rather than in `config.xml` — storing it as a string resource
would ship an unused value in the APK. It exists so Google can authorise
*this app* to request tokens, matched by package name + signing certificate
SHA-1 registered in Google Cloud Console.

A consequence worth knowing: debug builds use application ID
`app.ptrip.tracktrip.debug` and the debug keystore, so they need their **own**
Android OAuth client registration (with the debug SHA-1 and the `.debug`
package name) or sign-in will fail with a "developer error" in debug while
working fine in release. Get the debug SHA-1 with:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android | grep SHA1:
```

## Toolchain

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.7.0 |
| Kotlin | 2.4.10 |
| compileSdk / targetSdk | 37 |
| **minSdk** | **26** (Android 8.0) |
| Compose BOM | 2026.08.00 |

`minSdk` is 26 because the next phase needs a foreground service for
background location tracking.

Note that AGP 9 has **built-in Kotlin support**, so there is no separate
`org.jetbrains.kotlin.android` plugin — applying it is an error. Only the
Compose compiler plugin is applied alongside AGP.

## Dependencies of note

- **Google Sign-In uses Credential Manager**
  (`androidx.credentials` + `com.google.android.libraries.identity.googleid`),
  *not* the deprecated `GoogleSignInClient` / `play-services-auth` sign-in API.
- **OkHttp** for backend calls. Responses are parsed with `org.json` from the
  platform — no Moshi/Gson/kotlinx-serialization, since the payloads are small
  and flat enough that a code-generating serializer would cost more than it
  saves.
- **`androidx.security:security-crypto`** for `EncryptedSharedPreferences`,
  so the access/refresh tokens are encrypted at rest (AES256-GCM values,
  AES256-SIV keys) under an Android Keystore master key — never plain text.
- **Coil** (`coil3` + `coil-network-okhttp`) for the one remote image the app
  has: a rider's avatar. The OkHttp fetcher is a separate artifact in 3.x, and
  without it network images fail at runtime rather than at build time.
- **ZXing** — `com.google.zxing:core` writes the invite QR code and
  `com.journeyapps:zxing-android-embedded` wraps the camera preview that reads
  one. Only `BarcodeView` is used, inside an `AndroidView`; the library's own
  `CaptureActivity` and decorations are not, so the screen is ordinary Compose
  with the app's own viewfinder drawn over it.

Picking a photo uses `ActivityResultContracts.PickVisualMedia` — the system
photo picker, so there is no storage permission to ask for and the app only
ever sees the one image chosen. The picked image is downscaled to 1024px and
re-encoded as JPEG before upload (`ui/AvatarImage.kt`): phone cameras produce
4–12 MB files and the server refuses anything over 5 MB, so without that step
perfectly ordinary photos would fail to upload. Re-encoding also drops the EXIF
block, and with it the GPS tag on the original photo — a welcome side effect on
a location-sharing app.

## Sign-in flow

```
SignInScreen  ──tap──▶  CredentialManager.getCredential()
                              │  GetGoogleIdOption(serverClientId = WEB client id)
                              ▼
                        Google ID token
                              │
                              ▼
        AuthApi ──POST {idToken}──▶  https://api.ptrip.app/auth/google
                              │
                              ▼
              { accessToken, refreshToken, user }
                              │
                              ▼
                TokenStore (EncryptedSharedPreferences)
                              │
                              ▼
                        TripListScreen
```

Relevant files:

| File | Role |
|---|---|
| `MainActivity.kt` | Credential Manager call + which screen to show |
| `ui/SignInViewModel.kt` | `SignedOut / Loading / Error / SignedIn` state |
| `ui/SignInScreen.kt` | Button, spinner, inline error text |
| `ui/TripListScreen.kt` | Placeholder post-sign-in screen |
| `data/AuthApi.kt` | OkHttp call to `POST /auth/google` |
| `data/TokenStore.kt` | Encrypted token storage |

Every failure path — no credential offered, user dismissed the sheet, network
down, non-2xx from the backend, malformed response — is caught and turned
into a short message on screen. Nothing throws to the top level.

### Credential Manager fallback chain

`auth/GoogleSignInHelper.kt` tries three strategies in order, stopping at the
first that yields a token (a user cancellation stops the chain immediately):

1. `GetGoogleIdOption` with `setFilterByAuthorizedAccounts(true)` — quiet
   path for users who've signed in before.
2. `GetGoogleIdOption` with the filter **off** — required for a first-ever
   sign-in, when nobody has authorized the app yet.
3. `GetSignInWithGoogleOption` — the explicit button flow, which always shows
   the account chooser and can succeed where the ID-token options return
   nothing.

Each step logs to logcat under the tag **`TracktripSignIn`**, including the
concrete exception class, Credential Manager's `type` string, and the stack
trace. Filter logcat on that tag when diagnosing sign-in problems:

```bash
adb logcat -s TracktripSignIn
```

### "Google closed the sign-in without returning an account"

Credential Manager maps an activity result of `RESULT_CANCELED` to
`GetCredentialCancellationException` — and Play Services returns
`RESULT_CANCELED` **both** when the user dismisses the sheet **and** when it
aborts internally. The exception carries nothing that distinguishes them, so
a chooser that appears, accepts a tap, and then reports "cancelled" is a
known signature of the app not being a registered OAuth client rather than of
anything the user did.

The helper therefore treats a cancellation as an *abort* when earlier
strategies already returned `NoCredentialException` — the user must have
interacted for the chooser to get that far, so a genuine dismissal is
unlikely. That surfaces a message instead of silently returning to the
sign-in button. A cancellation with no prior `NoCredentialException` is still
treated as a real user cancellation and stays silent.

Fix is the same as the section above: register this build's package name and
signing SHA-1, remembering that debug is a different pair from release.

### "Google didn't offer an account for this app"

This is `NoCredentialException` after all three strategies. The name is
misleading: it very rarely means the device has no Google account. The usual
cause is that **this build isn't a recognised OAuth client** — its package
name plus signing certificate SHA-1 aren't registered as an Android OAuth
client in the Google Cloud project that owns the server client ID.

The trap is the debug build. `applicationIdSuffix = ".debug"` means debug
installs are `app.ptrip.tracktrip.debug`, signed with the debug keystore — a
*different* package name and a *different* SHA-1 from release. Registering
only the release pair produces exactly this error in debug while release
works. Register both:

| Build | Package name | Certificate |
|---|---|---|
| debug | `app.ptrip.tracktrip.debug` | `~/.android/debug.keystore` (alias `androiddebugkey`, password `android`) |
| release | `app.ptrip.tracktrip` | your release keystore |

```bash
# debug SHA-1
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android | grep SHA1:

# release SHA-1
./scripts/keystore-sha1.sh
```

Also confirm the web client ID in `config.xml` and the backend's
`GOOGLE_CLIENT_ID` are the *same* client, from the *same* Cloud project as
the Android clients above. A client ID from a different project fails the
same way.

### Devices without genuine Google Play Services (microG, Huawei) — unsupported

On devices running microG or Huawei Mobile Services instead of real Google
Play Services, the account chooser may appear but every account is rejected
with **"Account abnormality"**. That's the reimplementation failing Google's
account checks; it happens before our code sees any result and there is
nothing to fix on our side.

**Genuine Google Play Services is a requirement.** Devices without it aren't
supported, and this isn't tracked as a bug.

Tokens are stored but **not yet used**: there's no authenticated request or
refresh-on-401 logic, since no screen calls a protected endpoint yet.
Navigation is deliberately simple state switching in `MainActivity` rather
than `navigation-compose`; worth revisiting once there's more than one real
screen.

## Building

```bash
cd android
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Unit tests (plain JVM, no device needed):

```bash
./gradlew testDebugUnitTest
```

These are contract tests over the backend's JSON, built from payloads copied
verbatim off a running server — `app/src/test/.../TripApiParsingTest.kt`. A
field renamed on the backend fails a test here instead of silently emptying a
screen. `org.json` is stubbed out in `android.jar` (every method throws), so
the real implementation is on the unit-test classpath.

Debug builds use the `.debug` application ID suffix, so a debug and a release
build can sit side by side on one device.

CI builds a debug APK on every push touching `android/**` — grab it from the
run's **Artifacts** section on the Actions tab (`tracktrip-debug-apk`).

---

## Release keystore

The release keystore is a **private key you must create yourself, on your own
machine**. It is deliberately not created here, not committed, and not stored
on GitHub.

Why it matters:

- **Lose it** → you can never ship an update to an already-published app. The
  Play Store rejects an APK for an existing package signed with a different
  key.
- **Leak it** → someone else can publish an app that Android trusts as yours.

`*.jks`, `*.keystore`, `*.p12`, and `keystore.properties` are all gitignored
(unanchored, so they're caught in subdirectories too), but the safest place
for the file is **outside this repository entirely**.

### 1. Create it (run on your own machine)

```bash
./scripts/create-release-keystore.sh
```

Writes `~/keystores/tracktrip-release.jks` — RSA 4096, alias `tracktrip`,
valid 9855 days (~27 years, comfortably past Google Play's
"valid through 2033-10-22" requirement). Pass a different path as the first
argument if you like.

Or the equivalent raw command:

```bash
keytool -genkeypair -v \
  -keystore ~/keystores/tracktrip-release.jks \
  -alias tracktrip \
  -keyalg RSA -keysize 4096 \
  -validity 9855 \
  -storetype JKS
```

> `keytool` will warn that JKS is a proprietary format and suggest PKCS12.
> That warning is harmless — Android signs fine with JKS. If you'd rather use
> the modern format, add `-storetype PKCS12` instead; both work with Gradle
> signing configs.

**Back the file up** somewhere private and durable (password manager vault,
encrypted drive), along with its password. Neither can be recovered.

### 2. Get the SHA-1 fingerprint (for the Google OAuth Android client)

One command:

```bash
keytool -list -v -keystore ~/keystores/tracktrip-release.jks -alias tracktrip | grep SHA1:
```

Or via the helper, which prints SHA-1 and SHA-256:

```bash
./scripts/keystore-sha1.sh
```

Use that SHA-1 plus the package name `app.ptrip.tracktrip` to create the
**Android** OAuth client in Google Cloud Console. Add the resulting client ID
to the backend's `GOOGLE_CLIENT_ID` (it accepts a comma-separated list, so the
web and Android client IDs can coexist).

For debug builds, Android's auto-generated debug keystore has its own
fingerprint — register that one too if you want Sign-In working in debug:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android | grep SHA1:
```

---

## CI debug signing — pinning the debug keystore

By default AGP signs debug builds with `~/.android/debug.keystore`, creating
it if missing. A CI runner is a fresh machine every time, so **every CI build
got a different debug certificate**. Measured on two runs of `main`:

| Build | SHA-1 |
|---|---|
| CI run #14 | `5E:19:47:9A:7B:98:3A:50:37:40:A7:21:24:B7:05:F4:37:C2:23:2B` |
| CI run #8 | `4A:CC:F5:8A:18:01:AD:09:5B:36:4E:1D:13:A4:C1:7C:C2:95:45:17` |
| Poom's machine | `03:21:F8:4C:C1:3C:2E:A0:29:C3:9C:97:61:C9:67:46:3F:CE:11:E4` |

Google Sign-In only issues tokens to a package name + certificate fingerprint
registered in Cloud Console, so a CI APK could never sign in — and registering
one CI fingerprint wouldn't help, since the next build changes it again.

The build now accepts a **pinned** debug keystore. Set
`DEBUG_KEYSTORE_PATH` (plus `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS`,
`DEBUG_KEY_PASSWORD` if they aren't the Android defaults) and debug builds are
signed with it. Unset, behaviour is exactly as before.

CI reads it from the `ANDROID_DEBUG_KEYSTORE_BASE64` secret. **Until that
secret exists, CI keeps building but the APK still can't sign in** — the run
is annotated with a warning and the job summary says so.

### Creating the secret (Windows, PowerShell)

Do this once, on the machine whose debug keystore is registered in Cloud
Console:

```powershell
# 1. Confirm this is the right keystore — the SHA-1 must match what's
#    registered in Google Cloud Console for app.ptrip.tracktrip.debug
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" `
  -alias androiddebugkey -storepass android | Select-String "SHA1:"

# 2. Base64-encode it to a single line
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) `
  | Set-Content -NoNewline "$env:USERPROFILE\debug.keystore.b64"

# 3. Copy to clipboard
Get-Content "$env:USERPROFILE\debug.keystore.b64" | Set-Clipboard
```

macOS/Linux equivalent:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android | grep SHA1:
base64 -w0 ~/.android/debug.keystore | pbcopy   # Linux: | xclip -selection clipboard
```

Then in **Settings → Secrets and variables → Actions → New repository
secret**:

| Secret | Value |
|---|---|
| `ANDROID_DEBUG_KEYSTORE_BASE64` | the base64 string |
| `ANDROID_DEBUG_KEYSTORE_PASSWORD` | *(optional — defaults to `android`)* |
| `ANDROID_DEBUG_KEY_ALIAS` | *(optional — defaults to `androiddebugkey`)* |
| `ANDROID_DEBUG_KEY_PASSWORD` | *(optional — defaults to `android`)* |

Delete the `.b64` file afterwards. Then re-run the workflow: every run prints
the signing SHA-1 to the job summary, so you can confirm it matches.

A debug keystore isn't really a secret — its password is the publicly known
`android` and it has no security value — but keeping it out of the repo avoids
confusing it with the release keystore, which genuinely must never be shared.

## Adding release signing later (not done in this change)

Release signing is intentionally **not** configured yet, because the keystore
must not touch GitHub until we deliberately put it there. When you're ready:

1. Base64-encode the keystore so it survives as a text secret:
   ```bash
   base64 -w0 ~/keystores/tracktrip-release.jks > keystore.b64
   ```
   (on macOS: `base64 -i ~/keystores/tracktrip-release.jks -o keystore.b64`)

2. Add four repository secrets under
   **Settings → Secrets and variables → Actions**:

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | contents of `keystore.b64` |
   | `KEYSTORE_PASSWORD` | the store password |
   | `KEY_ALIAS` | `tracktrip` |
   | `KEY_PASSWORD` | the key password |

   Then delete `keystore.b64` — don't leave it lying around.

3. In `app/build.gradle.kts`, add a `signingConfigs.release` block that reads
   those values from environment variables, and point
   `buildTypes.release.signingConfig` at it. Guard it so local builds without
   the env vars still work.

4. In the workflow, decode the secret into a file before building:
   ```yaml
   - name: Decode keystore
     run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > android/app/release.jks
   ```
   then run `./gradlew assembleRelease` with the passwords passed as `env:`.

Two cautions: GitHub masks secrets in logs but anyone who can push a workflow
change to the repo can exfiltrate them, so limit who has write access. And
keep the local `.jks` as the source of truth — the base64 secret is a copy,
not a backup.
