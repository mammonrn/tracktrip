-- Waypoints on a trip: either points planned ahead of time (ordered), or
-- points dropped live while riding (unordered, chronological).

CREATE TABLE trip_waypoints (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  trip_id     INTEGER NOT NULL REFERENCES trips(id),
  name        TEXT NOT NULL,
  lat         REAL NOT NULL,
  lng         REAL NOT NULL,
  type        TEXT NOT NULL CHECK (type IN ('planned', 'live')),
  -- Ordering position within the planned route. Only meaningful for
  -- type='planned'; always NULL for type='live'.
  order_index INTEGER,
  added_by    INTEGER NOT NULL REFERENCES users(id),
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX idx_trip_waypoints_trip_type ON trip_waypoints (trip_id, type);
