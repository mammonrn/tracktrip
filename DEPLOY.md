# Deploying trip-tracker (first time, Ubuntu 24.04 VPS)

This is a step-by-step guide for deploying trip-tracker for the very first
time, written for someone without deep Linux experience. It assumes:

- You already have SSH access to the VPS.
- The VPS already runs at least one other Node.js app under PM2. **We will
  not touch that app** — trip-tracker gets its own PM2 process name and its
  own port, so it can't collide with it.
- You have a domain (`api.ptrip.app`) with its DNS **A record** already
  pointed at this VPS's IP address. (Check with `dig api.ptrip.app +short`
  — it should print the VPS's IP.)

Run every command below over SSH, logged in as the **same user** that
already manages your other PM2 app (not necessarily `root`). If you're not
sure who that is, run `whoami` after SSH-ing in, and check `pm2 list` shows
your other app.

---

## 1. Check prerequisites

```bash
node -v      # should print v18 or higher
npm -v
pm2 -v       # if this fails, PM2 isn't installed — stop and ask first
```

Check which ports are already in use, so you can pick a free one for
trip-tracker later:

```bash
sudo ss -tlnp
```

Look at the list of `LISTEN` ports. Anything already listed is taken —
you'll need a different `PORT` value in step 3.

## 2. Clone the repository

Pick a folder to hold the app. `/opt` is a common, sensible choice:

```bash
sudo mkdir -p /opt/trip-tracker
sudo chown "$USER":"$USER" /opt/trip-tracker
git clone https://github.com/mammonrn/tracktrip.git /opt/trip-tracker
cd /opt/trip-tracker
```

## 3. Install dependencies

`npm ci` installs exactly the versions recorded in `package-lock.json`
(safer for production than `npm install`):

```bash
npm ci --omit=dev
```

## 4. Create and fill in `.env`

```bash
cp .env.example .env
```

Now generate a secure `JWT_SECRET` — **do this with a single command, don't
type one in by hand**:

```bash
openssl rand -hex 32
```

This prints a random 64-character hex string. Copy it.

> **Never reuse a secret from another project or app.** `JWT_SECRET` is
> what lets this server trust that a login token is genuine. If the same
> secret is also used somewhere else, anyone who can forge/leak a token
> for that other system can forge a valid trip-tracker login too — and if
> trip-tracker's secret ever leaks, that other system is compromised as
> well. Generate a fresh, unique secret for trip-tracker and never paste
> it anywhere else.

Open `.env` in an editor (`nano .env` is the simplest if you're not
comfortable with `vim`) and fill in every value:

```bash
nano .env
```

| Variable | What to put |
|---|---|
| `PORT` | A port number nothing else on the VPS is using (checked in step 1). Default `4100` is fine if free. |
| `JWT_SECRET` | The random value from `openssl rand -hex 32` above. |
| `GOOGLE_CLIENT_ID` | Your Google OAuth client ID(s), comma-separated if you have more than one (e.g. web + Android). |
| `DB_PATH` | Leave as `./data/trip-tracker.db` unless you have a reason to change it. |
| `HISTORY_RETENTION_DAYS` | Leave as `30` unless told otherwise. |

Save and exit (`nano`: `Ctrl+O`, `Enter`, then `Ctrl+X`).

`.env` contains secrets — it's already excluded from git via
`.gitignore`, so it's safe to edit in place; it will never be committed.

## 5. Run database migrations

This creates `data/trip-tracker.db` and sets up all the tables:

```bash
npm run migrate
```

You should see output listing the migrations that were applied. Running
this command again later (e.g. after pulling a new version with new
migrations) is always safe — already-applied migrations are skipped.

## 6. Install Caddy (reverse proxy + automatic HTTPS)

Skip this step if Caddy is already installed and running on this VPS
(check with `caddy version`) — just merge the site block from this
project's `Caddyfile` into your existing `/etc/caddy/Caddyfile` instead of
overwriting it, then jump to step 7.

Otherwise, install Caddy from its official APT repository:

```bash
sudo apt update
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
```

Copy this project's `Caddyfile` into place:

```bash
sudo cp /opt/trip-tracker/Caddyfile /etc/caddy/Caddyfile
```

If you changed `PORT` away from the default `4100` in step 4, edit the
port Caddy proxies to so it matches:

```bash
sudo nano /etc/caddy/Caddyfile
```

## 7. Open ports 80 and 443

Caddy needs these open to get and renew the TLS certificate automatically.
If you use `ufw` (Ubuntu's firewall):

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw status
```

If your VPS provider also has a separate cloud firewall/security-group
setting (DigitalOcean, AWS, etc.), open 80 and 443 there too — `ufw`
alone isn't enough if that's in front of it.

## 8. Start (or reload) Caddy

```bash
sudo systemctl enable --now caddy    # first install only
sudo systemctl reload caddy          # if Caddy was already running
```

Check it's healthy:

```bash
sudo systemctl status caddy
```

## 9. Start trip-tracker under PM2

```bash
cd /opt/trip-tracker
pm2 start ecosystem.config.cjs
```

Confirm it's running alongside your other app, both visible:

```bash
pm2 list
```

You should see two apps: your existing one, and `trip-tracker`. Save the
process list so PM2 restores both apps automatically on server reboot:

```bash
pm2 save
```

If `pm2 startup` was never run on this VPS before (i.e. PM2 doesn't
survive a reboot at all yet), set that up once — it prints a command you
need to copy-paste and run:

```bash
pm2 startup
```

## 10. Verify it's actually working

```bash
curl -s https://api.ptrip.app/health
```

Expected output: `{"status":"ok"}`. If you get a connection error, check
`pm2 logs trip-tracker` and `sudo journalctl -u caddy -n 50 --no-pager`.

---

## Rolling back if something breaks

Migrations in this project are purely additive (new tables/columns only,
never drops or destructive rewrites — see `README.md`), so rolling back
the **code** to a previous commit is safe without needing to touch or
"undo" the database.

1. Find the last known-good commit:
   ```bash
   cd /opt/trip-tracker
   git log --oneline -10
   ```
2. Check out that commit (replace `<commit-sha>`):
   ```bash
   git checkout <commit-sha>
   npm ci --omit=dev
   ```
3. Restart the app:
   ```bash
   pm2 restart trip-tracker
   ```
4. Confirm it's healthy again:
   ```bash
   curl -s https://api.ptrip.app/health
   ```
5. Once you've confirmed the rollback fixed things and you're ready to
   move forward again, return to the latest code:
   ```bash
   git checkout main
   git pull origin main
   npm ci --omit=dev
   npm run migrate
   pm2 restart trip-tracker
   ```

If trip-tracker is badly broken and you just need it to stop affecting
the VPS while you investigate, you can stop it without affecting your
other PM2 app:

```bash
pm2 stop trip-tracker
```

Your other app, and `pm2 save`'d state, are unaffected either way —
`pm2 stop`/`restart` only ever target the app by name.
