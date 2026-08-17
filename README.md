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
  users/
    levels.js        # the rider level table and progress towards the next one
  routes/
    index.js          # health check
    auth.js            # POST /auth/google, /auth/refresh, /auth/logout
    me.js               # GET/PATCH /me
    trips.js             # /trips, /trips/:id/invites, /trips/:id/end
    invites.js           # /invites, /invites/:id/accept
    waypoints.js         # /trips/:id/waypoints
    positions.js          # /trips/:id/positions
    sharing.js             # /trips/:id/share/start, /share/stop
  ws/                # WebSocket server (stub)
scripts/
  cleanup-history.js  # CLI wrapper for npm run cleanup
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

- `GET /me` — current user, including their lifetime `total_km`.
- `PATCH /me` — body `{ display_name }` (1–40 characters after trimming).
  Photo uploads aren't supported yet; `photo_url` can't be changed via the API.
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

- `POST /trips` — body `{ name }` (1–60 characters after trimming). Returns
  `201` with the trip. The caller becomes the owner and is written into
  `trip_members` with `role = 'owner'` in the same transaction.
- `GET /trips` — the caller's trips (owned **and** joined), newest first, each
  with the caller's own `role`.
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

Once a trip has ended it is **read-only**: members can still read it, but
every write to it — positions and live waypoint drops included — returns
`409 {"error": "trip has ended"}`, and no new invite can be sent or accepted.

Ending a trip also **stops location sharing for everyone in the group**, in
the same transaction: every sharing session on that trip is cleared. Nobody
carries on past the end of a trip; a rider who wants to keep going starts a
trip of their own. See [Sharing sessions](#sharing-sessions).

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
  `status`/`accepted_at`/`accepted_by` are written in one transaction.

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

Clients poll `GET`; there is no push yet.

- `POST /trips/:id/positions` — the caller reports their own position. Returns
  `200` with the stored position.

  | Field | Rule |
  |---|---|
  | `lat` | number, −90 to 90 |
  | `lng` | number, −180 to 180 |
  | `timestamp` | optional ISO 8601 string — when the *device* took the fix, not when the request arrived. Defaults to now, and is normalized to UTC. |
  | `accuracy` | optional number, 0 or greater (metres) |
  | `speed` | optional number, 0 or greater |
  | `heading` | optional number, 0 to 360 |
  | `battery_pct` | optional integer, 0 to 100 |

  `200` rather than `201`: there is one row per rider, so this creates it the
  first time and replaces it after that. The caller can only ever write their
  own position — the row is keyed on the authenticated user, not on anything
  in the body.

  **Each accepted fix also credits distance** towards the rider's lifetime
  `users.total_km` — see [Lifetime distance](#lifetime-distance) below.

  Capped at **10 reports per minute per rider** (`POSITION_RATE_LIMIT` in
  `src/routes/positions.js`), answering `429 {"error": "too many position
  updates"}` above that. The app reports roughly every ten minutes, so this
  is a safety net against a client stuck in a retry loop, not a quota — no
  real rider will reach it. It is keyed on the **rider**, not the IP, because
  a group riding together is usually behind one carrier NAT and would
  otherwise throttle each other. `GET` is not capped: the limit is derived
  from how often a rider reports, and the map screen refreshes on its own
  cadence.

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
      "user_id": 2, "display_name": "Member", "photo_url": "member.jpg",
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
| `HISTORY_RETENTION_DAYS`  | Days of `position_history` to keep for ended trips before cleanup deletes it. | `30`                     |

Never commit a real `.env` file — it's excluded via `.gitignore`.
