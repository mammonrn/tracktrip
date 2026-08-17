import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { SHARING_DURATION_MINUTES } from '../src/trips/sharing.js';

const JWT_SECRET = 'test-secret';

/**
 * A trip with an owner and a member, plus an outsider. The trip is created
 * directly so the tests can end it without spending rate-limit budget.
 */
function setup() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare('INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)');
  const ownerId = Number(insertUser.run('sub-owner', 'owner@gmail.com', 'Owner').lastInsertRowid);
  const memberId = Number(insertUser.run('sub-member', 'member@gmail.com', 'Member').lastInsertRowid);
  const outsiderId = Number(
    insertUser.run('sub-outsider', 'outsider@gmail.com', 'Outsider').lastInsertRowid
  );

  const tripId = Number(
    db
      .prepare("INSERT INTO trips (name, owner_id, status) VALUES ('Chiang Mai loop', ?, 'active')")
      .run(ownerId).lastInsertRowid
  );
  const insertMember = db.prepare('INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, ?)');
  insertMember.run(tripId, ownerId, 'owner');
  insertMember.run(tripId, memberId, 'member');

  const config = { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'] };
  const app = createApp({
    db,
    config,
    verifyGoogleIdToken: async () => {
      throw new Error('unused in these tests');
    },
  });

  const tokenFor = (id) => signAccessToken(id, JWT_SECRET);
  return {
    db,
    app,
    tripId,
    ownerId,
    memberId,
    outsiderId,
    ownerToken: tokenFor(ownerId),
    memberToken: tokenFor(memberId),
    outsiderToken: tokenFor(outsiderId),
  };
}

const startSharing = (app, tripId, token, body) =>
  supertest(app)
    .post(`/trips/${tripId}/share/start`)
    .set('Authorization', `Bearer ${token}`)
    .send(body);

const stopSharing = (app, tripId, token) =>
  supertest(app).post(`/trips/${tripId}/share/stop`).set('Authorization', `Bearer ${token}`).send();

const report = (app, tripId, token, body = { lat: 18.79, lng: 98.98 }) =>
  supertest(app)
    .post(`/trips/${tripId}/positions`)
    .set('Authorization', `Bearer ${token}`)
    .send(body);

const readPositions = (app, tripId, token) =>
  supertest(app).get(`/trips/${tripId}/positions`).set('Authorization', `Bearer ${token}`);

const endTripAsOwner = (app, tripId, ownerToken) =>
  supertest(app).post(`/trips/${tripId}/end`).set('Authorization', `Bearer ${ownerToken}`).send();

/** Rewinds a rider's session so it has already lapsed. */
const expireSession = (db, tripId, userId) =>
  db
    .prepare('UPDATE sharing_sessions SET expires_at = ? WHERE trip_id = ? AND user_id = ?')
    .run('2020-01-01T00:00:00.000Z', tripId, userId);

// ─── Sharing is on by default ───────────────────────────────────────────────

test('a rider with no session shares by default for the whole trip', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  assert.equal((await report(app, tripId, memberToken)).status, 200);
  assert.equal(
    db.prepare('SELECT COUNT(*) AS count FROM sharing_sessions').get().count,
    0,
    'reporting must not need a session'
  );

  const me = (await readPositions(app, tripId, memberToken)).body.find(
    (m) => m.user_id === memberId
  );
  assert.equal(me.is_sharing, true);
  assert.equal(me.sharing_until, null);
});

// ─── Pausing mid-trip ───────────────────────────────────────────────────────

test('stopping pauses a rider who never touched the controls', async () => {
  const { app, tripId, memberToken, memberId } = setup();

  assert.equal((await report(app, tripId, memberToken)).status, 200);

  const stopped = await stopSharing(app, tripId, memberToken);
  assert.equal(stopped.status, 200);
  assert.equal(stopped.body.sharing, false);

  // The pause has to bite: a stop that left reports flowing would be a
  // button that does nothing.
  const blocked = await report(app, tripId, memberToken, { lat: 19.5, lng: 99.5 });
  assert.equal(blocked.status, 409);
  assert.equal(blocked.body.error, 'your sharing session has ended');

  const me = (await readPositions(app, tripId, memberToken)).body.find(
    (m) => m.user_id === memberId
  );
  assert.equal(me.is_sharing, false, 'and the group can see they have gone dark');
});

test('stopping does not delete the session, which would switch sharing back on', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await stopSharing(app, tripId, memberToken);

  // No row is how a rider who never paused looks, and that reads as sharing.
  // A stop therefore has to leave a trace behind.
  const row = db
    .prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
    .get(tripId, memberId);
  assert.ok(row, 'the pause is recorded');
  assert.ok(row.expires_at <= new Date().toISOString(), 'as a session already spent');
});

test('stopping twice reports the second as a no-op', async () => {
  const { app, tripId, memberToken } = setup();

  assert.equal((await stopSharing(app, tripId, memberToken)).status, 200);
  assert.equal((await stopSharing(app, tripId, memberToken)).status, 409);
});

