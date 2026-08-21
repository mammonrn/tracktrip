# Deploying trip-tracker (Ubuntu 24.04 VPS with nginx + certbot)

Step-by-step, command-by-command instructions for deploying trip-tracker
for the first time on a VPS that **already has**:

- Node.js 20 or newer, and PM2, installed and managing at least one
  other app. (Node 20 is enough — dependencies are held to versions that
  support it, so there's no need to upgrade Node for this app. Check with
  `node -v`.)
- nginx installed and already serving two sites from
  `/etc/nginx/sites-enabled/`: `analytics` and `default`.
- certbot installed with the nginx plugin available (`certbot --nginx`).

**We will not modify `analytics` or `default`, or any other existing
site.** trip-tracker gets its own nginx config file and its own PM2
process name (`tracktrip-api`), so it can't collide with what's already
running.

This assumes you have SSH access, and that DNS for `api.ptrip.app` (an
**A record**) already points at this VPS's IP address — check with
`dig api.ptrip.app +short` before starting.

Run every command below over SSH as `root` (or with `sudo`, if that's how
this VPS is set up).

---

## 1. Clone the repository

```bash
git clone https://github.com/mammonrn/tracktrip.git /root/tracktrip
cd /root/tracktrip
```

## 2. Install dependencies

`npm ci` installs exactly the versions recorded in `package-lock.json`
(safer for production than `npm install`):

```bash
npm ci --omit=dev
```

This should complete with no `EBADENGINE` warnings. The project sets
`engine-strict=true` in `.npmrc`, so if a dependency ever requires a
newer Node than this box runs, `npm ci` **fails here** rather than
warning and leaving you to discover it at runtime. If that happens, pin
the offending dependency back to a version that supports your Node
release — don't upgrade Node on this VPS, since other apps depend on
the installed version and `better-sqlite3` is a native module that
would need rebuilding.

## 3. Create and fill in `.env`

```bash
cp .env.example .env
```

Generate a secure `JWT_SECRET` with a single command:

```bash
openssl rand -hex 32
```

This prints a random 64-character hex string. Copy it — you'll paste it
into `.env` in a moment.

> **Never reuse this secret anywhere else — including for other apps
> already running on this same VPS.** `JWT_SECRET` is what lets
> trip-tracker trust that a login token is genuine. If it's shared with
> another app on this box, a bug or leak in that other app's secret
> handling can be used to forge valid trip-tracker logins, and vice
> versa. Generate a secret that belongs to trip-tracker alone.

Edit `.env`:

```bash
nano .env
```

| Variable | What to put |
|---|---|
| `HOST` | Leave as `127.0.0.1`. This makes the app reachable only via nginx on this same machine — not directly from the internet, which would bypass nginx and TLS entirely. |
| `PORT` | A port nothing else on the VPS is using. Default `4100` is fine unless something's already bound to it — check with `sudo ss -tlnp`. |
| `JWT_SECRET` | The value from `openssl rand -hex 32` above. |
| `GOOGLE_CLIENT_ID` | Your Google OAuth client ID(s), comma-separated if you have more than one (e.g. web + Android). |
| `DB_PATH` | Leave as `./data/trip-tracker.db` unless you have a reason to change it. |
| `HISTORY_RETENTION_DAYS` | Leave as `30` unless told otherwise. |
| `SUPERUSER_EMAILS` | Comma-separated emails that may see and manage every trip. The list is the authority — adding an address grants the role at the next restart, removing one takes it away. Leave unset to keep the built-in default. |
| `LOCATIONIQ_API_KEY` | The access token from <https://my.locationiq.com/dashboard>, which powers the map's place search. **Leave it empty if you don't have one yet** — the server starts fine, place search answers 503 saying it isn't configured, and everything else works. See [Turning on place search](#turning-on-place-search) for filling it in later. |
| `LOCATIONIQ_COUNTRY_CODES` | Optional. Comma-separated ISO country codes to bias results towards, e.g. `th`. Leave empty to search worldwide. |

