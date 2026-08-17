# trip-tracker

Backend for an Android app that shares rider positions among members of a
motorcycle trip. Node.js + Express + better-sqlite3 + `ws`, run under PM2.

This is a monorepo: the backend lives at the root, and the Kotlin/Compose
Android client lives in [`android/`](./android/README.md).

Google Sign-In based auth and a basic profile API are implemented. The live
WebSocket position feed is still a stub.

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
    serializeUser.js   # shapes a user row for API responses
  routes/
    index.js          # health check
    auth.js            # POST /auth/google, /auth/refresh, /auth/logout
    me.js               # GET/PATCH /me
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

- `GET /me` — current user.
- `PATCH /me` — body `{ display_name }` (1–40 characters after trimming).
  Photo uploads aren't supported yet; `photo_url` can't be changed via the API.

Access tokens are JWTs valid for 1 hour. Refresh tokens are opaque 32-byte
random values valid for 60 days; only their SHA-256 hash is stored in the
`refresh_tokens` table.

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
