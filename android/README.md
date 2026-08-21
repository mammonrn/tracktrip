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
sharing toggles, background battery use, and signing out. **Every action that
writes to the server says whether it worked** — one snackbar, green or red,
with a Retry where repeating the call is the whole fix; see [Saying whether it
worked](#saying-whether-it-worked). **The app is fully translated into Thai** —
`StringCoverageTest` fails the build if an English string arrives without one.

Google sign-in is wired end to end — Credential Manager obtains a Google ID
token, it's exchanged at the backend's `POST /auth/google`, and the returned
tokens are stored in `EncryptedSharedPreferences`. On failure the app shows an
inline message rather than crashing.

## Screens

| Screen | Backend it talks to |
|---|---|
| Sign-in — the app's mark over its name, and the Google button | `POST /auth/google` |
| Trip list — the newest three, an archive for the rest, a podium on each card | `GET /trips`, `GET /invites`, `POST /invites/:id/accept`, `GET /trips/:id/positions` |
| Create trip | `POST /trips` |
| Trip detail — members, sharing, invite, end or leave the trip | `GET /trips/:id/positions`, `POST /trips/:id/invites`, `POST /trips/:id/end`, `DELETE /trips/:id/members/me`, `GET /trips/:id/suggested-invitees`, `POST /trips/:id/share/start`, `/share/stop` |
| Settings — profile, language, sharing default, the sharing switch, saved places, sign out | `GET /me`, `GET /trips`, `POST /trips/:id/share/start`, `/share/stop`, `GET /places`, `GET /me/places`, `DELETE /places/:id`, `DELETE /me/places/:id` |
| Profile — photo, name, username, phone, date of birth | `GET /me`, `PATCH /me`, `POST /me/avatar` |
| Live map — everyone's position, with the member list under it and the route card over it | `GET /trips/:id/positions` + the `/ws` socket, `GET /trips/:id/waypoints`, `PATCH /trips/:id`, `POST /trips/:id/waypoints`, `DELETE /trips/:id/waypoints/:wpId`, `GET /geocode/search`, `GET /directions` |
| Invite with QR — a code to hold up | `POST /trips/:id/join-code` |
| Scan to join — the camera | `POST /trips/join` |

Settings is reached by the gear in the trip list's header — and by the gear on
Map & places, which is where the list of a rider's own places lives — and the
scanner by the viewfinder icon beside it. Signing out lives in settings, not on the trip
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

### The mark on the sign-in screen

Sign-in was the word "PTrips" in the middle of an empty page — the one screen
every rider sees before they have any reason to trust what they are signing
into. The mark now sits above the name at 96dp, centred, which reassembles the
lockup from `design/app_icon.png` where the pin sits over the wordmark.

It draws `@mipmap/ic_launcher_foreground` rather than a new asset: that file is
already the mark alone, cropped and centred on its own ink at five densities,
and the whole point is that it is the icon the rider just tapped. Because it is
an adaptive icon's *foreground layer*, `AppLogo` applies the two rules a
launcher applies to it — a circle of `ic_launcher_background` behind it (the
clouds in the pin are transparent cut-outs, not white, so on a bare page they
would read as holes), and the image laid out at 1.5x the circle and clipped
back to it, which is the 108:72 crop a round launcher icon performs. Drawn at
the layer's own 108dp instead, the mark would appear at two thirds the size the
launcher gives it, ringed by its own safe-zone padding.

No content description: the name is written underneath it in words, and a
screen reader saying "PTrips" twice on a screen with one button is worse than a
mark it skips. `SignInLogoTest` finds it by tag, which buys the test the same
hook and adds no announcement.

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

### One switch, and why it is not two

Settings used to ask "is this phone sharing?" once per running trip: a list
built from `listTrips().filter { it.isActive }` with a `Switch` on each row.
Every one of those switches is a statement about a trip, so a rider whose trip
had **ended** came to turn sharing on and found nothing to press — the trip was
filtered out before the list was drawn, and what stood where the control should
have been was "Sharing needs a running trip". A privacy setting had been made
out of somebody's weekend plans.

Sharing binds a trip id in five places and four of them are right: the server's
session is per trip, the service reports to the trip it was started for,
`SharingState.ActiveSharing` says which, and `SharingController.start/stop`
carry one out. The fifth — whether the phone may share at all — was the only
statement about the phone, and the only one made out of trips.

The first fix for that put the phone's switch above the trip rows and kept
both. That was one question too many. **A rider is on one ride at a time**, and
the phone is on one by construction: `SharingState` holds a single
`ActiveSharing` and the service reports to the trip it was started for. Rows
asking *which* trip were asking somebody to choose between things they cannot
do at once, and two switches that can disagree about the same fact is the
confusion this was meant to end rather than extend.

So the section is **one switch and a sentence**, backed by
`AppSettings.shareLocationFromThisPhone` and always pressable: no trips, every
trip finished, network down. [`location/DeviceSharing.kt`](
app/src/main/java/app/ptrip/tracktrip/location/DeviceSharing.kt) holds the
decisions, with no Android in it, for the same reason `BatteryExemption` does:

- **Off** stops whatever is live and never reads the trip list — the id comes
  from the service, so a session that outlived its trip stops all the same.
  That case is exactly what a trip-shaped control could not reach.
- **On** starts sharing on the running trip. With none there is nothing to
  start and the choice is simply kept, which is the whole of the original
  report.

On while nothing is being sent is permission rather than transmission, and an
ordinary state — the line under the switch says which of the three it is
(`DeviceSharing.Status`) and **names the trip** when there is one, because the
screen no longer has a row that does. The preference defaults to **on**,
matching the backend, where a rider who has never touched the controls already
counts as sharing; defaulting it off would have silently stopped everyone who
upgraded mid-tour. Starting from the trip screen turns it on, because picking a
duration there is consenting. Android's location dialog is raised only when the
switch is about to start sending, and never when turning it off — a kill switch
that waits on a dialog is not a kill switch.

#### One active trip per rider — now a backend rule

It began as an assumption this screen relied on and nothing enforced. The
backend enforces it now: `POST /trips`, accepting an invite and redeeming a
join code all answer `409` while the caller is already on an active trip, with
a trigger under them (migration `0013`). The root README has the rule; what
matters here is that the trip list can no longer contain two running trips for
one rider, so the switch never has to choose.

`DeviceSharing.onSwitched` still takes the **first** running trip rather than
asserting there is only one, and that is not belt-and-braces for its own sake.
The rule was added after the fact and deliberately does not evict anybody who
was already on two — their trips are real rides with positions in them — so
those riders exist, and the switch has to do something sensible for them.
`GET /trips` orders `created_at DESC, id DESC`, so the first is the most
recently started: the one somebody with two open trips is actually riding.
Deliberately not a picker; the picker is what this change removed.

### Leaving a trip

The member's counterpart to End, in the place End sits, and not the same
control renamed: ending closes the ride for the whole group and is the
owner's; leaving takes one rider off it and the owner may not, because a trip
with no owner is one nobody can end, invite to or rename.

It exists because of the rule above. With only `/end` — owner-only — a rider
whose host went home without pressing it could not start a trip of their own,
could not accept another invitation, and had nothing they could do about it.

`DELETE /trips/:id/members/me`, offered to a non-owner on a **running** trip
only: nothing holds a rider to a finished one, and leaving would take them off
a completed ride's roster and off its podium. Behind `HudConfirmDialog` rather
than the inline confirm End uses, for the same reason signing out has one —
this is the rider's own irreversible act, and getting back on takes the owner
sending a fresh invitation, which is what the dialog says.

The phone stops sharing the moment the server confirms, through the same
`onTripEnded` the end of a trip goes through: the membership is gone, so the
next report would be answered 403 and the notification in the rider's pocket
would be claiming something untrue.

Nothing on this side had to change for the server making that a **soft
delete** — `trip_members` keeps the row with `left_at` stamped, so the two
riders go on counting as having ridden together — and one thing improved
without being touched. A rider who left is no longer on the trip's roster but
*is* still in "Ridden with before", so `InviteShortcuts` offers them again:
the window drops whoever is in `state.members`, and they are not.

### A finished trip's member rows say when, not how long ago

Every member row carries the age of that rider's last position report, and
while a ride is on that is the most useful number on the screen: it is the
difference between a pin that has not moved because the rider has not moved and
one that has not moved because their phone lost signal ten minutes ago.

On a trip that has **ended** the same number is noise dressed as information.
Nobody has reported since the trip closed, so the figure only counts up —
"Owner · 1206 min ago" on a ride that finished yesterday, a number that says
nothing except that time passes, and that grows a digit every few days until it
is the widest thing on the row.

So on a finished trip that slot now carries the days the trip actually ran, and
the row is restacked around them:

```
Owner                          <- the role, as its own small label
krongkrangrn                   <- the name
Created 20/08/2026 – Ended 20/08/2026
```

against a running trip, which is unchanged:

```
krongkrangrn
Owner · 4 min ago
```

Two full dates and a dash will not share a line with a role, and moving the
role *above* the name is what frees the whole width for them. It reads better
too: a label over a name is how a caption is normally worn, and on a finished
trip the row is a record of who was there rather than a live readout.

The rules live in
[`ui/TripDates.kt`](app/src/main/java/app/ptrip/tracktrip/ui/TripDates.kt),
which is a pure function of the two stamps, the clock and the locale, so they
are testable without a screen:

- the member row uses `Style.NUMERIC` — `20/08/2026`, always the full date. The
  fixed width is the point rather than a side effect: two of these share one
  line, and a format whose length depends on the month name ("1 May" against
  "21 September") would fit in English and overflow in Thai. It is formatted
  against `Locale.ROOT`, because the pattern is digits and slashes with nothing
  in it for a locale to translate — and passing one in would invite a calendar
  that writes 2026 as 2569;
- `Style.DAY_MONTH` — "21 Aug" — is still what the saved-place dialog uses,
  where there is room, and it is where the conditional year lives: added when
  it is load-bearing (the two dates in different years, or either outside the
  year the rider is reading in), left off when it is not. That is what keeps an
  archive honest without every trip from this month carrying a year nobody
  needed;
- a stamp this build cannot parse says nothing rather than guessing, the same
  rule `FixAge` follows, and the row keeps the age it always had;
- `created_at` and `ended_at` were on the wire from the first migration and
  simply went unread until the row needed them.

### What the third line costs, and why it is not free

About **18dp** — a card of 74dp becomes 92dp — and `EndedTripDatesTest`
measures it rather than asserting it away.

The two lines that are not the name come down from `labelSmall`'s 11sp to 10sp.
The obvious next move — a tighter `lineHeight` with `LineHeightStyle.Trim.Both`
and `includeFontPadding = false` — was tried and measured: it bought 3dp of the
18, and it put the app's other language at risk to do it. **Thai stacks up to
two marks above the base character**, and both strings this caption draws have
them — `เจ้าของทริป` carries a tone mark and an upper vowel,
`ยังไม่ได้แชร์ตำแหน่ง` carries four. The half-leading above the first line is
exactly where those marks live, and a clipped tone mark is a different word.
That is a worse bug than a taller card, and one that would only ever appear on
a Thai phone — which is most of them. So the leading stays at roughly 1.4x the
size, which is what Thai needs.

An earlier version of that test measured the rider's *name* — a `Text` of fixed
height — and so reported "no growth" through a change that added a line. It
measures the card by tag now.

### Being refused for being mid-ride

The client shows the server's own `error` string for any refusal, which keeps
the app's rules in step with the server's — and leaves them in English on a
phone that may be set to Thai. That is the right trade for a message a rider
meets once. It is the wrong one for this refusal, which every rider meets the
first time they forget to end a ride, and which stands between them and the
thing they opened the app to do.

So the backend sends `code: "active_trip_exists"` and the trip that is in the
way, `ApiClient` turns that one body into `ActiveTripException`, and
[`ui/ApiErrorText.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/ApiErrorText.kt) words it in the
rider's language with the trip **named** — "you already have an active trip"
sends somebody to a list of forty to work out which, and the answer is usually
a ride they forgot to end months ago. The server's sentence stays as the
fallback for a build talking to a server that predates the code, and every
other failure still prints exactly what the server said.

Three screens can meet it: **Create trip**, **Scan to join**, and the trip
list, when an invitation is accepted. A refused invite stays pending on the
server, so nothing is taken from the rider by the refusal.

## The trip list

The first screen after signing in, and the one the app is judged on: its job is
"get me into the ride I am about to do".

**The newest three, and an archive for the rest.** It used to draw every trip a
rider had ever been on, so somebody who rides most weekends had forty of them
and scrolled past thirty-seven every time they opened the app.
[`ui/TripListRules.kt`](app/src/main/java/app/ptrip/tracktrip/ui/TripListRules.kt)
splits the list; the archive is one chip that says how many are in it, and it
sits above the trips it opens so the control a rider just pressed is still
under their thumb. Nothing is hidden and nothing is fetched twice: `GET /trips`
already returns the whole list, and the chip only decides how much is drawn.

"Newest three" is the *first* three, because `GET /trips` orders by
`created_at DESC, id DESC` for every caller — which is just as well, since the
Android `Trip` carries no `created_at`. That pairing is written down in
`TripListRules` for the same reason `ReportCadence` writes down the backend's
rate limit: if the server's ordering changed, this would quietly start calling
the wrong three trips recent rather than failing.

**A podium on each card.** The first three riders to reach the trip's
destination, as three faces at the end of the row the card already had — 26dp,
shorter than the two lines of text beside them, so the card is exactly the
height it was. That constraint is the point: a podium that grew every card
would spend precisely what the archive just bought. A trip nobody has finished
shows nothing at all rather than an empty podium; most trips in an old archive
never had a destination set, and a row of grey placeholders on forty cards
would be the app filling space with what it does not know.

[`map/ArrivalBoard.kt`](app/src/main/java/app/ptrip/tracktrip/map/ArrivalBoard.kt)
is honest about being an estimate, in the same terms `RideOrder` is. Nothing
tells this app when anybody arrived anywhere; what it can read is one
last-known fix per rider and the time it was recorded. So arriving is
geometric — within 300 m of the destination, which covers a car park and
excludes the bypass — and the order is the order of those last reports, oldest
first. Read plainly that ranks riders by how long they have been at the finish
without reporting since, which on the trips this is actually looked at is the
same list as who got there first: a rider who arrives stops sharing, or their
session lapses, and their last fix freezes near the moment they arrived. It is
weaker on a trip still under way with everybody parked and still reporting, and
it is never *unstable* — the user id breaks every tie the clock cannot, so the
podium does not reshuffle on each poll.

The cost is one `GET /trips/:id/positions` per trip, because `GET /trips` says
nothing about who is where and should not: a trip list has no business paying
for eight riders' coordinates on every trip a rider has ever been on. So the
number is kept small — only the trips being drawn, only those with a
destination, only those not already answered, and never more than eight in one
pass, so a rider with forty archived trips opening the archive is a handful of
small reads rather than forty. Failures are swallowed whole: a podium that did
not load is a card exactly as it looked before the feature existed.

## Inviting a rider

Three ways onto a trip, all owner-only because the backend is: a **QR code**
for somebody standing next to you, a **share link** for a chat window, and a
**Google address** typed from memory.

The third is the fallback, and it used to take up more of the panel than the
other two together — a field, an Invite button and two lines about what an
invite actually does, sitting open whether or not anybody was going to type. It
is behind an **"Add by email"** button.

### "Ridden with before": five, and a number

The shortcut chips draw the riders you have ridden with before, in the server's
own order (`trips_together DESC, last_ridden_together DESC`). They used to draw
*all* of them, in a box that scrolled — which for a group that has ridden
together for years is a wall of names above the three buttons that actually add
people. [`ui/InviteShortcuts.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/InviteShortcuts.kt) cuts it to **five**
and puts the rest behind a **"+12 more"** chip that opens them in place; the
number says exactly how much is behind it. Nothing is hidden and there is no
second screen to dismiss.

That pairing with the backend's `ORDER BY` is written down in `InviteShortcuts`
for the same reason `ReportCadence` writes down the rate limit: `SuggestedInvitee`
carries no `last_ridden_together`, so nothing else in the app would say what
"the first five" means.

### A chip is the invite, and the list refills behind it

Tapping a name used to fill the email field in and open the dialog on it. A
name from the rider's own past trips needs no form and no second confirmation,
so a tap **sends it**. The chip then sinks past the window — sunk, not dropped,
because inviting the wrong Nut is easy and a chip that vanishes leaves no way
to put it right — and the sixth name rises into its place. Working down a group
is the same tap repeated until the names run out, rather than four screens each
time. Anyone who has joined the trip since the list was read is dropped
outright: they are on it, and there is nothing left to offer about them.

### The dialog takes several at once

It was one address and one Invite, so adding four people was four rounds of
open, type, send, watch it close. Both halves are plural now: the chips are the
same five-and-a-number, **multi-select** (filled when picked), and the field
takes a list — split on commas, semicolons and any whitespace, because that is
what an address list arrives as when it is pasted out of a chat window. One
press sends everything, `InviteRules.addresses` being what turns the two
controls into one request list — chips first, deduplicated case-insensitively,
so a chip and the same address typed is one invite rather than one invite and
one "already invited" the rider caused by being thorough.

`POST /trips/:id/invites` takes one address and there is no bulk endpoint, so
they go one after another. A refusal does not stop the rest — one rider already
on the trip must not cost the other four their invite — and what came back is
kept apart from what did not: `inviteSent` lists the accepted ones and the error
line names each refusal with the address it belongs to.

### What had to stay where

The **reason a send failed** is drawn inside the dialog, because a dialog covers
the screen's own error line and a refused invite would otherwise fail in
silence. The **confirmation** stays on the panel, because the dialog closes on
the server's answer and the answer has to outlive it — and it closes on
`inviteSent` changing rather than on a flag flipped inside the press, so a press
where nothing landed leaves the rider in front of what they chose. One name is
confirmed as the name; several as a count, because five addresses on one line is
not a confirmation anybody reads.

A text field inside a dialog window never lets a Compose test reach idle under
Robolectric, so the dialog is split: `InviteByEmailDialog` is placement and
nothing else, and everything with behaviour in it — `InviteByEmailForm`,
`InviteRules.addresses` and `InviteRules.canSend` — lives where a test can
reach it. The selection is held one level up, in `InvitePanel`, because the
Invite button sits in the dialog's confirm slot rather than inside the form.

### The share link, and one message that said it twice

One press of **Share link** produced one message that contained the invitation
twice — the greeting, then the greeting again with the trip name in it. Not two
sends, and not the builder running twice.

`ACTION_SEND` carries two text extras, and only email keeps them apart.
`EXTRA_SUBJECT` is a header to Gmail; to SMS/MMS, LINE, Messenger and most other
chat targets it is simply the first line of the message they compose. The intent
set both: a subject reading *"Join my trip on PTrips"*, and a body opening
*"Join my trip "X" on PTrips."* — self-contained, because it has to work on a
target that drops the subject. Folded together by the receiving app, that is one
message saying it twice.

[`ui/InviteMessage.kt`](app/src/main/java/app/ptrip/tracktrip/ui/InviteMessage.kt)
is now the only place the message is assembled, and it sets **one** extra. The
body already stood alone, which left the subject nothing to add. Email loses
nothing by it: inviting somebody by address is a separate action that goes
through the server, where the subject is the server's to write.

The text used to be built inline where the sheet was opened, which is why
nothing caught this — there was no function to call and nothing to read back.
`InviteMessageTest` reads the finished intent the way a chat app assembles it,
and three of its cases fail if a second extra comes back.

### A tapped link opens the app

`https://ptrip.app/join/CODE` is claimed as a **verified App Link**, so tapping
one in a chat opens the trip rather than a browser. Three things have to agree,
and none of them can see the other two:

| | |
|---|---|
| the manifest | `<data android:scheme="https" android:host="ptrip.app" android:pathPattern="/join/.*" />` with `autoVerify="true"` |
| the app | `joinCodeFrom(intent.dataString)` in `MainActivity`, which also reads the `tracktrip://join?code=` form the QR code carries |
| the domain | `https://ptrip.app/.well-known/assetlinks.json` — see [`deploy/assetlinks.json`](../deploy/assetlinks.json) |

**`pathPattern`, not `pathPrefix`.** A prefix of `/join` also claims `/joining`,
`/joinus` and the bare `/join`, which carries no code to act on — on a domain
this product owns and will want to put pages on. The syntax is Android's
simplified glob rather than a regex: `.` is any character and `*` is zero or
more of what precedes it, so `/join/.*` reads as `/join/` followed by anything.
`AppLinkTest` matches it with the same `PatternMatcher` Android routes with, so
the claim is checked against the links `joinWebLinkFor` actually writes.

**Both builds claim it.** The filter is in the shared manifest, so the debug
build claims the same links under `app.ptrip.tracktrip.debug`. Verification is
per package name *and* per signing certificate, so `assetlinks.json` carries an
entry for each — a file listing only the release id leaves the debug build
falling back to a chooser, which is the build this gets tested on. Fingerprints
come from [`scripts/keystore-sha1.sh`](../scripts/keystore-sha1.sh) and go in as
printed: **uppercase hex, colon-separated**, which is what the Digital Asset
Links statement-list spec asks for. `AppLinkTest` keeps the package names in
step with `build.gradle.kts` and rejects a fingerprint of the wrong shape.

The domain side is [`deploy/nginx-ptrip.app.conf`](../deploy/nginx-ptrip.app.conf) —
its own nginx site on the same box as the API, serving the statement list and
[a page for `/join/CODE`](../deploy/www/join.html) and nothing else. The page is
for a phone **without** the app; a phone with it never sees it. Until that site
is up, Android offers a chooser instead of opening the app directly. That still
works — it is the difference between one tap and two, not between working and
not.

**A link is acted on once.** The launch intent stays attached to the activity for
its whole life, so reading it on every `onCreate` would redeem the link again on
every rotation: the server answers "already a member", and the rider is thrown
out of whatever they were looking at and back onto the trip. `onCreate` reads it
only when `savedInstanceState` is null; a link arriving while the app is already
open comes through `onNewIntent`, which is what `launchMode="singleTask"` is for.

A code from a link is redeemed as soon as there is a signed-in rider to redeem
it for — held in `pendingJoinCode` until then, so a link tapped by somebody who
has never opened the app survives the sign-in. Failures are not silent: an
expired or unknown code comes back through the same snackbar as any other
refusal, with the server's wording, and joining a trip while already out on one
is refused by name.

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

One list: `From`, every stop, `To`, in riding order, in a sheet off the bottom
edge. [`ui/RouteSetup.kt`](app/src/main/java/app/ptrip/tracktrip/ui/RouteSetup.kt)
holds the rules; `RouteListSheet` in [`ui/TripMapScreen.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/TripMapScreen.kt) draws it.

What was here before was two places. The two ends lived on a card at the top
and the stops in a summary sheet at the bottom, so the ride was described twice
and never in one place. And the only two things a rider could do to a stop were
append one and delete one: the order stops went on in was the order they were
thought of in, with no way to say "that one first". A route planned in the
wrong order had to be deleted stop by stop and typed again.

Every point on the ride is now a row. Each row has a grip on the left and a
cross on the right, tapping an end opens the picker for it, and the row after
the last one adds another stop. The distance and the time from `GET /directions`
are the line under the title — a figure that describes the whole list belongs
over the whole list.

**Dragging a row re-orders the ride.** A stop's `order_index` is its position in
`RouteDraft.stops` and nothing else — the commit counts along the list and
`draftWaypoints` numbers the pins from it — so a drag *is* the index update,
applied the moment the row crosses its neighbour. The pins renumber, the road
re-measures, and the confirm that follows writes the order on screen. There is
no second field that could fall out of step with it.

The drag runs over the whole list rather than just the stops, so a stop dragged
above the start becomes the start and the old start becomes the first stop —
`RouteSetupRules.ordered`/`fromOrdered` are the two halves of that, and they are
inverses. Two things are refused rather than half-applied: a re-order on a draft
with an end still empty (there is no full list to order, so no grip is offered
either), and a drag that would touch either end for a member who does not own
the trip.

The list caps at 320dp and scrolls past that; a row dragged towards an edge
stops at it rather than scrolling the list after it, which is worth knowing
before planning a ride with ten stops on one screen.

**Confirm route** is one `PATCH /trips/:id` per end that actually moved and one
`POST /trips/:id/waypoints` per stop. Not "Start": this app has no turn-by-turn
navigation, and a button saying "Start" on a screen shaped like this one is read
by anyone who has used Google Maps as "begin guiding me". It is off until both
ends are set.

**While the list is up, pressing and holding the map is the short way to a
stop.** Both ends are set at that point, so the only point left to add is a
stop, and the gesture drops one where the finger is — straight to the naming
dialog, which prefills "Stop 3" so confirming takes no typing.

**Nothing is written until Confirm.** That is the point of holding a draft
rather than saving each end as it is picked, which is what the flow before last
did: a rider planning a route used to commit half of it to everybody else's map
and then go looking for the other half, and a rider who changed their mind had
already published the wrong start. While the list is open the map draws the
draft — its flags, its stops, its road — and closing it puts the trip back
exactly as it was.

**The routing quota is defended the same way it was.** A preview is one request
per route a rider actually settles on, keyed on the whole `RoutePlan` through
the same [`map/RouteRequests.kt`](
app/src/main/java/app/ptrip/tracktrip/map/RouteRequests.kt) rule the trip's own
route uses, with the same five-minute wait after a failure. A draft that is the
route the trip already has is not fetched at all — that one has already been
fetched — which is the common case of opening the list to look at what is set.

Adding or moving a stop *is* a new route and does cost a request, which is the
price of the sheet quoting the right distance. A drag that ends where it started
costs nothing: the view model compares the draft before and after and returns
early when they are equal. The server's limit of six routes a minute per rider
is unchanged, so a rider re-ordering faster than one move every ten seconds will
hit it; the sheet then falls back to the leg measure captioned "direct" until
the five-minute wait is up.

**Editing after confirming is the same list.** It opens seeded from the trip, so
changing one end is one tap.

**There are two confirms, because there are two screens.** Over the map the
route list is a card that closes on confirm, and the draft has to go with it: a
draft left on the shared view model outranks the trip's own stops in
`TripMapUiState.drawnWaypoints`, so the map would fly a rider's abandoned pins
for the rest of the ride. That is `confirmRouteAndClose`.

On Edit trip the list *is* the screen. Nothing closes, so clearing the draft
tidies nothing away — it blanks the rows the rider just pressed Confirm on,
straight back to "Choose a starting point". One call doing both jobs was
reported as the route vanishing on confirm, which is the same shape of bug as
the name's Save sharing an exit with the back arrow one release earlier.
`confirmRoute` is the other half: it writes, then re-seeds the draft from what
came back, through the same `RouteSetupRules.reseeded` guard `openRouteSetup`
uses, so a rider who started another edit while the write was out keeps it.

**The list is the trip's real route.** It is seeded from `GET /trips/:id/waypoints`,
so re-opening a trip shows the stops it actually has, each carrying the
`trip_waypoints` id it came from. That was not true at first, and the symptom
was the bug this replaced: a rider added two stops, watched the pins land, left
the trip and came back to From and To with nothing between them, because the
list only ever described things it had made itself.

Confirming is a **difference**, not a rewrite — `RouteSetupRules.waypointEdits`
computes removals first, then a PATCH per saved stop that moved or was renamed,
then a POST per row that has never been saved. A stop nobody touched costs
nothing. Posting the seeded list as-is would have duplicated every existing stop
on every visit, doubling the route each time.

Two cases the diff handles because the trip is shared and the list on screen can
be minutes old: a row whose id the trip no longer has is dropped rather than
re-created, so a stop somebody else deleted is not resurrected; and a `404`
while applying is taken as "somebody got there first" and the remaining edits
still run. `openRouteSetup` also re-reads the waypoints before seeding — the
screen opens the list as soon as the *trip* has loaded, which on a cold start is
before the waypoints have.

**A member who does not own the trip** sees the two ends with neither grip nor
cross, and a line saying why: `PATCH /trips/:id` is owner-only, and offering
them a control whose save comes back 403 would read as a broken app rather than
as a rule.

Re-ordering became owner-only too, once a drag started being written. Moving one
stop renumbers the ones around it, so a member dragging their own stop up two
places rewrites two stops that are not theirs — the server refuses those, and
the rider would be left with a route half renumbered and no way to tell which
half. Members still add stops and remove their own; where those stops sit in the
ride is the owner's call.

### Finding a place

The picker is a screen, not a panel — `PlaceSearchScreen` in
[`ui/TripMapScreen.kt`](app/src/main/java/app/ptrip/tracktrip/ui/TripMapScreen.kt),
opaque, over everything, opening focused with the keyboard up.

It used to be a panel under the top bar with its results capped at 240dp so the
map stayed visible behind it. With the keyboard up that left room for three
results, and the answer was very often the fourth — a search that had worked
looked like a search that had found nothing, and the fix was to scroll a list
nothing said was scrollable.

It searches **two lists at once**, and they fail separately.

#### Shared places — the riders' own list

`GET /geocode/search` is LocationIQ over OpenStreetMap, and OSM is thin in
exactly the way that matters here. "ปตท สวนดอก" — a petrol station every rider
in Chiang Mai can name — is not in it: the stations around Suan Dok are tagged
`PTT` and nothing else, and "สวนดอก" is not an administrative area there. There
is no query that finds it, because the row is not there.

So the riders write it down. [`data/SharedPlacesApi.kt`](
app/src/main/java/app/ptrip/tracktrip/data/SharedPlacesApi.kt) talks to
`/places` on this app's own backend (see the backend README); everything a
signed-in rider adds, every other rider on the server finds.

- **Adding** is the row under the results — offered whatever the search came
  back with, not only when it found nothing, because a rider looking at eight
  towns none of which is the petrol station they meant is in the same position
  as one looking at an empty list. Tapping it does not save anything: it arms
  the map. The name is known and the point is not, and a point is a thing you
  say by pointing at it, so the picker steps aside, a banner says the name back
  and asks for a long press, and the press opens the naming dialog. Saving
  writes the place **and** drops it into the row the rider was filling when they
  gave up searching.
- **The two sources are told apart on the row.** A LocationIQ row is a map
  company's record of somewhere; a shared row is a rider saying "this is here, I
  have been". Both are useful and they are not the same claim, so shared rows
  carry a tag, sit under a heading of their own, and say who wrote them.
- **Shared rows come first.** They are the answer the geocoder could not give,
  and there are never many — the server sends at most five.
- **Removing** is a cross, offered only to whoever typed the place in. Everyone
  can see every row, so a cross on somebody else's would be an invitation to
  remove something the group is still using. A super user can remove any of
  them, from the server side.

**The two failure modes are kept apart, and that is the whole reason this is a
second call rather than something folded into `/geocode/search`.** That route
answers 503 with no API key on the server, 429 when the day's quota is gone and
502 when LocationIQ is unreachable — and on every one of those days the place
somebody wrote down is still here. A geocoder failure with shared results behind
it is not reported at all: saying "the search failed" over the row a rider was
looking for would send them hunting a fault that is not theirs. The mirror case
is a backend older than this app, where `/places` 404s and the geocoder answers
perfectly well; that is swallowed for the same reason.

Both lookups are children of the job the debounce cancels, so a search
abandoned at the keyboard is abandoned at both servers, and one keystroke costs
one request to each rather than one per letter.

#### Private places — home, work, and nobody else's business

A third list, and the only one that is not shared. [`data/PersonalPlacesApi.kt`](
app/src/main/java/app/ptrip/tracktrip/data/PersonalPlacesApi.kt) talks to
`/me/places`; nothing this rider saves there is visible to anybody else on the
server, including a super user. See the backend README for why that is a second
table rather than a flag.

They are drawn as a row of **chips at the top of the picker**, above both
search lists. They are not results — a result is something a rider is choosing
between, and these are decisions already made — so one tap fills the row and
closes the screen, with no request: the coordinate arrived with the chip. The
chip says the label ("บ้าน"), the line under it says the place's own name,
because "บ้าน" a year later is not obviously the same address it was.

`PersonalPlace` deliberately has **no `asPlace()`**, unlike `SharedPlace`. A
private row must not be able to enter the merged search list — everything in
that list is drawn with a tag saying who can see it, and there is no honest tag
for "nobody but you" in a list of things everybody has. There is a test that
fails if one appears.

#### Saving a place: which list?

The long press ends in one dialog — the pin is dropped, the name is typed, and
the last question is who gets to see it, which is the order the decision
actually happens in. Two options, spelled out in words rather than behind a
padlock glyph: **"Everyone on this server"** and **"Only me"**, each with a line
saying what it means. Shared is the default and nothing is remembered between
dialogs; this is the one screen in the app where the wrong answer publishes an
address, so it is asked every time.

Choosing "Only me" adds a shortcut-name field, prefilled from the place's name
and offered as two chips — the honest answer is "บ้าน" or "ที่ทำงาน" almost
every time.

### The map with no trip

[`ui/PlacesMapScreen.kt`](app/src/main/java/app/ptrip/tracktrip/ui/PlacesMapScreen.kt),
reached from the pin button on the trip list.

Two things a rider could previously only do from inside a ride — look a place up,
and write one down — are things people think of when they are *not* riding. The
petrol station nobody could find last weekend is worth adding on a Tuesday
evening, and until now the only way in was to open a trip and pretend to plan a
route on it.

What it deliberately is not is a ride: no riders, no positions, no position
reporting, no route, no poll, no live feed. Everything that makes the trip map
expensive is about the ride rather than the map, which is why
[`PlacesViewModel`](app/src/main/java/app/ptrip/tracktrip/ui/PlacesViewModel.kt)
is its own object rather than the trip map's with nulls threaded through it —
a null trip would leave every one of those features one missed branch from
running against nothing.

What it shares is shared as pieces rather than as a mode: the same `RiderMap`,
the same full-screen picker, the same save dialog, the same long press. A rider
who learned the gesture on one screen already knows it on the other.

The rider's own places used to be printed under the map, in two headed sections
with a cross at the end of every row — an inch below a full-bleed map that a
thumb is already panning and pinching. The report was places disappearing, and
the cause was a finger landing where it was not aimed. The list is on **Settings**
now ([`ui/MyPlaces.kt`](app/src/main/java/app/ptrip/tracktrip/ui/MyPlaces.kt)),
reached from the gear in this screen's own header, and unchanged in every other
respect: the same two sections — **"Only you"** and **"Shared with everyone"**,
because a rider tidying up needs to know which is which without tapping
anything — and the same confirmation in front of every removal. The places
themselves did not leave the map; they are still pins on it, and tapping a pin
still offers to remove it, which is aiming rather than brushing past.

The shared half is the whole shared list filtered to this rider's own rows on
the phone: the server has no "mine" filter and should not grow one, since a
shared list is shared and an endpoint answering "just yours" would be a second
way to ask a question it deliberately does not care about.

#### The rows became cards, and the cards open

Once the list was on Settings it was still bare rows in a column, and the only
thing on a row that could be pressed was the cross that deleted it. A rider with
two PTTs written down had no way to tell which was which before removing one:
the row showed a name and one supporting line, and everything else the app knew
about the place — where it actually is, when it was written down, who by — was
on no screen at all.

Each place is a row inside one of two **group cards** — "Only you" and "Shared
with everyone" — and tapping a row opens what the app knows: the coordinate
(there is no address; a shared place is a name somebody typed over a point they
chose, and nothing on the server can reverse-geocode it), the day it was added,
who added it where that is known, and who can see it.

The grouping was the second half of the fix. The headings were loose text and
every place was its own floating card, so a rider with three private places and
four shared ones read as seven identical cards with two captions somewhere among
them — and nothing in that drawing said which three were private. That
distinction is the only thing on this screen that matters, because one list is
visible to the whole server and the other to nobody. A card is how this app
already says "these belong together", so the *group* is the card and the rows
inside it are plain rows on a divider: a card inside a card is two boundaries
claiming the same thing. A group with nothing in it is not drawn at all — an
empty "Only you" card would promise a rider private places they do not have. A private shortcut also shows the name a
route calls it by, which is usually not the label on its chip; it never shows an
author, because `personal_places` has no join to `users` — the owner is the
caller, and that is the property the whole table rests on.

Two consequences worth stating. The whole row is now a target that does
something harmless, so a mis-tap opens a card instead of deleting a place. And
the cross has an alternative beside it, so pressing it is a choice between two
things rather than the only thing on the row to press. The confirmation in front
of a removal is unchanged; what is new behind it is that the delete now says
whether it worked (see [Saying whether it worked](#saying-whether-it-worked)),
and that the card greys out with a spinner while it is in flight — the gap a
rider used to fill by tapping the cross a second time, which sent a second
DELETE and came back 404 on a place that had gone exactly as asked.

`created_at` was already in both endpoints' responses and simply unread until
the card needed it.

Pins for both lists are drawn on one map, so the id has to say which list a tap
came from: a shared place keeps its own rowid, which is positive, and a private
one becomes `-(id + 1)`, which is at most `-2` and can never collide.

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
anyone who said no or wants to check.

Where that row *goes* depends on where the rider already stands, and getting
that wrong made it a dead button. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
is a dialog only while the app is being optimised; launched by a rider who is
already exempt, the system activity finds nothing to grant and finishes without
drawing a pixel — so on exactly the phones whose row read "Unrestricted",
pressing it did nothing. `BatteryExemption.destinations` picks now: exempt goes
to the app's own page in Android's settings, where "Battery" is one tap in and
the choice is theirs either way; optimised still leads with the one-tap dialog.
It hands back an ordered list rather than an answer, because any one of those
activities can be missing on a given build, and the press has to land somewhere
rather than nowhere. On top of that, Settings shows the
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

## Saying whether it worked

Every write in this app went out the same way and came back three different
ways. A rename put its failure on the form it was typed into; a leave put its
failure on the member list; a place deleted itself off a list and said nothing
at all, success or failure — which is the one that started this. A rider
tapping the cross on a place could not tell "gone" from "the request never
landed", because for the second or two it takes both look like a row that is
still there. Their only move was to press it again.

Successes were worse: there were none. The only signal that anything had worked
was the screen changing underneath, and half of these actions change nothing
visible — an invitation sent, a trip renamed from a form that closes, a place
removed from a list already scrolled past.

So there is now one rule: **every action that writes to the server says whether
it worked**, in one place and one look.

- [`ui/Feedback.kt`](app/src/main/java/app/ptrip/tracktrip/ui/Feedback.kt) is
  the channel. A view model raises a `FeedbackMessage` — a tone, a wording, and
  optionally something to retry — into the one `FeedbackCenter` on
  [`AppContainer`](app/src/main/java/app/ptrip/tracktrip/data/AppContainer.kt).
- [`ui/FeedbackHost.kt`](app/src/main/java/app/ptrip/tracktrip/ui/FeedbackHost.kt)
  collects it, on `MainActivity`'s own `Scaffold` rather than on any screen's.
- [`ui/theme/HudSnackbar.kt`](app/src/main/java/app/ptrip/tracktrip/ui/theme/HudSnackbar.kt)
  draws it: green for a success, red for a failure, and a **Retry** where there
  is something to retry.

### Why the bar is on the activity and not on the screen

Several of these actions navigate as they land — creating a trip opens it,
joining one opens it, leaving one goes back to the list — so a snackbar owned by
the screen that started the write would be torn down in the same frame as the
answer arrived. Above the navigation stack, the answer outlives the screen that
asked the question. It also means there is exactly one bar: two hosts is how an
app ends up stacking two answers to one press.

### Why the newest answer wins

The host collects with `collectLatest`, and that is load-bearing.
`showSnackbar` suspends for as long as the bar is on screen, so a plain
`collect` would park the collector inside the first message for four seconds
while the second waited — and the answer to a press a rider made *now* would
arrive after the last one had finished being read, looking like an answer to the
wrong question. Collecting the latest cancels the suspended call, takes the old
bar off and draws the new one.

That is also why one press of Invite raises **one** message even though it is
one request per address: a success and a failure for the same press would flash
the first and leave only the second. A press with any refusal in it reports the
refusal — the half with something left to do about it — and what did land is
listed on the form beside what did not.

### Where the words come from

A view model has no `Context` and must not have one, so it sends a resource id
and its arguments (`FeedbackText.Res`) rather than a sentence. That keeps every
success translatable, which `StringCoverageTest` enforces.

Failures are the exception: they carry the backend's own `error` string
(`FeedbackText.Plain`), shown verbatim, for the reason
[`ApiErrorText`](app/src/main/java/app/ptrip/tracktrip/ui/ApiErrorText.kt)
already gives — "trip has ended" and "only the trip owner can do this" are
written to be read, and re-wording them here would let the app's rules drift
from the server's. A failure that never reached a server with an opinion falls
back to `feedback_failed`, so a red bar is never blank.

### Where Retry is offered, and where it is not

Retry is offered where repeating the call *is* the whole fix: ending a trip,
leaving one, renaming one, deleting a place or a stop, accepting an invitation,
uploading a photo, moving the sharing switch. It is deliberately absent where a
second attempt would be either a different request or the same refusal:

- **a join code** is single-use, and the two failures a rider meets are
  "expired" and "unknown" — a Retry would re-present a code that is already gone;
- **creating or joining a trip while already out on one** is refused until that
  ride is ended, and a button that earns the same refusal teaches a rider to
  ignore it;
- **confirming a route** has already re-seeded its draft from the server by the
  time it fails, so a second press would send whatever is on screen now under a
  label promising otherwise. Confirm is still one tap away.

### Which actions say something

Everything that writes: create trip, rename trip, end trip, leave trip, accept
invitation, send invitations, join by code or QR, issue a QR / share link
(failures only — the code appearing *is* the success), start and stop sharing,
the settings sharing switch, save profile, upload photo, add and delete a shared
place, add and delete a private place, confirm a route, set a start or
destination, add and remove a stop.

The one write that stays silent is the position report the location service
sends every ten seconds. It is not something a rider pressed, and a bar for it
would be a bar every ten seconds; how that is going is already answered by the
sharing notification and by the fix age on the map.

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

### DELETE, which never worked

`ApiClient.delete` has existed since shared places did, and the `when` that
turned a method name into a request had no `"DELETE"` branch — so every
removal fell through to `throw IllegalArgumentException`. Deleting a shared
place, a private place or a waypoint could never have worked.

It went unnoticed because of what it threw. `IllegalArgumentException` is not
an `ApiException`, so the `catch (e: ApiException)` in every view model let it
past: the failure left the coroutine instead of landing on the error line, and
nothing on screen said the removal had not happened. And no test could see it,
because the mapping sat in a private function that needed a `Context` and a
socket to reach.

The mapping is `applyMethod` now — a `Request.Builder` extension with no
Android in it, which `ApiMethodsTest` walks verb by verb. Adding a verb to
`ApiClient` without wiring it there fails the build. `DELETE` goes out
explicitly bodyless: OkHttp's no-argument `delete()` attaches an empty body,
and a DELETE carrying `Content-Length: 0` is the shape some proxies are
fussiest about.

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

## Building a release APK

One-off setup, on the machine that holds the keystore:

```bash
cd android
cp local.properties.example local.properties   # keep any sdk.dir line already there
$EDITOR local.properties                       # fill in the four RELEASE_* values
```

Then:

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`, signed, with the
application id `app.ptrip.tracktrip` — no `.debug` suffix, so it is the id
registered with Google and the one the Play Store would expect.

`local.properties` is gitignored and stays on your machine. Nothing reads these
values from the environment and CI never sees them, which is deliberate: a
release key CI could reach is a release key anybody with push access could sign
with. CI builds debug only.

### It fails rather than guessing

A missing or half-filled `local.properties` stops the build at
`:app:packageRelease`, before an APK exists, and names what it could not find:

```
> Release signing is not configured.

    android/local.properties is missing: RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD

  Copy android/local.properties.example alongside it and fill in the
  four RELEASE_* values. See android/README.md, "Building a release APK".
```

The two outcomes it exists to prevent are a release signed with the **debug**
key and a release that is **unsigned**. Both install perfectly well on the
machine that built them, both look like a working build, and neither can ever
be updated by a properly signed one — Android refuses an update whose signing
certificate differs from the installed app's.

`assembleDebug` and `testDebugUnitTest` do not read any of this and work with
no `local.properties` beyond `sdk.dir`.

### Bump `versionCode` on every release

`versionCode` is the integer Android compares; `versionName` is the string a
human reads. **The installer only looks at `versionCode`** — ship two builds on
the same one and the second cannot be installed over the first. The rider has
to uninstall, which takes their stored tokens with it, so they sign in again.

It only ever goes up, and nothing requires it to track `versionName`. Currently
`versionCode = 1`, `versionName = "1.0.0"`, both in
[`app/build.gradle.kts`](app/build.gradle.kts).

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

## Building a release APK in CI

`.github/workflows/release-apk.yml` builds the same signed APK on a GitHub
runner, using the release keystore from repository secrets.

**Run it from the Actions tab**: *Actions → Build signed release APK → Run
workflow → Run workflow*. Pick the branch if you want a release off something
other than `main`.

It is `workflow_dispatch` **only** — no push, no tag, no schedule. A release is
a thing somebody decides to make, not a side effect of pushing, and this is the
one workflow that holds a key whose misuse cannot be undone. The debug APK is
still built on every push by `build-apk.yml`; that is the one for testing, this
is the one you hand to a rider.

> A `workflow_dispatch` workflow only appears in the Actions tab once it is on
> the **default branch**. Until this is merged to `main` there is no button to
> press.

### The four secrets

Under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the keystore, base64 as one unbroken line |
| `RELEASE_STORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | the alias inside the keystore |
| `RELEASE_KEY_PASSWORD` | the key password |

```bash
base64 -w0 ~/keystores/tracktrip-release.jks > keystore.b64   # Linux
base64 -i ~/keystores/tracktrip-release.jks -o keystore.b64   # macOS
```

One line, no `BEGIN`/`END` headers, no wrapping. Delete `keystore.b64`
afterwards — don't leave it lying around. Keep the local `.jks` as the source
of truth: the secret is a copy, not a backup.

The workflow names any that are missing and stops before doing anything else,
rather than letting Gradle fail later talking about a `local.properties` file
that does not exist on a runner.

### ⚠️ PKCS12 keystores cannot have two different passwords

If your keystore is **PKCS12** — which is what `keytool -genkeypair` produces
unless told otherwise — then `RELEASE_KEY_PASSWORD` **must equal**
`RELEASE_STORE_PASSWORD`. PKCS12 has nowhere to put a separate key password:
`keytool` prints `Ignoring user-specified -keypass value` when the keystore is
created and carries on, so the key silently ends up using the store password.

The failure it causes does not look like a password problem at all:

```
com.android.ide.common.signing.KeytoolException: Failed to read key ... :
Get Key failed: Given final block not properly padded.
```

The workflow checks for this before building and says so plainly. Note that
`keytool -certreq` does *not* catch it — on PKCS12 it accepts the wrong key
password without complaint.

[`scripts/create-release-keystore.sh`](../scripts/create-release-keystore.sh)
creates a **JKS**, which does support two passwords, so a keystore from that
script is unaffected.

### What it produces

The APK is uploaded as the **`tracktrip-release-apk`** artifact (30 days), and
the run summary carries the application id, versionName, versionCode and the
signing SHA-1 — so you can confirm what was built and which certificate signed
it without downloading anything. That SHA-1 has to be registered in Google
Cloud Console against `app.ptrip.tracktrip` or Google Sign-In refuses the
build.

The decoded keystore lives in `$RUNNER_TEMP`, outside the checkout, so no
artifact glob can reach it; the `local.properties` the workflow writes is never
printed, and both are deleted in a final `always()` step. On a GitHub-hosted
runner that deletion changes nothing — the machine is destroyed — but it is
what makes the job safe on a self-hosted one, which is reused.

### The remaining caution

GitHub masks secrets in logs, but **anyone who can push a workflow change can
exfiltrate them**. Limit who has write access to this repository, and treat
adding the key to CI as the trade it is: convenience now, a wider blast radius
if the account is ever compromised.
