# trip-tracker

Backend for an Android app that shares rider positions among members of a
motorcycle trip. Node.js + Express + better-sqlite3 + `ws`, run under PM2.

This is a monorepo: the backend lives at the root, and the Kotlin/Compose
Android client lives in [`android/`](./android/README.md).

Google Sign-In based auth, a basic profile API, the trip lifecycle
(create → invite → accept → end), and rider position sharing over REST are
implemented. Positions are polled; the WebSocket push feed is still a stub.

## Stack

- Node.js (>=20) + Express
- SQLite via `better-sqlite3`
- `ws` for the WebSocket server (stubbed for now)
- PM2 for process management in production
- nginx + certbot as the reverse proxy / TLS terminator in front of the app

## Project layout

```
src/
  app.js            # Express app factory (used by both index.js and tests)
  config.js         # reads .env
  db/
    index.js        # better-sqlite3 connection
    migrate.js       # migration runner (npm run migrate)
    migrations/      # numbered .sql migration files
    cleanup.js        # position_history retention cleanup logic
  auth/
    google.js        # verifies Google ID tokens
    jwt.js            # signs/verifies access tokens
    refreshTokens.js  # issue/hash/rotate/revoke refresh tokens
    users.js          # upsert user by google_sub
    middleware.js      # requireAuth Express middleware
    tripMembership.js  # requireTripMembership (loads trip, enforces 403)
    tripOwnership.js   # requireTripOwner (owner-only actions)
    tripStatus.js      # requireActiveTrip (409s writes to an ended trip)
    sharingAllowed.js  # requireSharingAllowed (trip active AND rider not paused)
    serializeUser.js   # shapes a user row for API responses
  trips/
    email.js         # normalizes invite email addresses
    serialize.js     # shapes trip / invite rows for API responses
    distance.js      # haversine + the GPS-jitter rules for counting km
    sharing.js       # sharing-session durations, expiry, and the on/off predicate
    joinCodes.js     # QR join codes: generation, expiry, normalisation
    activeTrip.js    # one active trip per rider: the lookup and the 409 body
    membership.js    # "on this trip now" vs "was ever on it" — the left_at rule
  users/
    levels.js        # the rider level table and progress towards the next one
    profile.js       # validation for the editable profile fields
    username.js      # what a username may contain, and what counts as the same one
    avatar.js        # avatar storage: magic-byte sniffing, random filenames
  routes/
    index.js          # health check
    auth.js            # POST /auth/google, /auth/refresh, /auth/logout
    me.js               # GET/PATCH /me, POST /me/avatar
    trips.js             # /trips, /trips/:id/invites, /trips/:id/end, join codes
    invites.js           # /invites, /invites/:id/accept
    waypoints.js         # /trips/:id/waypoints
    positions.js          # /trips/:id/positions
    sharing.js             # /trips/:id/share/start, /share/stop
    geocode.js              # GET /geocode/search (LocationIQ, proxied)
    directions.js           # GET /directions (LocationIQ road routing, proxied)
  geocode/
    locationiq.js     # forward geocoding: URL, parsing, error mapping
    directions.js     # road routing: URL, geometry parsing, line thinning
    cache.js          # the short-lived answer cache both of them use
  ws/                # WebSocket server (stub)
scripts/
  cleanup-history.js         # CLI wrapper for npm run cleanup
  check-one-active-trip.js   # read-only: who is on more than one active trip
test/                 # node:test unit tests
data/                 # SQLite DB file lives here (gitignored)
deploy/
  nginx-api.ptrip.app.conf  # nginx reverse proxy site config
```

## API

### Auth

- `POST /auth/google` — body `{ idToken }`. Verifies the Google ID token,
  upserts the user by `google_sub`, and returns
  `{ accessToken, refreshToken, user }`.
- `POST /auth/refresh` — body `{ refreshToken }`. Rotates the refresh token
  (the old one is revoked immediately) and returns a new
  `{ accessToken, refreshToken }` pair. Presenting a refresh token that was
  already revoked/rotated is treated as token theft: every refresh token for
  that user is revoked.
- `POST /auth/logout` — body `{ refreshToken }`. Revokes that refresh token.

All `/auth/*` routes are rate-limited to 20 requests/minute per IP.

### Profile

Requires `Authorization: Bearer <accessToken>`.

- `GET /me` — current user, including their lifetime `total_km` and the
  optional profile fields (`first_name`, `last_name`, `username`, `phone`,
  `birth_date`). Those are always present in the response and `null` until the
  rider fills them in.
