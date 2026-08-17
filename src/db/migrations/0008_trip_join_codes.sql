-- Short-lived codes that let someone join a trip by scanning a QR code,
-- without the owner knowing their email address first.
--
-- Deliberately a separate table from trip_invites: an invite is addressed to
-- one person and lives until it is accepted or revoked, while a join code is
-- addressed to whoever is standing in front of the phone and is worthless
-- fifteen minutes later. Putting both in one table would mean one of them
-- carrying columns that never apply.

CREATE TABLE trip_join_codes (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  trip_id    INTEGER NOT NULL REFERENCES trips(id),
  code       TEXT NOT NULL UNIQUE,
  expires_at TEXT NOT NULL,
  created_by INTEGER NOT NULL REFERENCES users(id),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- UNIQUE on `code` already indexes the redeem lookup — the one an attacker
-- would hammer. This one covers the other direction: finding a trip's live
-- codes, which is how issuing a new code retires the old ones.
CREATE INDEX idx_trip_join_codes_trip ON trip_join_codes (trip_id, expires_at);
