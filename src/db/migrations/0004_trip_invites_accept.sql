-- Records who accepted a trip invite and when.
--
-- `trip_invites.status` already tracks pending/accepted/revoked, but not the
-- user behind an acceptance: invites are addressed to an email, and the
-- account that eventually claims one is only known at accept time.

ALTER TABLE trip_invites ADD COLUMN accepted_at TEXT;
ALTER TABLE trip_invites ADD COLUMN accepted_by INTEGER REFERENCES users(id);

-- Invite emails are stored normalized (trimmed, lowercased) from here on, so
-- that UNIQUE (trip_id, email) really does dedupe and lookups can compare the
-- column directly instead of wrapping it in lower() and losing the index.
UPDATE trip_invites SET email = lower(trim(email));
