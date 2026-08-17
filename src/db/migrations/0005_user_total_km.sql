-- Lifetime distance ridden, per user, in kilometres.
--
-- Accumulated on every accepted position fix rather than derived on read:
-- the source segments live in member_positions, which only ever holds each
-- rider's *latest* fix, so the distance between two fixes is gone the moment
-- the next one overwrites it. Summing it as it arrives is the only way to
-- keep a lifetime total without also keeping every fix forever.

ALTER TABLE users ADD COLUMN total_km REAL NOT NULL DEFAULT 0;