test('starting again resumes a paused rider', async () => {
  const { app, tripId, memberToken } = setup();

  await stopSharing(app, tripId, memberToken);
  assert.equal((await report(app, tripId, memberToken)).status, 409);

  const resumed = await startSharing(app, tripId, memberToken, { duration_minutes: 60 });
  assert.equal(resumed.status, 200);
  assert.equal(resumed.body.sharing, true);
  assert.equal((await report(app, tripId, memberToken, { lat: 19.1, lng: 99.1 })).status, 200);
});

test('a session that runs out pauses the rider just as a stop would', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 30 });
  assert.equal((await report(app, tripId, memberToken)).status, 200);

  expireSession(db, tripId, memberId);

  const lapsed = await report(app, tripId, memberToken, { lat: 19.5, lng: 99.5 });
  assert.equal(lapsed.status, 409);
  assert.equal(lapsed.body.error, 'your sharing session has ended');
});

test('one rider pausing leaves everyone else sharing', async () => {
  const { app, tripId, ownerToken, memberToken, memberId, ownerId } = setup();

  await stopSharing(app, tripId, memberToken);

  assert.equal((await report(app, tripId, ownerToken)).status, 200);
  assert.equal((await report(app, tripId, memberToken)).status, 409);

  const rows = (await readPositions(app, tripId, ownerToken)).body;
  assert.equal(rows.find((m) => m.user_id === ownerId).is_sharing, true);
  assert.equal(rows.find((m) => m.user_id === memberId).is_sharing, false);
});

// ─── Starting ───────────────────────────────────────────────────────────────

test('starting sharing with a duration sets an expiry that far ahead', async () => {
  const { app, tripId, memberToken } = setup();

  for (const minutes of SHARING_DURATION_MINUTES) {
    const before = Date.now();
    const res = await startSharing(app, tripId, memberToken, { duration_minutes: minutes });
    assert.equal(res.status, 200, `duration ${minutes} should be accepted`);
    assert.equal(res.body.sharing, true);

    const expiresAt = Date.parse(res.body.expires_at);
    assert.ok(
      Math.abs(expiresAt - (before + minutes * 60_000)) < 5_000,
      `expiry for ${minutes} minutes was ${res.body.expires_at}`
    );
  }
});

test('starting with a null duration shares until stopped', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  const res = await startSharing(app, tripId, memberToken, { duration_minutes: null });
  assert.equal(res.status, 200);
  assert.equal(res.body.sharing, true);
  assert.equal(res.body.expires_at, null);

  assert.equal(
    db.prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
      .get(tripId, memberId).expires_at,
    null
  );
});

test('start rejects a duration that is not on offer', async () => {
  const { app, db, tripId, memberToken } = setup();
  const bad = async (body, why) => {
    const res = await startSharing(app, tripId, memberToken, body);
    assert.equal(res.status, 400, `expected 400 for ${why}, got ${res.status}`);
  };

  // Omitting the field is not the same as sending null: a client that forgot
  // it must not silently get an unlimited session.
  await bad({}, 'missing duration_minutes');
  await bad({ duration_minutes: 15 }, 'a duration not on the list');
  await bad({ duration_minutes: 0 }, 'zero');
  await bad({ duration_minutes: -30 }, 'negative');
  await bad({ duration_minutes: '30' }, 'string rather than number');
  await bad({ duration_minutes: 30.5 }, 'fractional');

  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM sharing_sessions').get().count, 0);
});

test('starting again replaces the session, so a rider can change their mind', async () => {
  const { app, db, tripId, memberToken } = setup();

  const first = await startSharing(app, tripId, memberToken, { duration_minutes: 30 });
  assert.ok(first.body.expires_at);

  const second = await startSharing(app, tripId, memberToken, { duration_minutes: null });
  assert.equal(second.body.expires_at, null, 'switched to sharing until stopped');

  assert.equal(
    db.prepare('SELECT COUNT(*) AS count FROM sharing_sessions WHERE trip_id = ?').get(tripId).count,
    1,
    'and did not pile up a second row'
  );
});

test('sharing routes need membership and authentication', async () => {
  const { app, tripId, outsiderToken } = setup();

  assert.equal((await startSharing(app, tripId, outsiderToken, { duration_minutes: 30 })).status, 403);
  assert.equal((await stopSharing(app, tripId, outsiderToken)).status, 403);

  assert.equal(
    (await supertest(app).post(`/trips/${tripId}/share/start`).send({ duration_minutes: 30 })).status,
    401
  );
  assert.equal((await supertest(app).post(`/trips/${tripId}/share/stop`)).status, 401);
});

test('sharing routes 404 an unknown trip and 400 a malformed id', async () => {
  const { app, memberToken } = setup();

  assert.equal((await startSharing(app, 9999, memberToken, { duration_minutes: 30 })).status, 404);
  assert.equal((await startSharing(app, 'abc', memberToken, { duration_minutes: 30 })).status, 400);
  assert.equal((await stopSharing(app, 9999, memberToken)).status, 404);
});

