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

/** Ends the trip without going through the API, to keep tests independent. */
const endTrip = (db, tripId) =>
  db
    .prepare("UPDATE trips SET status = 'ended', ended_at = ? WHERE id = ?")
    .run(new Date().toISOString(), tripId);

/** Rewinds a rider's session so it has already lapsed. */
const expireSession = (db, tripId, userId) =>
  db
    .prepare('UPDATE sharing_sessions SET expires_at = ? WHERE trip_id = ? AND user_id = ?')
    .run('2020-01-01T00:00:00.000Z', tripId, userId);

// ─── Starting and stopping ──────────────────────────────────────────────────

test('starting sharing with a duration sets an expiry that far ahead', async () => {
  const { app, tripId, memberToken } = setup();

  for (const minutes of SHARING_DURATION_MINUTES) {
    const before = Date.now();
    const res = await startSharing(app, tripId, memberToken, { duration_minutes: minutes });
    assert.equal(res.status, 200, `duration ${minutes} should be accepted`);
    assert.equal(res.body.sharing, true);
    assert.ok(res.body.started_at);

    const expiresAt = Date.parse(res.body.expires_at);
    const expected = before + minutes * 60_000;
    assert.ok(
      Math.abs(expiresAt - expected) < 5_000,
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

  const row = db
    .prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
    .get(tripId, memberId);
  assert.equal(row.expires_at, null);
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
  const { app, db, tripId, memberToken, memberId } = setup();

  const first = await startSharing(app, tripId, memberToken, { duration_minutes: 30 });
  assert.ok(first.body.expires_at);

  const second = await startSharing(app, tripId, memberToken, { duration_minutes: null });
  assert.equal(second.status, 200);
  assert.equal(second.body.expires_at, null, 'switched to sharing until stopped');

  assert.equal(
    db.prepare('SELECT COUNT(*) AS count FROM sharing_sessions WHERE trip_id = ?').get(tripId).count,
    1,
    'and did not pile up a second row'
  );
});

test('stopping clears the session, and stopping twice reports the second as a no-op', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 60 });

  const stopped = await stopSharing(app, tripId, memberToken);
  assert.equal(stopped.status, 200);
  assert.equal(stopped.body.sharing, false);
  assert.equal(stopped.body.expires_at, null);
  assert.equal(
    db.prepare('SELECT COUNT(*) AS count FROM sharing_sessions WHERE user_id = ?').get(memberId).count,
    0
  );

  const again = await stopSharing(app, tripId, memberToken);
  assert.equal(again.status, 409);
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

// ─── Riding on after the trip ends ──────────────────────────────────────────

test('a rider who chose to carry on keeps reporting after the trip ends', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  assert.equal((await report(app, tripId, memberToken)).status, 200);
  assert.equal(
    (await startSharing(app, tripId, memberToken, { duration_minutes: 240 })).status,
    200
  );

  endTrip(db, tripId);

  // This is the whole change: the trip is over, the group has broken up, and
  // this rider is still on the road.
  const after = await report(app, tripId, memberToken, { lat: 19.2, lng: 99.3 });
  assert.equal(after.status, 200);
  assert.equal(after.body.lat, 19.2);

  const stored = db
    .prepare('SELECT lat FROM member_positions WHERE trip_id = ? AND user_id = ?')
    .get(tripId, memberId);
  assert.equal(stored.lat, 19.2);
});

test('sharing until stopped survives the trip ending, with no expiry to reach', async () => {
  const { app, db, tripId, memberToken } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: null });
  endTrip(db, tripId);

  assert.equal((await report(app, tripId, memberToken, { lat: 19.1, lng: 99.1 })).status, 200);

  // ...until they stop by hand, after which the trip's ended state applies again.
  assert.equal((await stopSharing(app, tripId, memberToken)).status, 200);
  const blocked = await report(app, tripId, memberToken, { lat: 19.4, lng: 99.4 });
  assert.equal(blocked.status, 409);
  assert.equal(blocked.body.error, 'trip has ended');
});

test('a rider who never opted in is blocked exactly as before', async () => {
  const { app, db, tripId, memberToken, ownerToken } = setup();

  // The member carries on; the owner goes home without pressing anything.
  await startSharing(app, tripId, memberToken, { duration_minutes: 240 });
  endTrip(db, tripId);

  assert.equal((await report(app, tripId, memberToken)).status, 200);

  const owner = await report(app, tripId, ownerToken);
  assert.equal(owner.status, 409);
  assert.equal(owner.body.error, 'trip has ended');
});

