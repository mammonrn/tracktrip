import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import Database from 'better-sqlite3';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';

function freshDb() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  return db;
}

/**
 * Applies only the migrations that sort before `cutoff`, through a directory
 * holding just those files — the state a database deployed before `cutoff`
 * is actually in. Returns a cleanup function.
 */
function migrateUpTo(db, cutoff) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tracktrip-migrations-'));
  for (const file of fs.readdirSync(MIGRATIONS_DIR).filter((f) => f.endsWith('.sql') && f < cutoff)) {
    fs.copyFileSync(path.join(MIGRATIONS_DIR, file), path.join(dir, file));
  }
  runMigrations(db, dir);
  return () => fs.rmSync(dir, { recursive: true, force: true });
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

test('0004 upgrades a database that already holds invites, without losing them', () => {
  const db = freshDb();
  const cleanup = migrateUpTo(db, '0004');
  try {
    const ownerId = Number(
      db.prepare("INSERT INTO users (google_sub, email) VALUES ('sub-owner', 'owner@gmail.com')").run()
        .lastInsertRowid
    );
    const tripId = Number(
      db.prepare("INSERT INTO trips (name, owner_id) VALUES ('Legacy ride', ?)").run(ownerId)
        .lastInsertRowid
    );
    // Written before invite emails were normalized on the way in.
    const inviteId = Number(
      db
        .prepare('INSERT INTO trip_invites (trip_id, email, invited_by) VALUES (?, ?, ?)')
        .run(tripId, '  Friend@GMail.com ', ownerId).lastInsertRowid
    );

    const applied = runMigrations(db, MIGRATIONS_DIR);
    assert.ok(applied.includes('0004_trip_invites_accept.sql'));

    const invite = db.prepare('SELECT * FROM trip_invites WHERE id = ?').get(inviteId);
    assert.equal(invite.email, 'friend@gmail.com'); // backfilled to match new writes
    assert.equal(invite.status, 'pending'); // and otherwise untouched
    assert.equal(invite.accepted_at, null);
    assert.equal(invite.accepted_by, null);

    assert.equal(runMigrations(db, MIGRATIONS_DIR).length, 0);
  } finally {
    cleanup();
  }
});

test('0005 gives every existing rider a zero lifetime distance', () => {
  const db = freshDb();
  const cleanup = migrateUpTo(db, '0005');
  try {
    const riderId = Number(
      db.prepare("INSERT INTO users (google_sub, email) VALUES ('sub-rider', 'rider@gmail.com')").run()
        .lastInsertRowid
    );

    const applied = runMigrations(db, MIGRATIONS_DIR);
    assert.ok(applied.includes('0005_user_total_km.sql'));

    // Riders who existed before the column did start from zero, not NULL —
    // arithmetic against NULL would silently swallow every later addition.
    const rider = db.prepare('SELECT * FROM users WHERE id = ?').get(riderId);
    assert.equal(rider.total_km, 0);
    assert.equal(rider.email, 'rider@gmail.com');

    db.prepare('UPDATE users SET total_km = total_km + ? WHERE id = ?').run(12.5, riderId);
    assert.equal(db.prepare('SELECT total_km FROM users WHERE id = ?').get(riderId).total_km, 12.5);

    assert.equal(runMigrations(db, MIGRATIONS_DIR).length, 0);
  } finally {
    cleanup();
  }
});

test('0006 adds sharing sessions and drops the is_sharing column it replaces', () => {
  const db = freshDb();
  const cleanup = migrateUpTo(db, '0006');
  try {
    const ownerId = Number(
      db.prepare("INSERT INTO users (google_sub, email) VALUES ('sub-owner', 'owner@gmail.com')").run()
        .lastInsertRowid
    );
    const tripId = Number(
      db.prepare("INSERT INTO trips (name, owner_id) VALUES ('Legacy ride', ?)").run(ownerId)
        .lastInsertRowid
    );
    db.prepare('INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, ?)').run(
      tripId,
      ownerId,
      'owner'
    );

    const columnNames = () =>
      db.prepare('PRAGMA table_info(trip_members)').all().map((c) => c.name);
    assert.ok(columnNames().includes('is_sharing'), 'the column exists before 0006');

    const applied = runMigrations(db, MIGRATIONS_DIR);
    assert.ok(applied.includes('0006_sharing_sessions.sql'));

    // The membership survives; only the column it never used goes away.
    assert.ok(!columnNames().includes('is_sharing'));
    const membership = db
      .prepare('SELECT * FROM trip_members WHERE trip_id = ? AND user_id = ?')
      .get(tripId, ownerId);
    assert.equal(membership.role, 'owner');

    // Nobody is sharing until they say so — an existing member must not be
    // grandfathered into an open session by the upgrade.
    assert.equal(db.prepare('SELECT COUNT(*) AS count FROM sharing_sessions').get().count, 0);

    db.prepare(
      'INSERT INTO sharing_sessions (trip_id, user_id, expires_at) VALUES (?, ?, NULL)'
    ).run(tripId, ownerId);
    const session = db.prepare('SELECT * FROM sharing_sessions').get();
    assert.equal(session.expires_at, null);
    assert.ok(session.started_at, 'started_at defaults to now');

    assert.equal(runMigrations(db, MIGRATIONS_DIR).length, 0);
  } finally {
    cleanup();
  }
});

