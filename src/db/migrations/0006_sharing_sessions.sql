-- Per-rider sharing sessions.
--
-- NOTE (added later): the paragraph below about carrying on past the end of a
-- trip no longer describes the system. Ending a trip now stops sharing for
-- the whole group and deletes every session in it; sessions exist only to
-- pause and resume *within* a running trip. The schema is unchanged, so this
-- migration is left as it was applied — see the README for current behaviour.
--
-- Until now "may this rider report a position?" was answered by the trip's
-- status alone: the owner ended the trip and everyone stopped, together. That
-- is the wrong shape for the actual ride — someone peeling off to carry on
-- alone afterwards still wants to be seen, and someone who has gone home does
-- not.
--
-- So the question moves off the trip and onto the rider. A row here means
-- "this rider has sharing switched on for this trip"; no row means they never
-- started, or they stopped. `expires_at` is when it lapses on its own, or
-- NULL for "until I turn it off".
--
-- Ending a trip deliberately does NOT touch these rows. A session runs out
-- when its own clock says so, or when its owner stops it.

CREATE TABLE sharing_sessions (
  trip_id    INTEGER NOT NULL REFERENCES trips(id),
  user_id    INTEGER NOT NULL REFERENCES users(id),
  started_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  -- NULL means no expiry: the rider stops it by hand.
  expires_at TEXT,
  PRIMARY KEY (trip_id, user_id)
);

-- "Which trips am I still sharing to?" — the lookup behind a client showing
-- its own live sessions.
CREATE INDEX idx_sharing_sessions_user ON sharing_sessions (user_id);

-- trip_members.is_sharing goes away. It was declared in 0001_init.sql and
-- never written by anything, so it has read 1 for every member since the
-- first migration — including in the API, where it was a constant dressed up
-- as a fact. Leaving it beside the table above would give the codebase two
-- answers to the same question, one of them permanently wrong.
--
-- Nothing is lost: every value in the column is the default.
ALTER TABLE trip_members DROP COLUMN is_sharing;
