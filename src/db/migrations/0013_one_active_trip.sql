-- One active trip per rider, enforced by the database.
--
-- The rule and what counts as "on a trip" are written up in
-- src/trips/activeTrip.js. This is the backstop under the route guards.
--
--
-- WHY A TRIGGER AND NOT A PARTIAL UNIQUE INDEX
--
-- The obvious shape would be
--
--   CREATE UNIQUE INDEX ... ON trip_members (user_id) WHERE <trip is active>
--
-- and it cannot be written. A partial index's WHERE clause may only reference
-- columns of the table being indexed, and "active" lives in trips.status, one
-- join away. Making it expressible would mean copying the status onto every
-- trip_members row and keeping the copy in step with POST /trips/:id/end —
-- a denormalisation whose failure mode is a rider silently locked out of ever
-- starting another trip.
--
-- The second reason is the one that decided it. A UNIQUE INDEX is validated
-- against existing rows when it is created, so on a database that already
-- holds a rider with two active trips the CREATE fails, the migration
-- transaction rolls back, and — because migrations run at boot in
-- src/index.js — the API does not start. Nobody knows whether production is in
-- that state until the deploy either works or takes the server down.
--
-- A trigger is checked only against writes that happen after it exists. A
-- rider who is already on two active trips keeps both, keeps riding, and is
-- simply refused a third. That is the right answer for legacy data as well as
-- the safe one: the rows are real trips with real positions in them, and
-- picking one to evict is a decision for whoever owns the data, not for a
-- migration that runs unattended at boot. scripts/check-one-active-trip.js
-- reports who is in that state, and the guards keep it from growing.
--
--
-- THE TWO WAYS IN
--
-- Membership is only ever gained by an INSERT into trip_members, from three
-- call sites: the owner's row inside POST /trips, accepting an invite, and
-- redeeming a join code. One trigger covers all three.
--
-- The second trigger covers a door the API does not have. Nothing reactivates
-- a trip today — POST /trips/:id/end is the only writer of trips.status and it
-- only ever sets 'ended' — but DEPLOY.md documents reaching for sqlite3 on the
-- server, and a hand-written UPDATE that revived a trip would put every one of
-- its members back on two. A constraint that holds only for the paths the
-- application happens to use is a convention, not a constraint.

CREATE TRIGGER trip_members_one_active_trip
BEFORE INSERT ON trip_members
FOR EACH ROW
WHEN (SELECT status FROM trips WHERE id = NEW.trip_id) = 'active'
 AND EXISTS (
   SELECT 1
     FROM trip_members AS held
     JOIN trips ON trips.id = held.trip_id
    WHERE held.user_id = NEW.user_id
      AND held.trip_id != NEW.trip_id
      AND trips.status = 'active'
 )
BEGIN
  SELECT RAISE(ABORT, 'one active trip per rider');
END;

CREATE TRIGGER trips_one_active_trip_on_reactivate
BEFORE UPDATE OF status ON trips
FOR EACH ROW
WHEN NEW.status = 'active'
 AND OLD.status != 'active'
 AND EXISTS (
   SELECT 1
     FROM trip_members AS mine
     JOIN trip_members AS held ON held.user_id = mine.user_id
     JOIN trips ON trips.id = held.trip_id
    WHERE mine.trip_id = NEW.id
      AND held.trip_id != NEW.id
      AND trips.status = 'active'
 )
BEGIN
  SELECT RAISE(ABORT, 'one active trip per rider');
END;

-- The lookup both the guards and the first trigger perform: this rider's rows,
-- to be joined against trips.status. trip_members' primary key is
-- (trip_id, user_id), which cannot serve a query that knows only the user.
CREATE INDEX IF NOT EXISTS idx_trip_members_user ON trip_members (user_id);
