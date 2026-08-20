-- Leaving a trip becomes a soft delete.
--
-- `DELETE /trips/:id/members/me` removed the trip_members row outright, which
-- took the ride out of the pair's history with it: "Ridden with before" is
-- built by joining trip_members to itself, so a trip somebody left stopped
-- being a trip the two of them shared. They had ridden together — that
-- happened — and the app should go on offering them to each other.
--
-- So the row stays and `left_at` is stamped instead. The rule the whole
-- codebase used to rely on ("a row here is a member") is gone with it, and
-- src/trips/membership.js is where its replacement lives.
--
--
-- WHAT THE TRIGGERS HAVE TO LEARN
--
-- 0013 enforces one active trip per rider, and it counted rows. A row is no
-- longer a membership, so every one of its reads takes `left_at IS NULL` —
-- otherwise a rider who left a trip stays locked out of starting another,
-- which is precisely the trap 6c0865b opened the door on.
--
-- And membership is no longer only gained by INSERT. A rider who left and is
-- invited back conflicts with their own dormant row, so `ON CONFLICT DO
-- UPDATE SET left_at = NULL` is what rejoining looks like — an UPDATE, which a
-- BEFORE INSERT trigger never sees. The third trigger below is that door.
--
-- Triggers cannot be altered in place, so the two from 0013 are dropped and
-- rewritten rather than patched. Dropping and recreating inside this
-- migration's transaction means there is no moment at which the constraint is
-- not enforced.

ALTER TABLE trip_members ADD COLUMN left_at TEXT;

DROP TRIGGER trip_members_one_active_trip;
DROP TRIGGER trips_one_active_trip_on_reactivate;

-- Joining a trip: the row is new.
CREATE TRIGGER trip_members_one_active_trip
BEFORE INSERT ON trip_members
FOR EACH ROW
WHEN NEW.left_at IS NULL
 AND (SELECT status FROM trips WHERE id = NEW.trip_id) = 'active'
 AND EXISTS (
   SELECT 1
     FROM trip_members AS held
     JOIN trips ON trips.id = held.trip_id
    WHERE held.user_id = NEW.user_id
      AND held.trip_id != NEW.trip_id
      AND held.left_at IS NULL
      AND trips.status = 'active'
 )
BEGIN
  SELECT RAISE(ABORT, 'one active trip per rider');
END;

-- Rejoining a trip they had left: the row is theirs already, and coming back
-- is `left_at` going back to NULL.
CREATE TRIGGER trip_members_one_active_trip_on_rejoin
BEFORE UPDATE OF left_at ON trip_members
FOR EACH ROW
WHEN NEW.left_at IS NULL
 AND OLD.left_at IS NOT NULL
 AND (SELECT status FROM trips WHERE id = NEW.trip_id) = 'active'
 AND EXISTS (
   SELECT 1
     FROM trip_members AS held
     JOIN trips ON trips.id = held.trip_id
    WHERE held.user_id = NEW.user_id
      AND held.trip_id != NEW.trip_id
      AND held.left_at IS NULL
      AND trips.status = 'active'
 )
BEGIN
  SELECT RAISE(ABORT, 'one active trip per rider');
END;

-- A finished trip being revived under members who have moved on. No route does
-- this; see 0013 for why a constraint that only holds for the paths the
-- application happens to use is a convention rather than a constraint.
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
      AND mine.left_at IS NULL
      AND held.trip_id != NEW.id
      AND held.left_at IS NULL
      AND trips.status = 'active'
 )
BEGIN
  SELECT RAISE(ABORT, 'one active trip per rider');
END;

-- The lookup every "is this rider on a trip right now?" performs. Partial,
-- because that question only ever asks about rows with no `left_at` — the one
-- query that reads the others is "have we ridden together?", which scans a
-- rider's whole history and is not what this index is for.
DROP INDEX IF EXISTS idx_trip_members_user;
CREATE INDEX idx_trip_members_user_current ON trip_members (user_id) WHERE left_at IS NULL;
