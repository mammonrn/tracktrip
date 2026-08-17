import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { runCleanup } from '../src/db/cleanup.js';

const RETENTION_DAYS = 30;
const NOW = new Date('2026-08-17T00:00:00.000Z');

function isoDaysAgo(days, from = NOW) {
  return new Date(from.getTime() - days * 24 * 60 * 60 * 1000).toISOString();
}

function seedDb() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  db.prepare("INSERT INTO users (google_sub) VALUES ('owner-1')").run();

  const insertTrip = db.prepare(
    "INSERT INTO trips (id, name, owner_id, status, ended_at) VALUES (?, ?, 1, ?, ?)"
  );
  const insertHistory = db.prepare(
    'INSERT INTO position_history (trip_id, user_id, lat, lng, recorded_at) VALUES (?, 1, 13.7, 100.5, ?)'
  );

  // Trip 1: ended well past the retention window -> history should be deleted.
  insertTrip.run(1, 'Old ended trip', 'ended', isoDaysAgo(RETENTION_DAYS + 5));
  insertHistory.run(1, isoDaysAgo(RETENTION_DAYS + 5));
  insertHistory.run(1, isoDaysAgo(RETENTION_DAYS + 3));

  // Trip 2: ended recently, still within retention window -> history kept.
  insertTrip.run(2, 'Recently ended trip', 'ended', isoDaysAgo(RETENTION_DAYS - 5));
  insertHistory.run(2, isoDaysAgo(RETENTION_DAYS - 5));

  // Trip 3: still active, regardless of age -> history kept.
  insertTrip.run(3, 'Active trip', 'active', null);
  insertHistory.run(3, isoDaysAgo(RETENTION_DAYS + 100));

  return db;
}

test('dry-run (default) reports what would be deleted without deleting anything', () => {
  const db = seedDb();
  const result = runCleanup(db, { retentionDays: RETENTION_DAYS, now: NOW });

  assert.equal(result.dryRun, true);
  assert.equal(result.tripCount, 1);
  assert.equal(result.deletedRows, 2);

  const remaining = db.prepare('SELECT COUNT(*) AS count FROM position_history').get().count;
  assert.equal(remaining, 4);
});

test('--confirm deletes only history for ended trips past the retention window', () => {
  const db = seedDb();
  const result = runCleanup(db, { retentionDays: RETENTION_DAYS, confirm: true, now: NOW });

  assert.equal(result.dryRun, false);
  assert.equal(result.tripCount, 1);
  assert.equal(result.deletedRows, 2);

  const remainingTripIds = db
    .prepare('SELECT DISTINCT trip_id FROM position_history ORDER BY trip_id')
    .all()
    .map((row) => row.trip_id);
  assert.deepEqual(remainingTripIds, [2, 3]);
});

test('active trips are never cleaned up regardless of history age', () => {
  const db = seedDb();
  runCleanup(db, { retentionDays: 0, confirm: true, now: NOW });

  const activeTripHistory = db
    .prepare('SELECT COUNT(*) AS count FROM position_history WHERE trip_id = 3')
    .get().count;
  assert.equal(activeTripHistory, 1);
});