Save and exit (`nano`: `Ctrl+O`, `Enter`, then `Ctrl+X`).

`.env` holds secrets — it's already excluded from git via `.gitignore`,
so editing it in place is safe; it will never be committed.

## 4. Run database migrations

```bash
npm run migrate
```

This creates `data/trip-tracker.db` and applies all the schema. Running
it again later (e.g. after pulling a new version with new migrations) is
always safe — already-applied migrations are skipped.

## 5. Start the app under PM2

The process **must** be named `tracktrip-api` — the name is already set
in `ecosystem.config.cjs`, so just run:

```bash
pm2 start ecosystem.config.cjs
```

Confirm it's running alongside whatever else is already under PM2:

```bash
pm2 list
```

You should see `tracktrip-api` plus any pre-existing app(s). Save the
process list so PM2 restores everything automatically on reboot:

```bash
pm2 save
```

(If `pm2 startup` was never run on this VPS before — i.e. PM2 itself
doesn't survive a reboot yet — that's a separate, one-time setup step
unrelated to this app; skip it if PM2 is already configured to survive
reboots.)

## 6. Add the nginx site config

This repo ships a ready-made config at `deploy/nginx-api.ptrip.app.conf`.
Symlink it into `sites-enabled` (symlinking rather than copying means
future `git pull`s automatically update the live config):

```bash
sudo ln -s /root/tracktrip/deploy/nginx-api.ptrip.app.conf /etc/nginx/sites-enabled/api.ptrip.app.conf
```

If you changed `PORT` away from the default `4100` in step 3, edit the
`proxy_pass` line so it matches before continuing (keep the
`127.0.0.1` host as-is — see the note in that file about why it isn't
`localhost`):

```bash
sudo nano /etc/nginx/sites-enabled/api.ptrip.app.conf
```

### Rider avatars — the one path you must edit by hand

The config has a `location /uploads/` block that serves uploaded avatars
straight off disk. It ships with a placeholder path that **will not work
until you change it**:

```nginx
root /home/DEPLOY_USER/tracktrip;
```

There are two occurrences (the outer block and the inner one that matches
the image files). Both must point at the **parent** of the directory the
app writes to, because `root` appends the whole URI — `/uploads/avatars/x.jpg`
is looked up as `<root>/uploads/avatars/x.jpg`.

With the default `UPLOADS_DIR` and a deploy under `/root/tracktrip`, both
lines become:

```nginx
root /root/tracktrip;
```

Then check that nginx's worker can actually read them. It runs as
`www-data` on Ubuntu, so it needs execute permission on every directory
down the path:

```bash
sudo -u www-data stat /root/tracktrip/uploads/avatars
```

If that says "Permission denied", the usual cause is a `700` home
directory. Either open the path up (`chmod o+x /root /root/tracktrip`) or
set `UPLOADS_DIR` in `.env` to somewhere world-traversable such as
`/var/www/tracktrip/uploads`, create it with `mkdir -p`, `chown` it to the
user PM2 runs the app as, and point both `root` lines at
`/var/www/tracktrip`.

Until this is done, uploading an avatar succeeds and the app stores it —
the image just 404s when anything tries to display it. Nothing else
breaks, so this is not a step that has to happen at the same moment as the
code deploy.

After editing, verify with a real file rather than by eye:

```bash
curl -sI https://api.ptrip.app/uploads/avatars/<some-uploaded-file>.jpg | head -1
```

**Always test the nginx config before reloading — every time, no
exceptions.** A bad config that gets reloaded can take down
`analytics` and `default` along with it:

```bash
sudo nginx -t
```

You should see `syntax is ok` and `test is successful`. Only if both of
those appear:

```bash
sudo systemctl reload nginx
```

## 7. Get a TLS certificate

```bash
sudo certbot --nginx -d api.ptrip.app
```

