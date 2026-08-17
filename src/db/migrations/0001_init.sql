-- Initial schema: users, trips, membership, invites, and live/historical positions.

CREATE TABLE users (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  google_sub   TEXT NOT NULL UNIQUE,
  email        TEXT,
  display_name TEXT,
  photo_url    TEXT,
  created_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  updated_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE trips (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  name       TEXT,
  owner_id   INTEGER NOT NULL REFERENCES users(id),
  status     TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'ended')),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  ended_at   TEXT
);

CREATE TABLE trip_members (
  trip_id    INTEGER NOT NULL REFERENCES trips(id),
  user_id    INTEGER NOT NULL REFERENCES users(id),
  role       TEXT NOT NULL CHECK (role IN ('owner', 'member')),
  is_sharing INTEGER NOT NULL DEFAULT 1,
  joined_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  PRIMARY KEY (trip_id, user_id)
);

CREATE TABLE trip_invites (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  trip_id    INTEGER NOT NULL REFERENCES trips(id),
  email      TEXT NOT NULL,
  invited_by INTEGER NOT NULL REFERENCES users(id),
  status     TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'accepted', 'revoked')),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  UNIQUE (trip_id, email)
);

CREATE TABLE member_positions (
  trip_id      INTEGER NOT NULL REFERENCES trips(id),
  user_id      INTEGER NOT NULL REFERENCES users(id),
  lat          REAL,
  lng          REAL,
  accuracy     REAL,
  speed        REAL,
  heading      REAL,
  battery_pct  INTEGER,
  recorded_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
  PRIMARY KEY (trip_id, user_id)
);

CREATE TABLE position_history (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  trip_id     INTEGER NOT NULL REFERENCES trips(id),
  user_id     INTEGER NOT NULL REFERENCES users(id),
  lat         REAL,
  lng         REAL,
  recorded_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_position_history_trip_user_time ON position_history (trip_id, user_id, recorded_at);
CREATE INDEX idx_trips_owner_status ON trips (owner_id, status);
CREATE INDEX idx_trip_invites_email_status ON trip_invites (email, status);