// ─── Ending the trip stops the whole group ──────────────────────────────────

test('ending a trip clears every sharing session in it', async () => {
  const { app, db, tripId, ownerToken, memberToken } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 240 });
  await startSharing(app, tripId, ownerToken, { duration_minutes: null });
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM sharing_sessions').get().count, 2);

  assert.equal((await endTripAsOwner(app, tripId, ownerToken)).status, 200);

  assert.equal(
    db.prepare('SELECT COUNT(*) AS count FROM sharing_sessions WHERE trip_id = ?').get(tripId).count,
    0,
    'ending the trip stops sharing for the whole group'
  );
});

test('nobody can report after the trip ends, whatever session they had', async () => {
  const { app, tripId, ownerToken, memberToken } = setup();

  // Every shape of session, including the one that used to survive.
  await startSharing(app, tripId, memberToken, { duration_minutes: null });
  await startSharing(app, tripId, ownerToken, { duration_minutes: 240 });

  await endTripAsOwner(app, tripId, ownerToken);

  for (const [who, token] of [
    ['the member who was sharing indefinitely', memberToken],
    ['the owner who had four hours left', ownerToken],
  ]) {
    const res = await report(app, tripId, token, { lat: 19.9, lng: 99.9 });
    assert.equal(res.status, 409, `${who} must be stopped`);
    assert.equal(res.body.error, 'trip has ended');
  }
});

test('a session left over from before cannot outlive the trip either', async () => {
  const { app, db, tripId, ownerToken, memberToken, memberId } = setup();

  await endTripAsOwner(app, tripId, ownerToken);

  // Forced straight into the table, standing in for a row that somehow
  // escaped the clear-out. The guard checks the trip first, so it changes
  // nothing.
  db.prepare(
    'INSERT INTO sharing_sessions (trip_id, user_id, started_at, expires_at) VALUES (?, ?, ?, NULL)'
  ).run(tripId, memberId, new Date().toISOString());

  const res = await report(app, tripId, memberToken);
  assert.equal(res.status, 409);
  assert.equal(res.body.error, 'trip has ended');
});

test('sharing cannot be started or stopped once the trip has ended', async () => {
  const { app, tripId, ownerToken, memberToken } = setup();

  await endTripAsOwner(app, tripId, ownerToken);

  const started = await startSharing(app, tripId, memberToken, { duration_minutes: 240 });
  assert.equal(started.status, 409);
  assert.equal(started.body.error, 'trip has ended');

  assert.equal((await stopSharing(app, tripId, memberToken)).status, 409);
});

test('after the trip ends nobody reads as sharing', async () => {
  const { app, tripId, ownerToken, memberToken } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 240 });
  await report(app, tripId, memberToken);
  await report(app, tripId, ownerToken);

  await endTripAsOwner(app, tripId, ownerToken);

  const rows = (await readPositions(app, tripId, ownerToken)).body;
  assert.equal(rows.length, 2, 'reads stay open on an ended trip');
  for (const member of rows) {
    assert.equal(member.is_sharing, false, `${member.display_name} must read as not sharing`);
    assert.equal(member.sharing_until, null);
    assert.ok(member.lat !== null, 'their last position is still on the map');
  }
});

test('ending one trip does not stop sharing on another', async () => {
  const { app, db, tripId, ownerToken, ownerId } = setup();

  const otherTripId = Number(
    db.prepare("INSERT INTO trips (name, owner_id, status) VALUES ('Still riding', ?, 'active')").run(
      ownerId
    ).lastInsertRowid
  );
  db.prepare('INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, ?)').run(
    otherTripId,
    ownerId,
    'owner'
  );
  await startSharing(app, otherTripId, ownerToken, { duration_minutes: 240 });

  await endTripAsOwner(app, tripId, ownerToken);

  assert.equal(
    db.prepare('SELECT COUNT(*) AS count FROM sharing_sessions WHERE trip_id = ?').get(otherTripId)
      .count,
    1
  );
  assert.equal((await report(app, otherTripId, ownerToken)).status, 200);
});

// ─── What the map sees ──────────────────────────────────────────────────────

test('POST positions answers with the reporter own sharing state', async () => {
  const { app, tripId, memberToken } = setup();

  const plain = await report(app, tripId, memberToken);
  assert.equal(plain.body.is_sharing, true);
  assert.equal(plain.body.sharing_until, null, 'no session, nothing to count down');

  await startSharing(app, tripId, memberToken, { duration_minutes: 60 });
  const timed = await report(app, tripId, memberToken, {
    lat: 19.1,
    lng: 99.1,
    timestamp: new Date(Date.now() + 1000).toISOString(),
  });
  assert.equal(timed.body.is_sharing, true);
  assert.ok(timed.body.sharing_until, 'and now there is');
});
