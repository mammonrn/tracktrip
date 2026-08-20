-- Places one rider saved for themselves. Nobody else ever sees a row of this.
--
-- ## Why this is a second table and not a column on shared_places
--
-- `shared_places` is a list the whole installation reads — that is its entire
-- point, and `created_by` on it is attribution, deliberately not a filter. A
-- privacy flag on that table would make every read of it one forgotten
-- `WHERE` away from publishing somebody's home address, and the forgetting
-- would be silent: the query still returns rows, the screen still looks right,
-- and the only symptom is a stranger seeing where you live.
--
-- Two tables cannot be confused for one another. A query against this table
-- that forgets its owner returns rows that do not belong to the caller and is
-- wrong in an obvious way; a query against `shared_places` is *supposed* to be
-- unscoped. The rule is legible from the table name at every call site, which
-- is worth more here than the column it saves.
--
-- ## user_id is NOT NULL and ON DELETE CASCADE
--
-- The opposite of `shared_places.created_by`, and for the opposite reason. A
-- shared place outlives the account that typed it because the group is still
-- using it. Nobody is using this one: it is one person's note about where they
-- live, and an account being deleted is exactly when it should stop existing.
-- There is also no such thing as an unowned row here — with no owner there is
-- nobody it could be shown to, so NULL would be a row that can only leak.
--
-- ## label and name
--
-- `label` is what the shortcut says — "บ้าน", "ที่ทำงาน", or whatever the rider
-- typed — and `name` is what the place is called on the map. They are separate
-- because they answer different questions: the chip in the search screen shows
-- the label, and the row it fills in the route shows the name. A rider whose
-- home is a condo called "The Astra" wants the chip to say "บ้าน" and the route
-- to say something they will recognise a month later.

CREATE TABLE personal_places (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  label       TEXT NOT NULL,
  name        TEXT NOT NULL,
  lat         REAL NOT NULL,
  lng         REAL NOT NULL,
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Every read of this table is "this rider's places", so the owner leads the
-- index. It is not an optimisation: it is the shape of the only query there
-- is, and a plan that ever scans this table across users is a bug worth
-- noticing in an EXPLAIN.
CREATE INDEX idx_personal_places_user ON personal_places (user_id, created_at DESC);