Certbot will edit `/etc/nginx/sites-enabled/api.ptrip.app.conf` in place
to add the port 443 / TLS server block and an HTTP → HTTPS redirect —
you don't need to write that part by hand. Follow its prompts (email
address, agree to terms, etc.).

## 8. Verify it's actually working

```bash
curl -s https://api.ptrip.app/health
```

Expected output: `{"status":"ok"}`.

If that fails, check, in this order:

```bash
pm2 logs tracktrip-api --lines 50
sudo nginx -t
sudo journalctl -u nginx -n 50 --no-pager
```

---

## Deploying an update

```bash
cd /root/tracktrip
git pull origin main
npm ci --omit=dev
npm run migrate
pm2 restart tracktrip-api
```

**`npm run migrate` is not optional and not only for the first install.** Most
updates to this server have been code-only, so `pull` + `restart` was enough and
the habit stuck. Any release that adds a table or a column needs the migrate
step, and skipping it does not fail loudly — the app starts fine and then throws
`SQLITE_ERROR: no such table` on the one route that needed the new schema, which
reads on the phone as that one feature being broken.

Running it when there is nothing to apply is free and safe: applied migrations
are recorded in `schema_migrations` and skipped.

**Which releases need it**, so far:

| Migration | Adds | Feature it is behind |
|---|---|---|
| `0009_trip_endpoints.sql` | `trips.origin_*`, `trips.destination_*` | Setting a route up |
| `0010_user_roles.sql` | `users.role` | Super users |
| `0011_shared_places.sql` | `shared_places` | Shared places (`/places`) |
| `0012_personal_places.sql` | `personal_places` | Private places (`/me/places`) |

To check what a database has already had applied:

```bash
sqlite3 /root/tracktrip/data/trip-tracker.db \
  "SELECT filename, applied_at FROM schema_migrations ORDER BY filename;"
```

And to confirm the newest one landed:

```bash
sqlite3 /root/tracktrip/data/trip-tracker.db ".schema personal_places"
```

**Back up first if the release changes schema.** It is the only way back from a
migration that goes wrong:

```bash
sqlite3 /root/tracktrip/data/trip-tracker.db \
  ".backup '/root/tracktrip/data/trip-tracker.db.$(date +%F-%H%M).bak'"
```

`.backup` rather than `cp`, and the difference is not pedantry: the app is
still running while you type this. A plain copy takes the bytes as they are
part way through somebody's write, and in WAL mode it copies only the main
file — leaving behind the `-wal` alongside it, which is where the most recent
commits still live. `.backup` goes through SQLite, takes the locks, and writes
one file that opens cleanly.

Migrations run inside a transaction each, so a failing one leaves the database
as it was and stops the run — but a *successful* migration is not undone by
`git checkout` of the previous release, which is what the backup is for.

---

## Turning on place search

Place search — typing "Pai" instead of hunting for it on the map — runs on
LocationIQ, and needs an access token this server holds. Until it has one,
the search answers `503 Place search is not configured on this server.` and
riders place points by pressing and holding the map, which is what they did
before and still works either way.

**The key goes in `.env` on the VPS, and nowhere else.** Not in the Android
app, not in a Gradle property, not in a GitHub secret. A key inside an APK is
readable by anyone who downloads it, and this one meters a free tier of 5,000
requests a day shared by every rider on this server.

1. Sign in at <https://my.locationiq.com/dashboard> and copy the access token.
2. On the VPS:

   ```bash
   cd /root/tracktrip
   nano .env
   ```

   Set the line to your token, with no quotes and no spaces around the `=`:

   ```
   LOCATIONIQ_API_KEY=pk.0123456789abcdef0123456789abcdef
   ```

   Optionally bias results towards Thailand:

   ```
   LOCATIONIQ_COUNTRY_CODES=th
   ```

3. Restart, so the process picks up the new environment:

   ```bash
   npm run pm2:restart
   ```

