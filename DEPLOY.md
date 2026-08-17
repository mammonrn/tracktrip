# Deploying trip-tracker (Ubuntu 24.04 VPS with nginx + certbot)

Step-by-step, command-by-command instructions for deploying trip-tracker
for the first time on a VPS that **already has**:

- Node.js and PM2 installed and managing at least one other app.
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
| `PORT` | A port nothing else on the VPS is using. Default `4100` is fine unless something's already bound to it — check with `sudo ss -tlnp`. |
| `JWT_SECRET` | The value from `openssl rand -hex 32` above. |
| `GOOGLE_CLIENT_ID` | Your Google OAuth client ID(s), comma-separated if you have more than one (e.g. web + Android). |
| `DB_PATH` | Leave as `./data/trip-tracker.db` unless you have a reason to change it. |
| `HISTORY_RETENTION_DAYS` | Leave as `30` unless told otherwise. |

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
`proxy_pass` line so it matches before continuing:

```bash
sudo nano /etc/nginx/sites-enabled/api.ptrip.app.conf
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
