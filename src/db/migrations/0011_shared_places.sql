-- Places the riders on this installation add themselves, and everybody can find.
--
-- ## Why this table exists at all
--
-- The place search is LocationIQ over OpenStreetMap, and OSM is thin in
-- exactly the way that matters here. "ปตท สวนดอก" — a petrol station every
-- rider in Chiang Mai can name — is not in it: the stations around Suan Dok
-- are tagged `PTT` and nothing else, and "สวนดอก" is not an administrative
-- area there, so there is no query that finds it. No amount of tuning the
-- geocoder produces a row that is not in the data.
--
-- So the riders put it in. A name and a point, typed once by whoever knows the
-- place, found from then on by everybody.
--
-- ## Why it is shared and not per-rider
--
-- This installation is a group of friends who ride together — tens of people,
-- not thousands of strangers. A place worth naming is worth naming once: a
-- per-rider table would have twenty people each typing the same petrol station
-- and none of them finding the other nineteen. The trust that makes that safe
-- is a property of who is on the server, and it is the same trust that already
-- lets any member drop a waypoint on somebody else's trip.
--
-- `created_by` is here for attribution and for the delete rule, *not* for
-- visibility: every signed-in rider reads every row.
--
-- ## ON DELETE SET NULL
--
-- A shared place outlives the account that typed it. It belongs to the group,
-- the way a waypoint belongs to its trip, so deleting a rider must not delete
-- the petrol station they added — the other riders are still using it and did
-- not ask for it to go.
--
-- The column then goes NULL rather than dangling, which the delete rule reads
-- as "nobody owns this any more": a super user can still remove it, and no
-- ordinary rider can, because no ordinary rider matches NULL. That is the
-- right answer for an orphan — it stays until somebody with the whole system
-- decides otherwise.
--
-- This is the one place in this schema that differs from `trip_waypoints`,
-- whose `added_by` is NOT NULL. A waypoint is scoped to a trip that is itself
-- scoped to people; a shared place is scoped to the installation.

CREATE TABLE shared_places (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT NOT NULL,
  lat         REAL NOT NULL,
  lng         REAL NOT NULL,
  -- Who typed it in. NULL once that account is gone; see above.
  created_by  INTEGER REFERENCES users(id) ON DELETE SET NULL,
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- The daily add limit counts this rider's rows since midnight, on every POST.
-- Without the index that is a full scan of the table on the one request that
-- is already doing a write.
CREATE INDEX idx_shared_places_creator_created ON shared_places (created_by, created_at);

-- Every read is either "the newest ones" or a name match ordered by newest, so
-- the ordering is worth having ready. The name match itself is a scan on
-- purpose — see src/places/search.js for why LIKE and not FTS.
CREATE INDEX idx_shared_places_created ON shared_places (created_at DESC);