test('an expired session stops reporting, and says so distinctly', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 30 });
  endTrip(db, tripId);
  assert.equal((await report(app, tripId, memberToken)).status, 200, 'still inside the window');

  expireSession(db, tripId, memberId);

  const lapsed = await report(app, tripId, memberToken, { lat: 19.5, lng: 99.5 });
  assert.equal(lapsed.status, 409);
  // Distinct from "trip has ended": the rider did opt in, their time ran out.
  assert.equal(lapsed.body.error, 'your sharing session has ended');
});

test('an expired session is no obstacle while the trip is still running', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 30 });
  expireSession(db, tripId, memberId);

  // A live trip needs no session at all, so a stale one must not get in the way.
  assert.equal((await report(app, tripId, memberToken)).status, 200);
});

test('ending a trip leaves every session untouched, to run out on its own', async () => {
  const { app, db, tripId, ownerToken, memberToken, memberId } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 60 });
  const before = db
    .prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
    .get(tripId, memberId);

  // Through the real endpoint, so it is the owner's action being tested.
  assert.equal(
    (await supertest(app).post(`/trips/${tripId}/end`).set('Authorization', `Bearer ${ownerToken}`))
      .status,
    200
  );

  const after = db
    .prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
    .get(tripId, memberId);
  assert.deepEqual(after, before, 'ending a trip must not stop anyone sharing');
});

test('sharing can be switched on after the trip has already ended', async () => {
  const { app, db, tripId, memberToken } = setup();

  endTrip(db, tripId);
  assert.equal((await report(app, tripId, memberToken)).status, 409);

  // Forgot to press it before the owner ended the trip — still allowed.
  const started = await startSharing(app, tripId, memberToken, { duration_minutes: 60 });
  assert.equal(started.status, 200);
  assert.equal((await report(app, tripId, memberToken, { lat: 19.7, lng: 99.7 })).status, 200);
});

test('distance still accrues for a rider carrying on alone', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 240 });
  await report(app, tripId, memberToken, {
    lat: 18.79,
    lng: 98.98,
    timestamp: '2026-05-01T08:00:00.000Z',
  });
  endTrip(db, tripId);

  await report(app, tripId, memberToken, {
    lat: 18.89,
    lng: 98.98,
    timestamp: '2026-05-01T08:15:00.000Z',
  });

  const totalKm = db.prepare('SELECT total_km FROM users WHERE id = ?').get(memberId).total_km;
  assert.ok(totalKm > 10 && totalKm < 12, `expected ~11 km, got ${totalKm}`);
});

// ─── What the map sees ──────────────────────────────────────────────────────

test('GET positions reports everyone as sharing while the trip runs', async () => {
  const { app, tripId, ownerToken, memberToken } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 60 });

  const res = await readPositions(app, tripId, ownerToken);
  assert.equal(res.status, 200);
  for (const member of res.body) {
    assert.equal(member.is_sharing, true, 'a running trip needs no session from anyone');
  }

  // The member's own expiry is still reported, so a client can show it.
  const withSession = res.body.find((m) => m.sharing_until !== null);
  assert.ok(withSession, 'the rider who set a timer has an expiry to show');
});

test('after the trip ends, only riders still sharing read as sharing', async () => {
  const { app, db, tripId, ownerToken, memberToken, memberId, ownerId } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 240 });
  endTrip(db, tripId);

  const res = await readPositions(app, tripId, ownerToken);
  assert.equal(res.status, 200, 'reads stay open on an ended trip');

  const member = res.body.find((m) => m.user_id === memberId);
  const owner = res.body.find((m) => m.user_id === ownerId);
  assert.equal(member.is_sharing, true);
  assert.ok(member.sharing_until, 'and shows when they will drop off');
  assert.equal(owner.is_sharing, false);
  assert.equal(owner.sharing_until, null);

  // Once the member's time is up, the map agrees with the write guard.
  expireSession(db, tripId, memberId);
  const later = await readPositions(app, tripId, ownerToken);
  assert.equal(later.body.find((m) => m.user_id === memberId).is_sharing, false);
});

test('POST positions answers with the reporter own sharing state', async () => {
  const { app, db, tripId, memberToken } = setup();

  await startSharing(app, tripId, memberToken, { duration_minutes: 60 });
  endTrip(db, tripId);

  const res = await report(app, tripId, memberToken);
  assert.equal(res.status, 200);
  assert.equal(res.body.is_sharing, true);
  assert.ok(res.body.sharing_until);
});
