#!/usr/bin/env bash
#
# Nightly backup: the database, the uploaded avatars, and a snapshot of the
# code, in one zip, pushed to Google Drive with rclone.
#
# RUN THIS ON THE VPS, as the deploy user — it needs to read the live database
# and the uploads directory, and it needs that user's rclone config.
#
#   ./scripts/backup-to-drive.sh
#
# Nightly at 03:00 Thai time, from that user's crontab (`crontab -e`).
# The server runs UTC, so 03:00 ICT is 20:00 the day before:
#
#   0 20 * * * /home/DEPLOY_USER/tracktrip/scripts/backup-to-drive.sh
#
# Requires: sqlite3, zip, git, and an rclone remote called "gdrive" that is
# already authorised. Setting that up is interactive (`rclone config`) and is
# deliberately not attempted here — see DEPLOY.md.

set -euo pipefail

# ─── Settings ───────────────────────────────────────────────────────────────

REPO_DIR="${REPO_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
LOG_FILE="${LOG_FILE:-/var/log/tracktrip-backup.log}"
RCLONE_REMOTE="${RCLONE_REMOTE:-gdrive:tracktrip-backups}"

# Both default the way src/config.js defaults them, and both are overridden by
# the same variables the app reads — so a server with a non-standard layout is
# configured once, in .env, rather than twice.
if [ -f "$REPO_DIR/.env" ]; then
  # shellcheck disable=SC1091
  set -a; . "$REPO_DIR/.env"; set +a
fi
DB_PATH="${DB_PATH:-$REPO_DIR/data/trip-tracker.db}"
UPLOADS_DIR="${UPLOADS_DIR:-$HOME/tracktrip/uploads}"

# A relative DB_PATH in .env is relative to the repo, which is where the app
# is started from. Resolve it the same way rather than against cron's $PWD,
# which is the deploy user's home and would silently miss the database.
case "$DB_PATH" in
  /*) ;;
  *) DB_PATH="$REPO_DIR/${DB_PATH#./}" ;;
esac

STAMP="$(date +%Y%m%d-%H%M%S)"
STAGE="/tmp/tracktrip-backup-$STAMP"
ARCHIVE="/tmp/tracktrip-backup-$STAMP.zip"

# ─── Logging ────────────────────────────────────────────────────────────────

# Every line stamped, because the only time anybody reads this file is when
# they are working out which night something stopped happening.
log() {
  printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "$*" >>"$LOG_FILE"
}

# Falls back to the home directory when /var/log is not writable by the deploy
# user, rather than dying on its first line and leaving no trace anywhere. Said
# out loud on stderr so a hand-run shows where the log actually went.
if ! { : >>"$LOG_FILE"; } 2>/dev/null; then
  LOG_FILE="$HOME/tracktrip-backup.log"
  echo "WARN: cannot write the log file; using $LOG_FILE instead" >&2
fi

# Anything that escapes — a missing tool, a failed copy, `set -e` firing — is
# reported with the line that did it. Without this a crashed run is a silence
# in the log, which reads exactly like a run that never started.
fail() {
  log "FAILED at line $1 (exit $2). Left $STAGE and $ARCHIVE in place if they exist."
  exit "$2"
}
trap 'fail "$LINENO" "$?"' ERR

log "──── backup $STAMP starting ────"

for tool in sqlite3 zip git rclone; do
  command -v "$tool" >/dev/null 2>&1 || {
    log "ERROR: $tool is not installed"
    exit 1
  }
done

mkdir -p "$STAGE"

# ─── 1. The database ────────────────────────────────────────────────────────
#
# `.backup`, never `cp`. The app is running while this happens, and a plain
# copy of a live SQLite file is a copy of whatever the bytes were part way
# through somebody's write — plus, in WAL mode, a copy that is missing the
# -wal file entirely and is therefore missing the most recent commits. The
# `.backup` command takes a consistent snapshot through SQLite itself, holding
# the right locks, and writes a single file that opens cleanly.

if [ ! -f "$DB_PATH" ]; then
  log "ERROR: no database at $DB_PATH"
  exit 1
fi

sqlite3 "$DB_PATH" ".backup '$STAGE/trip-tracker.db'"
# Reading the snapshot back is what tells a corrupt backup from a backup. A
# file of the right size that SQLite will not open is the failure mode this
# whole script exists to avoid, and it costs one query to rule out.
sqlite3 "$STAGE/trip-tracker.db" 'PRAGMA integrity_check;' | grep -qx 'ok' || {
  log "ERROR: the database snapshot did not pass integrity_check"
  exit 1
}
log "database   ok  ($(du -h "$STAGE/trip-tracker.db" | cut -f1))"

# ─── 2. The uploaded avatars ────────────────────────────────────────────────
#
# Not in the database and not in git: they are files on disk that nothing else
# has a copy of. A missing uploads directory is normal on a server where
# nobody has uploaded a photo yet, so it is noted rather than fatal.

if [ -d "$UPLOADS_DIR" ]; then
  cp -a "$UPLOADS_DIR" "$STAGE/uploads"
  log "uploads    ok  ($(find "$STAGE/uploads" -type f | wc -l) file(s), $(du -sh "$STAGE/uploads" | cut -f1))"
else
  log "uploads    none at $UPLOADS_DIR — skipped"
fi

# ─── 3. The code, as it is deployed right now ───────────────────────────────
#
# `git archive HEAD` rather than the whole .git directory: what is worth
# keeping beside a database is the one commit that database belongs to, so a
# restore can put the two back together. The history is on GitHub already and
# copying it nightly would make every archive mostly the same bytes.
#
# The commit is recorded next to it, because a tar of files does not say which
# commit it came from and that is the first thing anybody restoring will ask.

git -C "$REPO_DIR" archive --format=tar HEAD >"$STAGE/code.tar"
git -C "$REPO_DIR" log -1 --format='%H%n%cI%n%s' >"$STAGE/code-commit.txt"
log "code       ok  ($(git -C "$REPO_DIR" rev-parse --short HEAD), $(du -h "$STAGE/code.tar" | cut -f1))"

# ─── 4. One archive ─────────────────────────────────────────────────────────

( cd "$(dirname "$STAGE")" && zip -qr "$ARCHIVE" "$(basename "$STAGE")" )
log "archive    ok  $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"

# ─── 5. Upload ──────────────────────────────────────────────────────────────
#
# The exit code decides whether anything is deleted. A failed upload keeps the
# archive in /tmp on purpose: it is the only copy, and a script that tidied it
# away would turn "the backup did not reach Drive" into "the backup does not
# exist" — and would do it quietly, every night, until somebody needed it.

if rclone copy "$ARCHIVE" "$RCLONE_REMOTE/" >>"$LOG_FILE" 2>&1; then
  log "upload     ok  -> $RCLONE_REMOTE/$(basename "$ARCHIVE")"
  rm -rf "$STAGE" "$ARCHIVE"
  log "cleanup    ok  removed $STAGE and $ARCHIVE"
  log "──── backup $STAMP done ────"
else
  status=$?
  log "ERROR: rclone exited $status — the upload did not happen"
  log "KEPT $ARCHIVE and $STAGE so this can be retried by hand:"
  log "  rclone copy $ARCHIVE $RCLONE_REMOTE/"
  log "──── backup $STAMP FAILED ────"
  exit "$status"
fi
