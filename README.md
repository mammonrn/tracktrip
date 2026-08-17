# trip-tracker

Backend for an Android app that shares rider positions among members of a
motorcycle trip. Node.js + Express + better-sqlite3 + `ws`, run under PM2.

This is the **scaffold**: project layout, DB schema, and migrations only.
Auth and the live WebSocket position feed are not implemented yet.

## Stack

- Node.js (>=18) + Express
- SQLite via `better-sqlite3`
- `ws` for the WebSocket server (stubbed for now)
- PM2 for process management in production

## Project layout

```
src/
  config.js        # reads .env
  db/
    index.js       # better-sqlite3 connection
    migrate.js      # migration runner (npm run migrate)
    migrations/     # numbered .sql migration files
    cleanup.js       # position_history retention cleanup logic
  routes/           # HTTP routes (health check only so far)
  ws/               # WebSocket server (stub)
scripts/
  cleanup-history.js  # CLI wrapper for npm run cleanup
test/                 # node:test unit tests
data/                 # SQLite DB file lives here (gitignored)
```

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

## Deploying (Ubuntu 24.04 VPS, alongside other Node apps)

This app is designed to run next to other PM2-managed Node apps on the
same box without colliding:

1. Pick a `PORT` in `.env` that isn't already in use by another app on
   the VPS.
2. Clone the repo and set up `.env`:
   ```bash
   git clone <repo-url> /opt/trip-tracker
   cd /opt/trip-tracker
   npm ci --omit=dev
   cp .env.example .env   # edit with production values
   npm run migrate
   ```
3. Start it under PM2 using the app-specific name declared in
   `ecosystem.config.cjs` (`trip-tracker`), so `pm2 restart`/`pm2 stop`
   only ever target this app, not others already running:
   ```bash
   pm2 start ecosystem.config.cjs
   pm2 save
   ```
4. Put a reverse proxy (nginx, etc.) in front if the app needs to be
   reachable on 80/443, forwarding to the `PORT` from `.env`.
5. Schedule `npm run cleanup -- --confirm` periodically (e.g. a daily
   cron entry or a PM2 cron restart job) to enforce
   `HISTORY_RETENTION_DAYS`.

## Environment variables

All variables are documented in `.env.example`:

| Variable                  | Description                                                                 | Default                    |
|----------------------------|-------------------------------------------------------------------------------|-----------------------------|
| `PORT`                    | Port the Express + WebSocket server listens on. Choose one free on the VPS. | `4100`                     |
| `JWT_SECRET`              | Secret used to sign/verify JWTs issued to the Android app.                 | *(required, no default)*   |
| `GOOGLE_CLIENT_ID`        | OAuth client ID used to verify Google Sign-In tokens from the app.         | *(required, no default)*   |
| `DB_PATH`                 | Path to the SQLite database file.                                          | `./data/trip-tracker.db`   |
| `HISTORY_RETENTION_DAYS`  | Days of `position_history` to keep for ended trips before cleanup deletes it. | `30`                     |

Never commit a real `.env` file — it's excluded via `.gitignore`.
