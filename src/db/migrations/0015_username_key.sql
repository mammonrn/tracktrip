-- Usernames become Thai, and "the same username" stops being lower(username).
--
-- src/users/username.js is where the rule now lives and why. The short of it:
-- supporting Thai means two spellings can draw identical glyphs — `สำ` is one
-- code point, `สํา` is two, and Unicode normalisation deliberately does not
-- merge them — so a unique index has to compare something more considered than
-- the stored text.
--
--
-- WHY A COLUMN AND NOT AN EXPRESSION INDEX
--
-- The old index was `CREATE UNIQUE INDEX ... ON users (lower(username))`, and
-- `lower()` is the problem. SQLite's built-in is **ASCII-only** unless the
-- library was compiled against ICU, which better-sqlite3's bundled amalgamation
-- is not:
--
--   sqlite> SELECT lower('POOM'), lower('ÉCOLE'), lower('ก้อง');
--           poom            École           ก้อง
--
-- So the guarantee the index was written for — "Poom and poom are not two
-- riders" — already stopped at A-Z, and nothing in SQL reaches the Thai case at
-- all. There is no expression to index that would: the fold has to expand SARA
-- AM, reorder the marks in a cluster and recompose, which is a function, not a
-- SELECT.
--
-- So the fold runs in JavaScript, at the one place a username is written, and
-- its answer is stored beside the username. `username_key` is a cache of a
-- decision rather than a second copy of the data.
--
--
-- WHY THE BACKFILL IS SAFE, AND WHY IT IS lower()
--
-- Every username in this database is ASCII, and that is checkable rather than
-- hopeful: `username` has exactly one writer — `PATCH /me`, which has gone
-- through validateUsername since the column was added in 0007 — and that
-- validator has only ever accepted /^[a-zA-Z0-9_.]+$/. Google sign-in does not
-- touch the column. So for every existing row, `lower(username)` and
-- `usernameKey(username)` are the same string by construction: the fold's Thai
-- steps are no-ops on ASCII and its last step is `toLowerCase()`.
--
-- That is also why creating the new unique index cannot fail on this data.
-- 0013 wrote up the trap — a UNIQUE INDEX is validated against existing rows,
-- so a CREATE that collides rolls the migration back and, because migrations
-- run at boot, takes the API down with it. This index is over the same values
-- the old one already held unique, so any pair it would reject was already
-- rejected. Nothing new can collide.
--
-- NULLs stay NULL: SQLite ignores them in a unique index, so every rider who
-- has never set a username still coexists with all the others.

ALTER TABLE users ADD COLUMN username_key TEXT;

UPDATE users SET username_key = lower(username) WHERE username IS NOT NULL;

DROP INDEX IF EXISTS idx_users_username_lower;
CREATE UNIQUE INDEX idx_users_username_key ON users (username_key);