4. Check it from the VPS itself. The route needs a signed-in rider, so the
   quickest proof without a token is that it now asks for one rather than
   reporting itself unconfigured:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' \
     'http://127.0.0.1:4100/geocode/search?q=Pai'
   ```

   `401` means the key is loaded and the route is live (it is asking you to
   sign in). Confirm end to end from the app: open a trip's map, tap the
   magnifier in the header, and type a place name.

No migration, no rebuild of the app, and no downtime beyond the restart —
the phone has always been asking this server rather than LocationIQ, so the
same APK starts working the moment the key is there.

### Reading what the search says when it fails

Each message on the phone names one cause, and each cause has one fix. They
are worded apart deliberately: the first time this feature was tested, every
failure looked the same on screen and the search was blamed for a backend
that had never been deployed.

| On the phone | What it means | Fix |
|---|---|---|
| "…isn't on this server yet — its backend is older than this app" | The server has no `/geocode/search` route. `curl` it and you get a 404 HTML page. | Deploy the backend: `git pull` on the VPS, then `npm run pm2:restart`. **Not** a key problem. |
| "…isn't switched on for this server yet (no API key)" | Route is there; `LOCATIONIQ_API_KEY` is empty. | The steps above. |
| "Too many searches just now" | This rider passed 12 searches a minute, or LocationIQ's daily quota is spent. | Wait. If it persists, the 5,000/day free tier is exhausted. |
| "The place search service didn't answer" | This server could not reach LocationIQ, or LocationIQ errored. | Check the VPS's outbound network; see the log line below. |
| "No place by that name." | It worked. LocationIQ genuinely has no match. | Try a fuller name — OSM stores malls as e.g. "เซ็นทรัลพลาซา เชียงราย", not "เซ็นทรัลเชียงราย". |

### Did the server actually call LocationIQ?

Every search writes one line, so this is answerable without guessing:

```bash
pm2 logs tracktrip-api --lines 50 | grep geocode
```

```
geocode: upstream q="Pai" 3 result(s)        ← called LocationIQ, got 3
geocode: cache q="Pai" 3 result(s)           ← answered from the 10-minute cache
geocode: unconfigured q="Pai" LOCATIONIQ_API_KEY is not set
geocode: failed q="Pai" 429 Place search is busy. Try again in a moment.
```

**No `geocode:` lines at all** while the phone is showing an error means the
request never reached the route — which is the "backend is older than the
app" case in the table above. The key is never written to the log.

---

## Nightly backups to Google Drive

[`scripts/backup-to-drive.sh`](scripts/backup-to-drive.sh) puts one zip on
Google Drive every night containing the three things this server holds that
nothing else has a copy of:

| | what | why it is in there |
|---|---|---|
| `trip-tracker.db` | the database, via `sqlite3 .backup` | every trip, rider, position and place |
| `uploads/` | the avatars directory, copied whole | files on disk; not in the database and not in git |
| `code.tar` + `code-commit.txt` | `git archive HEAD`, and which commit it was | so a restore can put the code and the data back together |

`git archive` rather than the whole `.git`: the history is on GitHub already,
and copying it nightly would make every archive mostly the same bytes. What is
worth keeping beside a database is the one commit that database belongs to.

### Before it will run

**rclone must already be authorised**, with a remote called `gdrive`. That is
interactive — it opens a browser and asks Google — so the script does not
attempt it:

```bash
rclone config          # n) new remote, name it: gdrive, type: drive
rclone lsd gdrive:     # should list your Drive folders
```

Also needed: `sqlite3`, `zip` and `git`. The script checks for all four on
startup and stops with the name of the one that is missing.

### Installing the cron entry

Not installed for you — add it with `crontab -e`, **as the deploy user** (it
needs that user's rclone config and read access to the database):

```cron
# Nightly backup to Google Drive at 03:00 Thai time.
# The server runs UTC and ICT is UTC+7, so 03:00 ICT is 20:00 the day before.
0 20 * * * /root/tracktrip/scripts/backup-to-drive.sh
```

Check the server's clock before trusting that sum — `timedatectl` will say. If
it is set to `Asia/Bangkok` rather than UTC, use `0 3 * * *` instead.

### Reading the log

Every line is timestamped, in `/var/log/tracktrip-backup.log` — or
`~/tracktrip-backup.log` if the deploy user cannot write to `/var/log`, which
the script says on stderr the first time it happens.

```
2026-08-21 03:00:01+0700  ──── backup 20260821-030001 starting ────
2026-08-21 03:00:01+0700  database   ok  (156K)
2026-08-21 03:00:01+0700  uploads    ok  (2 file(s), 16K)
2026-08-21 03:00:02+0700  code       ok  (4fc75fa, 2.9M)
2026-08-21 03:00:02+0700  archive    ok  /tmp/tracktrip-backup-20260821-030001.zip (936K)
2026-08-21 03:00:09+0700  upload     ok  -> gdrive:tracktrip-backups/tracktrip-backup-20260821-030001.zip
2026-08-21 03:00:09+0700  cleanup    ok  removed /tmp/... and /tmp/....zip
2026-08-21 03:00:09+0700  ──── backup 20260821-030001 done ────
```

**A failed upload deletes nothing.** The zip stays in `/tmp` and the log says
how to push it by hand, because at that moment it is the only copy — a script
that tidied up after a failed upload would turn "the backup did not reach
Drive" into "the backup does not exist", quietly, every night, until somebody
needed it.

The database snapshot is read back with `PRAGMA integrity_check` before
anything is uploaded. A file of the right size that SQLite will not open is
the failure this whole thing exists to avoid, and it costs one query to rule
out.

### Restoring

```bash
rclone copy gdrive:tracktrip-backups/tracktrip-backup-20260821-030001.zip /tmp/
cd /tmp && unzip tracktrip-backup-20260821-030001.zip
cd tracktrip-backup-20260821-030001

