import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';

function freshDb() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  return db;
}

test('runMigrations creates the schema_migrations table and applies pending migrations', () => {
  const db = freshDb();
  const applied = runMigrations(db, MIGRATIONS_DIR);

  assert.ok(applied.length > 0);
  assert.ok(applied.includes('0001_init.sql'));

  const tables = db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
    .all()
    .map((row) => row.name);

  for (const expected of [
    'users',
    'trips',
    'trip_members',
    'trip_invites',
    'member_positions',
    'position_history',
    'schema_migrations',
  ]) {
    assert.ok(tables.includes(expected), `expected table ${expected} to exist`);
  }
});

test('runMigrations is idempotent and does not re-apply or drop existing migrations', () => {
  const db = freshDb();
  runMigrations(db, MIGRATIONS_DIR);

  db.prepare(
    "INSERT INTO users (google_sub, email) VALUES ('sub-1', 'rider@example.com')"
  ).run();

  const secondRun = runMigrations(db, MIGRATIONS_DIR);
  assert.equal(secondRun.length, 0);

  const user = db.prepare('SELECT * FROM users WHERE google_sub = ?').get('sub-1');
  assert.equal(user.email, 'rider@example.com');
});

test('expected indexes exist', () => {
  const db = freshDb();
  runMigrations(db, MIGRATIONS_DIR);

  const indexes = db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'index'")
    .all()
    .map((row) => row.name);

  assert.ok(indexes.includes('idx_position_history_trip_user_time'));
  assert.ok(indexes.includes('idx_trips_owner_status'));
  assert.ok(indexes.includes('idx_trip_invites_email_status'));
});

test('trips.status CHECK constraint rejects invalid values', () => {
  const db = freshDb();
  runMigrations(db, MIGRATIONS_DIR);
  db.prepare("INSERT INTO users (google_sub) VALUES ('sub-owner')").run();

  assert.throws(() => {
    db.prepare(
      "INSERT INTO trips (name, owner_id, status) VALUES ('Ride', 1, 'bogus')"
    ).run();
  });
});