test('0013 and 0014 upgrade a database that already breaks the rule they add', () => {
  // The production case, and the one the choice of a trigger over a partial
  // unique index turns on. An index is validated against existing rows when it
  // is created, so on a database already holding a rider with two active trips
  // the CREATE fails, the migration transaction rolls back, and — because
  // migrations run at boot — the API does not start. Nobody would find out
  // until the deploy either worked or took the server down.
  const db = freshDb();
  const cleanup = migrateUpTo(db, '0013');
  try {
    const poom = Number(
      db.prepare("INSERT INTO users (google_sub, email) VALUES ('sub-poom', 'poom@gmail.com')").run()
        .lastInsertRowid
    );
    const nut = Number(
      db.prepare("INSERT INTO users (google_sub, email) VALUES ('sub-nut', 'nut@gmail.com')").run()
        .lastInsertRowid
    );
    // Three trips, both riders on all of them, all still running — a state the
    // API allowed for as long as it existed.
    const tripIds = ['Chiang Mai loop', 'Pai run', 'Doi Inthanon'].map((name) => {
      const tripId = Number(
        db.prepare("INSERT INTO trips (name, owner_id, status) VALUES (?, ?, 'active')").run(name, poom)
          .lastInsertRowid
      );
      db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'owner')").run(tripId, poom);
      db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')").run(tripId, nut);
      return tripId;
    });

    const applied = runMigrations(db, MIGRATIONS_DIR);
    assert.ok(applied.includes('0013_one_active_trip.sql'));
    assert.ok(applied.includes('0014_member_left_at.sql'));

    // Nobody is evicted. Those are real trips with real positions in them, and
    // picking one to close is a decision for whoever owns the data rather than
    // for a migration running unattended at boot.
    assert.equal(db.prepare('SELECT COUNT(*) AS n FROM trip_members WHERE left_at IS NULL').get().n, 6);

    // The rule holds from here on, though: a fourth trip is refused.
    const fourth = Number(
      db.prepare("INSERT INTO trips (name, owner_id, status) VALUES ('Fourth', ?, 'active')").run(poom)
        .lastInsertRowid
    );
    assert.throws(
      () =>
        db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'owner')").run(fourth, poom),
      /one active trip per rider/
    );

    // And leaving is the way out of it, one trip at a time.
    const now = new Date().toISOString();
    db.prepare('UPDATE trip_members SET left_at = ? WHERE user_id = ? AND trip_id IN (?, ?)')
      .run(now, nut, tripIds[0], tripIds[1]);
    assert.equal(
      db
        .prepare(
          `SELECT COUNT(*) AS n FROM trip_members
             JOIN trips ON trips.id = trip_members.trip_id
            WHERE trip_members.user_id = ? AND trip_members.left_at IS NULL
              AND trips.status = 'active'`
        )
        .get(nut).n,
      1
    );

    assert.equal(runMigrations(db, MIGRATIONS_DIR).length, 0);
  } finally {
    cleanup();
  }
});

test('0015 backfills username_key from the ASCII usernames already stored', () => {
  const db = freshDb();
  const cleanup = migrateUpTo(db, '0015');

  // A database as it stands before this migration: usernames set, and every
  // one of them ASCII, because validateUsername has only ever accepted
  // [a-zA-Z0-9_.] and PATCH /me is the column's only writer.
  const insert = db.prepare(
    'INSERT INTO users (google_sub, email, display_name, username) VALUES (?, ?, ?, ?)'
  );
  insert.run('sub-a', 'a@gmail.com', 'A', 'Poom');
  insert.run('sub-b', 'b@gmail.com', 'B', 'rider.two');
  insert.run('sub-c', 'c@gmail.com', 'C', null);

  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM users').get().n, 3);

  runMigrations(db, MIGRATIONS_DIR);
  cleanup();

  // Every row keeps its username exactly as the rider typed it, and gains the
  // folded key beside it. For ASCII the fold is lower(), which is why the
  // backfill can be plain SQL — see the migration.
  const rows = db.prepare('SELECT username, username_key FROM users ORDER BY id').all();
  assert.deepEqual(rows, [
    { username: 'Poom', username_key: 'poom' },
    { username: 'rider.two', username_key: 'rider.two' },
    { username: null, username_key: null },
  ]);

  // The old expression index is gone and the new one is on the column.
  const indexes = db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'index'")
    .all()
    .map((row) => row.name);
  assert.ok(!indexes.includes('idx_users_username_lower'));
  assert.ok(indexes.includes('idx_users_username_key'));
});

test('0015 leaves the uniqueness guarantee at least as strong as it found it', () => {
  const db = freshDb();
  runMigrations(db, MIGRATIONS_DIR);

  const insert = db.prepare(
    'INSERT INTO users (google_sub, email, display_name, username, username_key) VALUES (?, ?, ?, ?, ?)'
  );
  insert.run('sub-a', 'a@gmail.com', 'A', 'Poom', 'poom');

  // The case rule the old index carried, still carried.
  assert.throws(() => insert.run('sub-b', 'b@gmail.com', 'B', 'poom', 'poom'), /UNIQUE/);

  // And riders with no username still coexist, because SQLite ignores NULLs
  // in a unique index.
  insert.run('sub-c', 'c@gmail.com', 'C', null, null);
  insert.run('sub-d', 'd@gmail.com', 'D', null, null);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM users').get().n, 3);
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