cat code-commit.txt                      # which commit this data belongs to
sqlite3 trip-tracker.db 'PRAGMA integrity_check;'   # expect: ok
```

Then stop the app, put `trip-tracker.db` back at `DB_PATH`, restore `uploads/`
to `UPLOADS_DIR`, `git checkout` the commit in `code-commit.txt`, and start it
again. Delete any `-wal` and `-shm` files sitting beside the old database
first — they belong to the database you are replacing, not to this one.

---

## Rolling back if something breaks

Migrations in this project are purely additive (new tables/columns only,
never drops or destructive rewrites — see `README.md`), so rolling back
the **code** is safe without needing to touch or "undo" the database.

### If the app itself is broken (bad deploy, crashing, etc.)

```bash
pm2 delete tracktrip-api
```

This stops and removes only trip-tracker's PM2 process — it does not
touch any other app PM2 is managing. To go back to a previous version of
the code and try again:

```bash
cd /root/tracktrip
git log --oneline -10          # find the last known-good commit
git checkout <commit-sha>
npm ci --omit=dev
pm2 start ecosystem.config.cjs
pm2 save
```

### If the nginx config itself is the problem

Remove just trip-tracker's site — this doesn't touch `analytics` or
`default`:

```bash
sudo rm /etc/nginx/sites-enabled/api.ptrip.app.conf
sudo nginx -t
sudo systemctl reload nginx
```

`sudo nginx -t` here is not optional — always confirm the config is
valid **before** reloading, whether you just added a site or just
removed one.

### Getting back to normal once you've fixed the problem

```bash
cd /root/tracktrip
git checkout main
git pull origin main
npm ci --omit=dev
npm run migrate
sudo ln -s /root/tracktrip/deploy/nginx-api.ptrip.app.conf /etc/nginx/sites-enabled/api.ptrip.app.conf
sudo nginx -t
sudo systemctl reload nginx
pm2 start ecosystem.config.cjs
pm2 save
```