- `PATCH /me` — a **partial** update. Every field is optional; only the keys
  present in the body are written, and unknown keys are ignored rather than
  rejected. An empty body is a no-op, not a `400`.

  | Field | Rules |
  |---|---|
  | `display_name` | 1–40 characters after trimming. May be changed but **not** cleared — it is what other riders see in the member list. |
  | `first_name`, `last_name` | Up to 40 characters. `null` or `""` clears. |
  | `username` | 3–20 characters: Latin (`a-zA-Z`) or **Thai** letters and marks, digits, underscores and single dots between them. Unique — case-insensitively for Latin, and by a Thai-aware fold that treats `สำ` and `สํา` as one name. A clash is a `409`. `null` or `""` clears. See [Usernames, Thai, and what counts as the same name](#usernames-thai-and-what-counts-as-the-same-name). |
  | `phone` | Digits with optional `+` and separators, 8–15 digits. Deliberately loose — it is read by humans, not dialled by the server. `null` or `""` clears. |
  | `birth_date` | `YYYY-MM-DD`, a real calendar date, in the past and within 120 years. `null` or `""` clears. |

- `POST /me/avatar` — `multipart/form-data` with one `avatar` file. Replaces
  the rider's photo and returns the updated user.

  - At most **5 MB** (`413` over that), and only JPEG or PNG (`415`
    otherwise). The declared `Content-Type` is a first pass; the decision is
    made from the file's magic bytes, so a shell script labelled `image/png`
    is refused.
  - The uploaded filename is **discarded**, not sanitised — the file is stored
    under a fresh UUID, which is what makes path traversal a non-question.
  - Written to `UPLOADS_DIR/avatars/` (default `~/tracktrip/uploads`), and
    `users.photo_url` is set to the path `/uploads/avatars/<uuid>.jpg`. The
    previous upload is deleted; a Google URL from sign-in is left alone.
  - **The app does not serve that path.** nginx does — see the
    `location /uploads/` block in `deploy/nginx-api.ptrip.app.conf` and the
    step in `DEPLOY.md`. Until that is applied, uploads succeed and the images
    404.
- `GET /me/level` — the profile screen's challenge widget:

  ```json
  {
    "total_km": 1234.57,
    "level": { "name": "Rookie Rider", "min_km": 500 },
    "next_level": { "name": "Wanderer", "min_km": 1500 },
    "km_to_next": 265.43
  }
  ```

  At the top of the table `next_level` and `km_to_next` are both `null` —
  distinct from `0`, which would mean a promotion is one metre away.

Access tokens are JWTs valid for 1 hour. Refresh tokens are opaque 32-byte
random values valid for 60 days; only their SHA-256 hash is stored in the
`refresh_tokens` table.

### Trips

A trip runs from creation until its owner ends it. The rider who creates it is
its owner (`trips.owner_id`) and its first member; everyone else joins by
accepting an emailed invite. All routes require
`Authorization: Bearer <accessToken>`.

- `POST /trips` — body `{ name }` (1–60 characters after trimming), plus an
  optional `origin` and `destination` (see below). Returns `201` with the
  trip. The caller becomes the owner and is written into `trip_members` with
  `role = 'owner'` in the same transaction. **`409` while the caller is
  already on an active trip** — see [One active trip per
  rider](#one-active-trip-per-rider).
- `GET /trips` — the caller's trips (owned **and** joined), newest first, each
  with the caller's own `role`.
- `GET /trips?all=true` — **super users only**: every trip on the server, in
  the same shape. Trips the caller is not on come back with
  `role: "superuser"` — not `"owner"`, which would be a lie about a real
  column, and not `null`, which every client would have to special-case. The
  parameter is ignored for everyone else, who get their own trips rather than
  a 403: it is a request for more, and the honest answer to it is everything
  they are allowed to see. See [Roles](#roles).

#### One active trip per rider

A rider may be on **one** active trip at a time. `POST /trips`, `POST
/invites/:id/accept` and `POST /trips/join` all answer `409` while the caller
already belongs to a trip whose `status` is `active`:

```json
{
  "error": "You're already on an active trip, \"Chiang Mai loop\". End it before starting or joining another.",
  "code": "active_trip_exists",
  "active_trip": { "id": 4, "name": "Chiang Mai loop", "role": "owner" }
}
```

`code` is there so a client can say it in its own language; `active_trip`
because "you already have an active trip" sends a rider to a list of forty to
work out which, and the answer is usually a ride they forgot to end. The
Android app shows a translated sentence naming the trip, falling back to
`error`.

**Owner and member both count.** The rule is about riding, and both roles ride:
it is the same `trip_members` rows `GET /trips` lists and
`/suggested-invitees` counts as having ridden together. A rule that counted
only the trips you own would let one invitation put you on three rides at once.
`users.role` is unrelated — a super user browsing every trip through
`GET /trips?all=true` holds no membership, and is neither blocked nor counted.

A refused invite is **left pending** rather than consumed, so it is still there
to accept once the trip in the way has ended. Redeeming a join code for the
trip you are already on is still a success, not a conflict.

##### Why the rule exists

The Android client had always assumed it — the phone holds one sharing session
and the location service reports to the trip it was started for — so a rider on
two active trips has a trip on their list that can never receive a position,
and no way to tell which.

##### Enforced twice

The route guards produce the message; migration `0013` is what makes the rule
true. It is a **trigger**, not a partial unique index, for two reasons. A
partial index's `WHERE` may only reference columns of the table it indexes, and
"active" lives in `trips.status`, one join away. And an index is validated
against existing rows when created, so on a database that already holds a rider
with two active trips the `CREATE` fails, the migration transaction rolls back,
and — because migrations run at boot — the API does not start. A trigger only
ever sees writes made after it exists.

That is deliberate for the data as well as for the deploy: **nobody already on
two active trips is evicted.** Those are real trips with real positions in
them, and picking one to close is a decision for whoever owns the data, not for
a migration running unattended at boot. They keep both and are simply refused a
third. `node scripts/check-one-active-trip.js` reports who is in that state:

```
DB_PATH=/root/tracktrip/data/trip-tracker.db node scripts/check-one-active-trip.js
```

It only reads, and exits non-zero when it finds somebody.

A second trigger refuses reviving an ended trip whose members have moved on.
No route does that — `POST /trips/:id/end` is the only writer of `trips.status`
and only ever sets `ended` — but a constraint that holds only for the paths the
application happens to use is a convention, not a constraint, and this repo
documents reaching for `sqlite3` on the server.

##### The way out

Two doors, and they are not the same act renamed. The **owner** ends the trip
(`POST /trips/:id/end`), which closes the ride for the whole group. Everybody
else **leaves** it (`DELETE /trips/:id/members/me`), which takes one rider off
it and leaves the trip running.

The second door exists because of the first. With only `/end`, a rider who
accepted an invitation and whose host then went home without pressing it could
not start a trip of their own, could not accept another invitation, and had
nothing they could do about it — the rule turned somebody else's
forgetfulness into an indefinite lock on their account. A constraint needs a
door the person it constrains can open.

#### Where a trip starts and ends

Every trip carries an optional `origin` and `destination`, serialized as
either `null` or `{ lat, lng, label }`:

```json
{
  "id": 1, "name": "Pai loop", "status": "active", "role": "owner",
  "origin": { "lat": 18.7883, "lng": 98.9853, "label": "Chiang Mai" },
  "destination": { "lat": 19.3583, "lng": 98.4406, "label": "Pai" }
}
```

These are **columns on `trips`** (migration `0009`), not rows in
`trip_waypoints`. A trip has at most one of each, ever — as columns the schema
enforces that, where as rows it would be a rule in application code that the
first client to post twice would break. Waypoints are the opposite shape:
a list, ordered, added and deleted freely mid-ride.

Both are nullable and independent: setting off from a known place with no plan
is an ordinary way to ride, and so is naming a destination before deciding
where to meet. `label` is optional too — a point dropped on the map may have
no name, and the app draws "Start"/"Finish" under it instead. A coordinate
missing its other half is **refused**, not stored: half a position is not a
place, and it would reach the map as a pin in the Gulf of Guinea.

- `PATCH /trips/:id` — **owner only**. Sets or clears either end.

  A partial update, and that is the point of it being a PATCH: a field left
  out is left alone, and sending it as `null` clears it. A PUT would make
  "set the destination" indistinguishable from "wipe the origin". An empty
  body is a no-op rather than an error.

  Allowed on an **ended** trip: filling in where a ride actually finished is
  something people do afterwards, and it writes no position.
- `POST /trips/:id/invites` — **owner only**; body `{ email }`. Returns `201`
  with the invite. Any other member, or a non-member, gets `403`.

  | Case | Result |
  |---|---|
  | New email | `201`, a `pending` invite |
  | Email already invited and still `pending` | `200` with that same invite — re-inviting is how an owner re-sends it, and never creates a duplicate |
  | Email of a `revoked` invite | `200`, the same row reopened as `pending` |
  | Email of an `accepted` invite, or of a current member | `409` |
  | The owner's own email | `400` |
  | Malformed address | `400` |

- `POST /trips/:id/end` — **owner only**. Sets `status = 'ended'` and stamps
  `ended_at`. Returns `200` with the trip; ending an already-ended trip is
  `409`.
- `GET /trips/:id/suggested-invitees` — **owner only**, running trips only.
  Riders the caller has shared a trip with before who are **not** already on
  this one. Returns
  `[{ user_id, email, display_name, username, photo_url, trips_together }]`.

  Ordered by `trips_together` (how many trips the two have shared) descending,
  then by who was ridden with most recently. Frequency rather than recency: the
  person ridden with twenty times is the one being looked for, even when
  somebody met once last weekend is more recent.

  **Unlimited.** The list is bounded by how many people the rider has actually
  ridden with and the client scrolls it; the old cap of ten hid exactly the
  regulars a long-standing group has.

  Owner-only to match `POST /trips/:id/invites`: these are suggestions *for*
  that form, and a member tapping one would get a `403`.

- `GET /trips/:id/member-levels` — **any member**. Every rider on the trip and
  the level their lifetime distance earns them, in one call:

  ```json
  [
    {
      "user_id": 2, "total_km": 1600,
      "level": { "name": "Wanderer", "min_km": 1500 },
      "next_level": { "name": "Voyager", "min_km": 3500 },
      "km_to_next": 1900
    }
  ]
  ```

  Each row is `user_id` plus exactly what [`GET /me/level`](#profile) returns
  for that rider — same `progressFor`, so the map and the profile screen can
  never disagree about where somebody stands.

  A batch because the map lists a level beside every name: `/me/level` only
  ever answers for the caller, and a trip of eight would otherwise be eight
  requests from a phone that is already polling positions. Readable by any
  member, not just the owner — they can already see each other's names and
  positions, and a level is the least private thing on that screen. Works on
  an ended trip: a level is a lifetime figure, not a live one.

#### Join codes

A short-lived code, shown as a QR, for adding someone standing next to you
without knowing their email address.

- `POST /trips/:id/join-code` — **any member** of a running trip. Returns
  `201 { trip_id, code, expires_at }`.

  The code is 8 characters over a 32-symbol alphabet with `I`, `O`, `0` and
  `1` left out (40 bits, drawn from `crypto.randomBytes`), and lives for 15
  minutes. Issuing one **retires the trip's previous codes** in the same
  transaction, so at most one is live at a time.

  Any member, not just the owner: the rider who met someone at a fuel stop is
  the one holding a phone.

- `POST /trips/join` — body `{ code }`. Puts the caller on the trip. Rate
  limited to 10 attempts/minute per IP.

  | Case | Response |
  |---|---|
  | Live code | `200 { trip, already_member: false }` |
  | Caller is already a member | `200 { trip, already_member: true }` — the outcome they wanted, reached sooner |
  | Code not found | `404` |
  | Code expired or retired | `410` — distinct from `404`, because "a minute too late" and "never existed" have different next steps |
  | Trip has ended | `409` |
  | Caller is already on a *different* active trip | `409 { code: "active_trip_exists", active_trip }` — see [One active trip per rider](#one-active-trip-per-rider) |
  | Missing or malformed code | `400`, without reaching the lookup |

  A code is **not** consumed by being redeemed: one QR held up to four riders
  should add four riders. It stops working on expiry, and when the next code
  is issued.

- `DELETE /trips/:id/members/me` — the caller leaves the trip. `204`, with
  nothing to read back.

  | Case | Response |
  |---|---|
  | A member, on a running trip | `204` |
  | The **owner** | `409` — a trip with no owner is one nobody can end, invite to or rename. `/end` is their door |
  | Not on the trip (a super user included) | `403` — managing a trip has never meant riding on it |
  | Trip has ended | `409` — nothing is holding them there, and leaving would take them off a finished ride's roster and its podium |

  One transaction, because half of it is not a state anybody should be in:

  - the **membership**, stamped with `left_at` rather than deleted — see
    [Leaving is a soft delete](#leaving-is-a-soft-delete). Every read that asks
    "are they on this trip now?" takes `left_at IS NULL`, so this alone takes
    them off the roster, off everyone's map, out of their own trip list and out
    of every access check;
  - the **sharing session**, for the same reason `/end` clears everybody's — a
    session outliving the membership is a rider believing they are still being
    tracked. Deleted rather than marked spent: the "no row means sharing by
    default" reading is about *members*, and somebody rejoining should start as
    a new one;
  - the **last known position** — `member_positions`, one live row per rider
    and what the map draws. Left behind it would be an orphan today and a ghost
    tomorrow: re-invited next month, they would appear at the petrol station
    they left from, dated then and drawn now;
  - the **accepted invitation**, back to `revoked`, which is the one state
    `POST /trips/:id/invites` already knows how to reopen. Left `accepted`,
    `UNIQUE (trip_id, email)` would answer "that invite was already accepted"
    for ever, and a door out that cannot be walked back through is half a door.

  `position_history` is deliberately **kept**: it is the record of a ride that
  happened, `cleanup-history.js` already ages it out, and leaving afterwards is
  not grounds to rewrite where everybody went. Waypoints are untouched — a
  route belongs to the trip, not to whoever added a stop to it.

#### Leaving is a soft delete

`trip_members` used to answer "is this rider on this trip?" by existing, and
every read of the table could take a row at face value. Leaving deleted the
row — which took the ride out of the pair's history with it, because "ridden
with before" is built by joining `trip_members` to itself. They *had* ridden
together; that happened, and the app should go on offering them to each other.

So `left_at` (migration `0014`) is stamped instead, and the one rule the whole
codebase relied on splits into two questions that had been the same question:

| | who asks | clause |
|---|---|---|
| **Are they on this trip now?** | access, rosters, the map, the trip list, the leaderboard, the one-active-trip rule | `left_at IS NULL`, strictly |
| **Were they ever?** | `GET /trips/:id/suggested-invitees`, and nothing else | every row, `left_at` ignored |

Getting the first one wrong is not a display bug: a rider who left and still
passed the membership check would keep reading the group's live positions and
its route, and could go on reporting their own — the exact access leaving
exists to end. So the clause lives in
[`src/trips/membership.js`](src/trips/membership.js), which both gates —
`requireTripMembership` and the WebSocket's `readableTrip` — now call.

The second question is asked in one query and says so out loud. Its
*exclusion* — who is already on this trip and so not worth suggesting — is the
first question again, and takes the clause: a rider who left is offered back.

**Rejoining** is that row's `left_at` going back to NULL. The primary key is
`(trip_id, user_id)`, so an invitation accepted or a code redeemed by somebody
who left conflicts with their own dormant row; `ON CONFLICT … DO NOTHING`
would have looked like success and joined nobody, so both are
`DO UPDATE SET left_at = NULL … WHERE trip_members.left_at IS NOT NULL` — a
no-op for somebody already on the trip, a return for somebody who was not.

Migration `0014` rewrites 0013's triggers for the same reason: they counted
rows, and a row is no longer a membership. It also adds a third, because
membership is no longer only gained by INSERT — coming back is an UPDATE, which
a `BEFORE INSERT` trigger never sees.

Once a trip has ended it is **read-only**: members can still read it, but
every write to it — positions and live waypoint drops included — returns
`409 {"error": "trip has ended"}`, and no new invite can be sent or accepted.

Ending a trip also **stops location sharing for everyone in the group**, in
the same transaction: every sharing session on that trip is cleared. Nobody
carries on past the end of a trip; a rider who wants to keep going starts a
trip of their own. See [Sharing sessions](#sharing-sessions).

### Live positions

`GET /ws` upgrades to a WebSocket carrying positions as the server stores them.
A rider watching the map used to find out about a friend on their own next
poll, so the delay they experienced was the reporting cadence *plus* most of a
polling one. This removes the second half.

Authenticate with `Authorization: Bearer <accessToken>` on the upgrade request,
or `?token=` for clients that cannot set headers (a browser cannot; the Android
app can, and does — a token in a URL reaches access logs). An unauthenticated
socket is closed with **4401**.

Then, as JSON text frames:

| Client sends | Server sends |
|---|---|
| `{"type":"subscribe","trip_id":N}` | `{"type":"subscribed","trip_id":N}` |
| `{"type":"unsubscribe","trip_id":N}` | `{"type":"unsubscribed","trip_id":N}` |
| `{"type":"ping"}` | `{"type":"pong"}` |
| — | `{"type":"ready","user_id":N}` on connect |
| — | `{"type":"position","trip_id":N,"position":{…}}` per stored fix |
| — | `{"type":"error","error":"…"}` for anything refused |

The `position` payload is exactly what `GET /trips/:id/positions` returns for
one member, and it is **what the server stored** rather than what was
submitted — a stale report that the position row refused is not announced,
because announcing it would drag every watching map backwards while the
database stayed right.

Subscribing is checked against the same rule the HTTP routes use — membership,
or the super-user role — through the same function (`readableTrip`), so the two
cannot drift apart.

**Positions are not accepted over the socket.** A frame of `{"type":"position"}`
is refused with a message naming `POST /trips/:id/positions`. Writing a fix is
not one write — it stores a position, refuses one that arrives out of order,
records a breadcrumb, and credits lifetime distance under a rate limit sized
against that distance being credited once. A second path into all of that is a
second place to credit the same kilometre twice, and the first symptom would be
a rider's level quietly inflating, which nobody reports as a bug. There is also
nothing to gain: the phone reports from a foreground service on its own
cadence, and the delay a rider *sees* is the screen refresh, which is the half
this removes.

**Abuse.** Every connection carries a token bucket of 60 messages a minute and
may hold at most 8 subscriptions; exceeding either closes the socket with
**4429** rather than answering, because replying "slow down" to a client in a
loop is one more message for it to ignore. Frames over 4 KiB are refused before
they are parsed. `POSITION_RATE_LIMIT` is untouched and still governs writes —
it counts HTTP requests, and a socket is one request that never ends, so the
two limits are for two different things.

**Losing it costs nothing but immediacy.** The socket carries a copy of data
that is already stored and already readable over REST; it adds nothing to the
state, so a client whose connection drops loses none. The app keeps polling
throughout — slowly while connected, at its usual rate when not — and
reconnects underneath with a widening backoff. `createApp` takes the hub as an
optional argument, so a server built without one behaves exactly as it did
before sockets existed.

### Roles

Two account-level roles, in `users.role` (migration `0010`): `user` and
`superuser`. This is **not** `trip_members.role`, which is `owner`/`member` and
says what somebody is on *one trip*. This says what they are on *this
installation*, and the two never touch — promoting a super user does not
rewrite anybody's membership, and a super user is not the owner of anybody's
trips.

One column rather than a roles/permissions/role_permissions triple, because
there are two kinds of account and one privilege between them. Adding
`moderator` later is one `CHECK` constraint and one branch; turning a column
into a join table is a mechanical migration, and going the other way never
gets done.

**Who is one** is decided by `SUPERUSER_EMAILS`, not by a row somebody
remembered to set. `syncSuperuserRoles` reconciles the column with that list
at every boot *and* at every sign-in — the second matters because a fresh
deploy's first super user has no account until the moment they first sign in.
Removing an address from the list demotes them at the next restart, which is
what makes revocation a one-line change with nothing left behind. An account
with no email address is never touched in either direction.

**What it allows** is a widening of checks that already exist, rather than a
set of doors of its own:

| | ordinary rider | super user |
|---|---|---|
| Read a trip they are on | ✓ | ✓ |
| Read any trip (`GET /trips/:id/positions`, waypoints, member levels) | 403 | ✓ |
| Owner-only edits on any trip (`PATCH /trips/:id`, invites, end trip) | 403 | ✓ |
| `GET /trips` | own trips | own trips |
| `GET /trips?all=true` | own trips | every trip |
| **Report a position** to a trip they are not on | 403 | **403** |
| **Start a sharing session** on a trip they are not on | 403 | **403** |

The last two rows are the line the design turns on: **managing a trip is not
riding on it**. `requireTripMembership` lets a super user through and leaves
`req.membership` as `null` — deliberately, rather than synthesising a
`trip_members` row, which would put them in rosters, in position lists, and in
the distance credited to real riders. Routes where the caller acts *as a
member* ask for one explicitly through `requireTripParticipation`, which has no
bypass.

`GET /me` reports the caller's own `role` so a client knows what to offer. It
is never a claim of trust: every route checks the database, so a client that
told itself the wrong thing gets a 403 from its first request.

### Invites

An invite is addressed to an **email**, not to a user id, because the invitee
may not have an account yet. Only the account signed in with that address can
accept it. Matching is case-insensitive: invite emails are stored trimmed and
lowercased, and the caller's address is normalized the same way before it is
compared.

Any domain is accepted, not just `@gmail.com` — riders sign in with Google,
which covers Workspace accounts on custom domains too.

- `GET /invites` — the caller's `pending` invites on trips that are still
  active, each with the inviting trip's `trip_name`. There is no push or email
  notification yet, so this is how an invitee discovers an invite.
- `POST /invites/:id/accept` — the invitee joins the trip. Returns `200` with
  `{ trip, invite }`; the membership row and the invite's
  `status`/`accepted_at`/`accepted_by` are written in one transaction. **`409`
  while the invitee is already on an active trip** — see [One active trip per
  rider](#one-active-trip-per-rider); the invite stays `pending`, so it can be
  taken up once that trip has ended.

  | Case | Result |
  |---|---|
  | Caller's email doesn't match the invite | `403` |
  | Already accepted, or revoked | `409` |
  | The trip has ended | `409` |
  | No such invite | `404` |

### Waypoints

Stop-off points on a trip. Two kinds:

- **`planned`** — set up before the ride, explicitly ordered via `order_index`.
- **`live`** — dropped while riding; no ordering, just chronological.

All routes require `Authorization: Bearer <accessToken>` **and** that the
caller is a member of the trip (a row in `trip_members`). A non-member gets
`403`, an unknown trip `404`, a non-numeric trip id `400`. `POST` and `DELETE`
additionally require the trip to still be active — on an ended trip they
return `409` while `GET` keeps working.

- `POST /trips/:id/waypoints` — body `{ name, lat, lng, type, order_index? }`.
  Returns `201` with the created waypoint.

  | Field | Rule |
  |---|---|
  | `name` | string, 1–60 characters after trimming |
  | `lat` | number, −90 to 90 |
  | `lng` | number, −180 to 180 |
  | `type` | exactly `"planned"` or `"live"` |
  | `order_index` | **required** when `type` is `planned` (non-negative integer); **must not be sent at all** when `type` is `live` — including as an explicit `null` |

- `GET /trips/:id/waypoints` — returns the two kinds separately:

  ```json
  {
    "planned": [ { "id": 1, "name": "Gas stop", "order_index": 0, "...": "..." } ],
    "live":    [ { "id": 2, "name": "Viewpoint", "order_index": null, "...": "..." } ]
  }
  ```

  `planned` is sorted by `order_index` ascending, `live` by `created_at`
  ascending. (Both fall back to `id` as a tiebreaker, so ordering stays stable
  when two rows share a value.)

- `DELETE /trips/:id/waypoints/:wpId` — returns `204`. Permitted only for the
  **trip owner** (`trips.owner_id`) or the **member who added that waypoint**
  (`added_by`); any other member gets `403`. A waypoint that belongs to a
  different trip than `:id` is treated as `404`, so it can't be deleted through
  the wrong trip's URL.

### Positions

Where each rider is right now. One row per rider per trip in
`member_positions` — the latest fix, not a trail.

Both routes require `Authorization: Bearer <accessToken>` **and** trip
membership. Reads stay open once a trip has ended, so a finished ride can
still show where everyone ended up. Writes are gated **per rider** — see
[Sharing sessions](#sharing-sessions) below. Membership is checked before
anything else, so a non-member gets `403` either way; ending a trip never
makes it public.

Clients poll `GET` **and** may subscribe to a WebSocket for the same data as
it is stored — see [Live positions](#live-positions). The poll is the record;
the socket is a shortcut.

- `POST /trips/:id/positions` — the caller reports their own position. Returns
  `200` with the stored position.

  | Field | Rule |
  |---|---|
  | `lat` | number, −90 to 90 |
  | `lng` | number, −180 to 180 |
  | `timestamp` | optional ISO 8601 string — when the *device* took the fix, not when the request arrived. Defaults to now, and is normalized to UTC. |
  | `accuracy` | optional number, 0 or greater (metres) |
  | `speed` | optional number, 0 or greater — **metres per second** |
  | `heading` | optional number, 0 to 360 |
  | `battery_pct` | optional integer, 0 to 100 |

  `200` rather than `201`: there is one row per rider, so this creates it the
  first time and replaces it after that. The caller can only ever write their
  own position — the row is keyed on the authenticated user, not on anything
  in the body.

  **Each accepted fix also credits distance** towards the rider's lifetime
  `users.total_km` — see [Lifetime distance](#lifetime-distance) below.

  Capped at **30 reports per minute per rider** (`POSITION_RATE_LIMIT` in
  `src/routes/positions.js`), answering `429 {"error": "too many position
  updates"}` above that. The app reports every **10 seconds** while a rider
  is sharing — 6 a minute — so this is a safety net against a client stuck
  in a retry loop, not a quota: no real rider will reach it. (It was 10/min
  against a 45-second cadence. The cadence came down to 10 seconds and the
  ceiling went up with it: at 6 a minute against 10, the headroom would have
  been 1.67x and the first thing the limiter caught would have been a real
  report. `ReportCadenceTest` on the Android side and the pair of tests in
  `test/positions.test.js` are what keep the two numbers in step.) It is keyed on
  the **rider**, not the IP, because a group riding together is usually
  behind one carrier NAT and would otherwise throttle each other. `GET` is
  not capped: the limit is derived from how often a rider reports, and the
  map screen refreshes on its own cadence (every 20 seconds — see
  `LiveCadence` for why that did **not** move with the reporting cadence).

- `GET /trips/:id/positions/history` — **the trail**: one point per fix the
  trip has accepted, oldest first, as `{ trip_id, truncated, points: [{ id,
  user_id, lat, lng, recorded_at }] }`.

  `GET /trips/:id/positions` answers *where is everyone now* from
  `member_positions`, which holds one row per rider and is overwritten on
  every report. This answers *where have they been*, from `position_history` —
  a different question with a different shape, one row per fix.

  Optional `user_id` (one rider's line rather than eight overlapping ones),
  `since` (an ISO timestamp — fetch the trail once, then ask only for what has
  been added), and `limit` (default 500, capped at 1000; an outrageous value is
  clamped rather than refused). `truncated` says the server had more to give,
  so a client can tell "that is the whole trail" from "that is as much as you
  asked for" instead of drawing a line that stops in the middle of a road.

  Only *accepted* fixes are recorded: a stale report is ignored by the position
  row and by the trail alike, or a phone flushing a backlog would scribble over
  a route that was already right. Reads stay open on an ended trip — looking at
  where a group went is most of the point of having gone. Purged for ended
  trips by the cleanup job after `HISTORY_RETENTION_DAYS`.

  **Older fixes are ignored.** A retry, or a phone flushing a backlog after
  losing signal, can deliver an old fix after a newer one; the stored row only
  moves forward in time, so the map never jumps backwards. The response body
  is always what the server now holds, which is *not* the submitted fix when a
  stale one was dropped. A fix bearing the same timestamp does overwrite, so a
  correction isn't stuck.

- `GET /trips/:id/positions` — the latest position of **every** member, as a
  flat array for the map and the friend list:

  ```json
  [
    {
      "user_id": 2, "display_name": "Member", "username": "speedy",
      "photo_url": "member.jpg",
      "role": "member", "is_sharing": true, "sharing_until": null,
      "lat": 19.1, "lng": 99.2, "accuracy": 12.5, "speed": 22.4,
      "heading": 275.5, "battery_pct": 84,
      "recorded_at": "2026-05-01T09:00:00.000Z"
    }
  ]
  ```

  Sorted freshest first. Members who have never reported are **still listed**,
  with every position field `null`, and sort last — the friend list shows them
  as not yet tracking rather than dropping them. Emails are not included.

  `username` is the handle the rider chose, or `null`. Clients show it in
  preference to `display_name`, which is whatever Google supplied.

  `speed` is **metres per second**, stored exactly as the device reported it —
  which is the unit Android's `Location.getSpeed()` uses. Converting on the
  way in would have put a unit in the database that nothing else in the system
  uses, so the conversion to km/h happens once, in the client, at the point of
  display. `null` means the phone never sent a speed for this fix; a rider
  stopped at a light sends a real `0`, and the two are not the same thing.

  `is_sharing` answers exactly what the write guard would: *may this rider be
  reporting right now?* On a running trip that is everyone. Once the trip ends
  it is only those who chose to carry on. `sharing_until` is when that rider's
  own session lapses — `null` for no session, and also for a session with no
  expiry, which `is_sharing` tells apart.

### Sharing sessions

**Sharing is on by default.** A rider who never touches the sharing controls
shares their location for the whole trip — most riders have no session row at
all, and no row means sharing.

A session exists only once someone has reached for the controls, and it lives
in `sharing_sessions`, one row per (trip, rider). `expires_at` is when it
lapses on its own, `NULL` for "until I stop it", and a timestamp in the past
for a rider who has **paused**. Stopping marks the row spent rather than
deleting it — deleting would leave no row, and no row is how an unpaused
rider looks, so it would silently switch sharing back on.

`POST /trips/:id/positions` is allowed when **both** hold:

- the trip is still `active`; **and**
- this rider hasn't paused — no session, or one that is still running.

| Situation | Result |
|---|---|
| No session, trip running | `200` — the ordinary case |
| Session running | `200` |
| Paused, or the session ran out | `409 {"error": "your sharing session has ended"}` |
| Trip has ended | `409 {"error": "trip has ended"}`, for everyone, whatever session they had |

The same condition is reported as `is_sharing` on every position read, so a
client can always tell in advance whether a report would be accepted.

Both routes require membership **and** an active trip — a session on a
finished trip would be state nothing will ever read:

- `POST /trips/:id/share/start` — body `{ duration_minutes }`, one of **30**,
  **60**, **240**, or `null` for "until stopped". The field is **required**:
  omitting it is a `400`, not a silent unlimited session. Starting again
  replaces the session, which is also how a paused rider resumes. Returns
  `200`:

  ```json
  {
    "trip_id": 1, "user_id": 2, "sharing": true,
    "started_at": "2026-08-17T13:07:31.487Z",
    "expires_at": "2026-08-17T17:07:31.487Z"
  }
  ```

- `POST /trips/:id/share/stop` — pauses this rider. `409` if they were
  already paused.

The durations on offer live in `SHARING_DURATION_MINUTES` in
`src/trips/sharing.js`.

Spent sessions are left in the table rather than swept on a timer;
`isSharingOn` is the single place that decides, so the write guard and the
map can never disagree.

## Setup

```bash
git clone <repo-url>
cd trip-tracker
npm install
cp .env.example .env   # then edit .env with real values
npm run migrate         # creates data/trip-tracker.db and applies schema
npm test
```

## Running

```bash
npm start
```

## Database migrations

Migrations are plain numbered `.sql` files in `src/db/migrations/`
(e.g. `0001_init.sql`). Applied migrations are recorded in a
`schema_migrations` table so `npm run migrate` is safe to run repeatedly —
already-applied files are skipped, and existing tables are never dropped.
To make a schema change, add a new numbered file; don't edit files that
have already shipped.

## History cleanup

`position_history` rows for **ended** trips older than
`HISTORY_RETENTION_DAYS` (default 30) can be purged with:

```bash
npm run cleanup            # dry-run: reports what would be deleted
npm run cleanup -- --confirm   # actually deletes
```

Only history belonging to trips with `status = 'ended'` and an `ended_at`
older than the retention window is affected; active trips are never
touched. Wire this into a cron/PM2 scheduled job on the VPS once deployed.

## Deploying (Ubuntu 24.04 VPS, alongside other Node apps + an existing nginx)

For full step-by-step, first-time deployment instructions (including
getting a TLS cert with certbot and how to roll back), see
[`DEPLOY.md`](./DEPLOY.md). Summary, for a VPS that already has
Node/PM2/nginx/certbot set up and other sites running:

1. Pick a `PORT` in `.env` that isn't already in use by another app on
   the VPS, and set a unique `JWT_SECRET` — see `DEPLOY.md` for why it
   must not be reused from another app on the same box.
2. `git clone`, `npm ci --omit=dev`, set up `.env`, `npm run migrate`.
3. Start it under PM2 using the process name already set in
   `ecosystem.config.cjs` (`tracktrip-api`), so `pm2 restart`/`pm2 stop`
   only ever target this app, not others already running:
   ```bash
   pm2 start ecosystem.config.cjs
   pm2 save
   ```
4. Symlink `deploy/nginx-api.ptrip.app.conf` into
   `/etc/nginx/sites-enabled/`, run `sudo nginx -t` (always, before every
   reload — a bad config can take down the other sites nginx is already
   serving), then `sudo systemctl reload nginx`, then run
   `sudo certbot --nginx -d api.ptrip.app` to get the TLS certificate.
5. Schedule `npm run cleanup -- --confirm` periodically (e.g. a daily
   cron entry or a PM2 cron restart job) to enforce
   `HISTORY_RETENTION_DAYS`.

## Environment variables

All variables are documented in `.env.example`:

| Variable                  | Description                                                                 | Default                    |
|----------------------------|-------------------------------------------------------------------------------|-----------------------------|
| `HOST`                    | Address the server binds to. Keep `127.0.0.1` in production so the port is reachable only through nginx, not directly from the internet. | `127.0.0.1`   |
| `PORT`                    | Port the Express + WebSocket server listens on. Choose one free on the VPS. | `4100`                     |
| `JWT_SECRET`              | Secret used to sign/verify JWTs issued to the Android app.                 | *(required, no default)*   |
| `GOOGLE_CLIENT_ID`        | Comma-separated OAuth client ID(s) accepted when verifying Google Sign-In tokens (e.g. web + Android client IDs). | *(required, no default)*   |
| `DB_PATH`                 | Path to the SQLite database file.                                          | `./data/trip-tracker.db`   |
| `UPLOADS_DIR`             | Where uploaded avatars are written. Must be outside the repo (a deploy replaces the checkout) and must match the `root` in nginx's `location /uploads/` block. | `~/tracktrip/uploads` |
| `HISTORY_RETENTION_DAYS`  | Days of `position_history` to keep for ended trips before cleanup deletes it. | `30`                     |
| `SUPERUSER_EMAILS`        | Comma-separated emails granted the super-user role — see [Roles](#roles). The list is the authority: it is reconciled with `users.role` at every boot and sign-in, so removing an address revokes the role. | `krongkrangrn@gmail.com` |
| `LOCATIONIQ_API_KEY`      | LocationIQ access token behind `GET /geocode/search` (the map's place search) and `GET /directions` (the road route drawn between a trip's ends). Server-side only — see [Place search](#place-search) and [Road routing](#road-routing). Unset is supported: those two routes answer 503 and nothing else changes. | *(empty)* |
| `LOCATIONIQ_COUNTRY_CODES`| Optional comma-separated ISO 3166-1 alpha-2 codes to bias searches towards, e.g. `th`. Unset searches the whole planet. | *(empty)* |

Never commit a real `.env` file — it's excluded via `.gitignore`.

## Usernames, Thai, and what counts as the same name

`username` is Latin letters, **Thai letters and marks**, digits, underscores and
single dots, 3 to 20 characters, no leading dot and no trailing dot. The rule
and its reasoning live in [`src/users/username.js`](src/users/username.js).

### Why the pattern is spelled out instead of using script properties

The obvious way to add Thai is `\p{Script=Latin}` and `\p{Script=Thai}`, and it
is wrong in both directions. Measured, not assumed:

```js
/^\p{Script=Latin}$/u.test('Ｐ')  // U+FF30 FULLWIDTH LATIN CAPITAL P -> true
/^\p{Script=Latin}$/u.test('ı')  // U+0131 DOTLESS I                 -> true
/^\p{Script=Thai}$/u.test('๏')   // U+0E4F FONGMAN, category Po       -> true
```

`Ｐｏｏｍ` and `ı` are exactly the impostors a unique username exists to keep
apart from `Poom` and `i`, and the old ASCII-only pattern was blocking them **by
accident** — it had no opinion about other scripts, it simply could not match
them. Opening the rule up to Thai is precisely the moment that accident gets
lost, so Latin here means ASCII `a-zA-Z` and Thai means two explicit ranges of
letters and marks. Thai digits are excluded on the same grounds: `0-9` are
already there, and `poom๑` beside `poom1` is two accounts a Thai reader says
identically out loud.

### Why `lower(username)` stopped being enough

The unique index used to be `ON users (lower(username))`. Two problems, and only
the second is about Thai.

**SQLite's `lower()` is ASCII-only** in the build better-sqlite3 bundles:

```
sqlite> SELECT lower('POOM'), lower('ÉCOLE'), lower('ก้อง');
        poom            École           ก้อง
```

So "Poom and poom are not two riders" already stopped at A-Z.

**Thai's collision problem is not case at all.** It is two different sequences
of code points that draw the same glyphs, and Unicode normalisation
deliberately does not merge them:

| looks like | one way | the other way | does NFC merge them? |
|---|---|---|---|
| `สำ` | `0E2A 0E33` | `0E2A 0E4D 0E32` | **no** — SARA AM has no canonical decomposition |
| `กี่` | `0E01 0E35 0E48` | `0E01 0E48 0E35` | **no** — SARA II's combining class is 0, so it never reorders |

Either one registers a name that renders exactly like somebody else's.

So the fold runs in JavaScript at the one place a username is written, and its
answer is stored beside the username in `username_key` (migration 0015). It
expands every SARA AM into NIKHAHIT + SARA AA, sorts the marks within each
cluster and puts the spacing vowel last, collapses the pair back, and
lowercases. `usernameTaken` and the unique index both compare that column.

The stored `username` keeps the rider's own spelling and capitalisation, for the
same reason 0007 indexed an expression rather than storing a lowercased value:
what to compare by and what to draw are different questions.

Names that merely *look* similar are left alone on purpose — `กอง` and `ก้อง`
are different words and stay different usernames.

### The migration is backward-compatible, and checkably so

0015 backfills `username_key` with plain SQL `lower(username)`, which is correct
because **every username already in the database is ASCII**: `username` has
exactly one writer — `PATCH /me`, which has gone through `validateUsername`
since 0007 added the column — and that validator has only ever accepted
`[a-zA-Z0-9_.]`. Google sign-in never touches the column. For ASCII the fold's
Thai steps are no-ops and its last step is `toLowerCase()`, so SQL and
JavaScript agree by construction.

That is also why creating the new unique index cannot take the API down the way
0013 warns about: it is over the same values the old index already held unique,
so any pair it would reject was already rejected.

## Place search

`GET /geocode/search?q=<name>&limit=<n>[&near=<lat,lng>]` turns a typed place
name into coordinates, so a rider can set a trip's start, finish or a stop by
name instead of hunting for the spot on the map.

**Two upstream indexes, asked in order.** LocationIQ's `/v1/autocomplete` is
the name index and is asked first, biased towards the rider — "somebody is
typing the name of a place" is the question it was built for. `/v1/search` is
an address geocoder and is the fallback, asked **unbiased**.

That order came out of a real failure. "วัดร่องขุ่น" — the White Temple, one
of the best-known places in Chiang Rai — returned one result from the address
index with no bias, and nothing useful once a bias was added. A bias promotes
everything near the rider that shares a prefix, and in northern Thailand "วัด"
is a prefix several thousand places share, so an exact name can be crowded out
of eight rows by its own neighbours. The fallback therefore drops the bias
rather than repeating it.

The second call only happens when the first found nothing at all, so an
ordinary search still costs one upstream request; a search that matches nothing
costs two, and the cache stores that answer either way. Pressing and holding the map
still does the same job and is unaffected — the search is an addition, not a
replacement, and it is the long press that keeps working for a viewpoint with
no name and on a server with no key set.

```
GET /geocode/search?q=Pai
→ { "query": "Pai",
    "cached": false,
    "results": [
      { "name": "Pai",
        "address": "Pai, Mae Hong Son, Thailand",
        "lat": 19.3583, "lng": 98.4406,
        "kind": "town", "osm_id": "1234567" }
    ] }
```

**The key lives here, not in the app.** LocationIQ authenticates with a single
token on a free tier of 5,000 requests a day for the whole server. A token
shipped inside the APK is one `unzip` away from being read and spent by
somebody else, so the phone asks this server and the server holds the token.

**`near=<lat,lng>` biases results towards the rider**, and never filters by
them. The app sends the phone's own position; the server turns it into a
`viewbox` of `SEARCH_BIAS_KM` (200 km) a side with `bounded=0`, so somewhere
across the country is still found — just ranked below somewhere down the road.
Without it a place name typed in Thai competes with the whole planet on string
relevance alone, and a well-known local landmark can lose to a better match six
thousand kilometres away.

The bias is part of the cache key, because it is part of the answer: two riders
in different provinces asking the same question get different orderings, and
serving one the other's would be the wrong answer served fast. It is rounded to
one decimal place (about 11 km) first, so a group riding together still shares
one entry and one upstream request. A caller that sends no `near`, or an
unusable one, searches unbiased and is never refused — a missing hint is not an
error.

**Three things defend that quota**, and each covers a case the others do not:

- the app waits for typing to stop before it asks (450 ms), so a nine-letter
  place name costs one request rather than nine;
- the server caches answers for ten minutes, so ten riders typing the same
  name cost one request rather than ten;
- the route is behind `requireAuth` and rate-limited to 12 searches a minute
  **per rider** — keyed on the rider rather than the IP, because a group
  riding together is usually behind one carrier NAT.

**Without `LOCATIONIQ_API_KEY` set** the route answers `503` with
`{"error": "Place search is not configured on this server."}` and the app
shows that sentence under the search box. Nothing else on the server is
affected. Filling the key in later needs no migration and no new build of the
app: put it in `.env` on the VPS and `npm run pm2:restart`.

## Editing a planned route

`PATCH /trips/:id/waypoints/:wpId` takes `name` and `order_index`. Until it
existed a route could only be appended to, so the order stops were arranged in
lived on the phone: a rider who added stops, left the trip and came back found
the route list empty, because there was nothing to load them into.

**Owner-only, and stricter than the `DELETE` beside it.** Re-ordering one stop
renumbers the ones around it, so a member dragging their own stop up two places
rewrites two stops that are not theirs. An author-only rule would let the first
write succeed and the second come back `403`, leaving the route half renumbered
with no way for the rider to tell. The road between the ends is the owner's,
the same way `PATCH /trips/:id` already was; adding a stop and removing your
own are what a member gets, both unchanged.

`lat`/`lng` and `type` are not patchable — a stop that moved somewhere else is
a different stop, and a planned waypoint that became live would silently leave
the route it was ordered inside. Both are a delete and a create.

`order_index` is **not** unique. A client rewriting a route sends one PATCH per
moved stop, and every intermediate state would collide with itself under a
unique index. It is a sort key; ties fall back to `id`, exactly as the `GET`
says. A body with neither field is `400` — a PATCH that changes nothing is a
request somebody meant to fill in.

## Shared places — the riders' own list

`GET /geocode/search` cannot find what OpenStreetMap does not contain, and the
gap is not a tuning problem. "ปตท สวนดอก" is a petrol station every rider in
Chiang Mai can name; in OSM the stations around Suan Dok are tagged `PTT` with
no mention of Suan Dok in their name or their address, and "สวนดอก" is not an
administrative area there at all. Bounded to Chiang Mai, that query has **zero**
candidates. Unbounded it matches สวนดอกไม้ in Saraburi, five hundred kilometres
away. There is no query that finds it, because the row is not there.

So the riders put it there. One name and one point, typed by whoever knows the
place, found from then on by everybody on the server.

```
POST   /places           { "name": "ปตท สวนดอก", "lat": 18.789, "lng": 98.971 }
GET    /places?q=&near=&limit=
DELETE /places/:id
```

All three are behind `requireAuth`. A place comes back as:

```json
{ "id": 12, "name": "ปตท สวนดอก", "lat": 18.789, "lng": 98.971,
  "created_by": 7, "created_by_name": "anne",
  "created_at": "2026-08-20T07:49:12.345Z" }
```

**The list is shared, not per-rider.** This installation is a group of friends
who ride together — tens of people, not thousands of strangers. A place worth
naming is worth naming once: a per-rider table would have twenty people each
typing the same petrol station and none of them finding the other nineteen. The
trust that makes that safe is the same trust that already lets any member drop a
waypoint on somebody else's trip. `created_by` is for attribution and for the
delete rule, never for visibility.

**Who may delete**: whoever added it, or a super user. Not "any member", even
though any member can read every row — a waypoint belongs to one ride and
disappears with it, and a shared place is something the whole group is still
using. Somebody removing another rider's petrol station for fun is a small act
with no undo. A refusal is `403` and not `404`: the row exists and the caller
can already see it in their own search results, so pretending it is missing
would be a lie they can immediately disprove.

**A deleted account does not take its places with it.** `created_by` is
`ON DELETE SET NULL`, so the rows stay and only the claim on them goes — the
group is still using them and did not ask for them to go. An orphaned row is
then nobody's, which the delete rule reads correctly: no ordinary rider matches
`NULL`, and only a super user can clear it.

**The matching is what the geocoder could not do.** `q` is split on whitespace
and **every** word has to appear in the name, so "ปตท สวนดอก" matches both
`ปตท สวนดอก` and `ปั๊ม ปตท. สาขาสวนดอก`, and does not match `ปตท ช้างเผือก`.
No stemming and no word segmentation: Thai does not put spaces between words, so
substring matching on an un-segmented language is exactly right — "สวนดอก" is
inside "ปั๊มสวนดอก" whether or not anything knows where the word ended.

Results are ranked in three bands — the name *is* the query, the name starts
with it, every word is in it somewhere — and only then by distance from `near`,
which is a preference and never a filter. `created_at` and then `id` break the
last ties, so the order is total and a list cannot reshuffle between requests.

The match runs in JavaScript over the whole table rather than as a SQL `LIKE`.
Requiring every word separately is one `LIKE` per word with its own escaping,
and the ranking above cannot be written as an `ORDER BY` at all. On a table this
size — tens of rows per rider per year — the scan is free and the rule is
readable and tested. If it ever stops being free the fix is FTS5 and the ranking
above it does not change.

**Two quotas, doing different jobs.** A rider may add **20 places per rolling
24 hours**, counted from the rows themselves — an in-memory counter is emptied
by every `pm2 restart` and cannot be trusted with a day. On top of that sits a
10-a-minute `express-rate-limit`, keyed on the rider, which is the one a loop
meets before it can make the server count rows. Exceeding the allowance is
`429` and not `403`: it is a rate, not a permission, and the wording says so.

A rolling window rather than a calendar day because a calendar day has to be a
day somewhere — on UTC these riders' quota would reset at seven in the morning,
part way through the ride they are planning — and because it cannot be doubled
by adding twenty at one minute to midnight and twenty more at one minute past.

**This is deliberately not folded into `/geocode/search`.** That route answers
`503` with no API key, `429` when the day's LocationIQ quota is gone and `502`
when LocationIQ is unreachable. This one is a local table that answers on every
one of those days — and the shared list is at its most useful on exactly the day
the geocoder is not working. Merging them server-side would put the local answer
behind the remote one's failures. The app asks both at once and merges what
comes back, keeping the two failure modes apart.

**No API key is involved**, so this feature works on a server that has never had
`LOCATIONIQ_API_KEY` set.

## Private places — one rider's own

`shared_places` above is a list the whole installation reads. This is the
opposite, and it is a **second table** rather than a flag on that one:

```
GET    /me/places
POST   /me/places      { "label": "บ้าน", "name": "คอนโด ดิ แอสตร้า", "lat": …, "lng": … }
DELETE /me/places/:id
```

**Why a table and not a column.** A privacy flag on `shared_places` would make
every read of it one forgotten `WHERE` away from publishing somebody's home
address, and the forgetting would be silent — the query still returns rows, the
screen still looks right, and the only symptom is a stranger seeing where you
live. A query against `personal_places` that forgets its owner is wrong in an
obvious way; a query against `shared_places` is *supposed* to be unscoped. The
rule is legible from the table name at every call site.

**Why `/me` and not `/places/mine`.** There is no id in the path naming whose
places these are. The owner is `req.user.id`, decided by `requireAuth` and by
nothing a caller can send, so there is no parameter to forget to check. Every
statement carries `WHERE user_id = ?` on top of that, and `canAccessPersonalPlace`
is checked before any single row is answered with — three independent guards,
because one is not enough for this.

**No super-user path, deliberately.** A super user administers the shared list;
nobody administers a home address. A privilege that could read one would have
to be re-audited every time this file changed, and the absence is the feature.

**No search endpoint, and no join.** A rider has a handful of these and they all
arrive at once, so matching against a typed query happens on the phone — the
query never travels and there is no filter to get wrong. `shared_places` joins
`users` to show who added a place; this never joins anything, because a join is
how a scoped query stops being scoped while still reading correctly.

**404, not 403, on somebody else's row** — the opposite of the shared list, on
purpose. A 403 confirms the row exists and belongs to somebody. On a shared
list that is already public knowledge; here it is exactly what must not leak, so
"not there" is the only honest answer. `DELETE /me/places/999999` and a delete
of a real row belonging to another rider are byte-identical replies.

`user_id` is `NOT NULL` and `ON DELETE CASCADE` — the reverse of
`shared_places.created_by`, and for the reverse reason. Nobody else is using
this row, and an account being deleted is exactly when a note about where
somebody lives should stop existing. A row with no owner could only ever leak.

At most 20 per rider — not a spam guard (nobody else can see them) but a guard
on the screen, which draws them as a row of chips. Over that is `409`, not
`429`: waiting changes nothing, something has to come off the list first.

Two tests guard the rule against future edits rather than against the current
code: one sweeps `src/` and fails any statement naming the table without
`user_id = ?` (an `INSERT` scopes by column instead), and one fails any join to
it. Removing the `WHERE` from the list query fails eight tests.

## Road routing

`GET /directions?from=<lat,lng>&to=<lat,lng>[&waypoints=<lat,lng>;<lat,lng>]`
answers with the road a trip actually takes: the line to draw on the map, how
long it is, and roughly how long it takes. It is what turns the map's progress
bar from "how much of the crow-flies gap have I closed" into "how much of the
road is left", which on a mountain road are very different numbers.

```
GET /directions?from=18.78830,98.98530&to=19.35830,98.44060&waypoints=18.28880,99.49080
→ { "from": { "lat": 18.7883, "lng": 98.9853 },
    "to":   { "lat": 19.3583, "lng": 98.4406 },
    "waypoints": [ { "lat": 18.2888, "lng": 99.4908 } ],
    "cached": false,
    "route": {
      "points": [ { "lat": 18.7883, "lng": 98.9853 }, ... ],
      "distance_km": 134.5,
      "duration_min": 210
    } }
```

**`waypoints` is one request, not several joined together.** The upstream takes
a list of coordinates and returns a single geometry through all of them, so a
trip with three stops costs one call against the quota, one distance that is
really the distance, and one line with no seams where the stops are. Joining
per-leg routes here would have cost four calls and produced a length that was
the sum of four roads rather than the road.

The order is the caller's and is never re-sorted — that is what `order_index`
on a planned waypoint means. "Optimise my stops" is a different feature and a
different upstream parameter.

At most `MAX_WAYPOINTS` (23) stops, which with the two ends is the 25
coordinates an OSRM-family routing call takes. More than that is a 400 rather
than a silent truncation: a route drawn through the first twenty-three of
thirty stops goes somewhere the trip does not, and it would look right.

**Every stop is in the cache key**, at the same four-decimal rounding the two
ends use. Two trips can share a start and a finish and be completely different
rides — one straight up the highway, one round three viewpoints — and keyed on
the ends alone the second would be served the first's line for six hours, with
nothing on screen to say so. Order is part of the key too, because the same
three stops taken the other way round is a different road.

Same key, same reasoning, same defences as place search — with the numbers
turned up, because the traffic pattern is different:

- **The app asks once per route**, not once per poll. The map polls positions
  every twenty seconds; a route is a function of the coordinates the trip owner
  set by hand — its two ends and its planned stops — so it is re-fetched when
  one of them moves or a stop is added, and at no other time. A failed attempt waits five minutes before another is tried,
  so a server with no key does not get three requests a minute for ever.
  (`android/.../map/RouteRequests.kt`, and the test beside it.)
- **The server caches a route for six hours**, against place search's ten
  minutes. Every rider on a trip asks the same question — the road from this
  trip's start to its finish — so eight riders cost one upstream request, and
  a road between two towns does not change over an afternoon.
- **Rate-limited to 6 a minute per rider**, tighter than search's twelve,
  because nobody types a route.

**`overview=full` is what gets asked for**, and the geometry is then simplified
here, to at most 600 points, before it leaves the server.

That was `overview=simplified` first, on the reasoning that a line being drawn
does not need a navigator's vertex count. The reasoning was right and the
parameter was wrong: `simplified` is an *overview*-grade geometry. Measured
against a real router on Chiang Mai → Pai, 130 km of mountain road:

| `overview` | points | drawn length ÷ straight-line length |
|---|---|---|
| `simplified` | 45 | 1.297 |
| `full` | 3,705 | 1.517 |
| `full`, then simplified here | 397 | **1.478** |

Forty-five points over 130 km is a three-kilometre straight chord per segment.
On a phone that is not a road — it is very nearly the straight line the feature
exists to replace, which is exactly what it looked like on a real ride.

The thinning is Douglas–Peucker with an adaptive tolerance: it starts at about
two metres and doubles until the line fits the cap, so a ride across town keeps
every vertex the router gave it and only a long route is thinned at all. Even
sampling (`capPoints`) is kept underneath as a backstop for a pathological
geometry, because it cuts a hairpin as readily as a straight.

The distance is the routing service's own figure and is never re-measured from
the thinned points — that would be quietly short.

**"No road between these two points"** — a finish on an island, a coordinate in
the sea — is `200` with `"route": null`, not an error, and is cached like any
other answer.

**Without `LOCATIONIQ_API_KEY` set** the route answers `503` and the app falls
back to the straight line it drew before road routing existed, captioned
"direct" exactly as it always was. Nothing is shown to the rider: they cannot
act on a server's API key, and the map still works.

Turn-by-turn navigation and alternative routes are deliberately not here. This
phase draws one line and measures against it.
