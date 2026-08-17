-- Where a trip starts and where it is going.
--
-- Columns on `trips` rather than two more rows in `trip_waypoints`, even
-- though a waypoint already carries a name and a coordinate. The two are
-- different kinds of thing:
--
--   * A trip has at most one origin and at most one destination, ever. That
--     is a property of the trip, and the schema should be able to say so — as
--     columns it is enforced, as rows it would be a rule living in
--     application code and quietly broken by the first client that posted
--     twice.
--   * Waypoints are a list. Their order matters, they are added and deleted
--     freely mid-ride, and `order_index` is meaningful only among them.
--     Overloading `type` with 'origin' and 'destination' would put two
--     singletons into a table whose whole shape is "many, in order", and
--     every read of the planned route would then have to filter them back
--     out.
--
-- All six are nullable and stay that way. A trip created from the trip list
-- has no idea where it is going yet, and a group that never fills these in is
-- using the app exactly as intended.
--
-- Latitude and longitude travel together with their label: a name with no
-- coordinate cannot be drawn, and a coordinate with no name is a pin nobody
-- can read. SQLite has no multi-column CHECK worth writing here, so the
-- pairing is enforced in validateTripEndpoint (src/routes/trips.js) instead —
-- and the API refuses a half of one rather than storing it.

ALTER TABLE trips ADD COLUMN origin_lat REAL;
ALTER TABLE trips ADD COLUMN origin_lng REAL;
ALTER TABLE trips ADD COLUMN origin_label TEXT;

ALTER TABLE trips ADD COLUMN destination_lat REAL;
ALTER TABLE trips ADD COLUMN destination_lng REAL;
ALTER TABLE trips ADD COLUMN destination_label TEXT;
