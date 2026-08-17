import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { validatePositionInput } from '../src/routes/positions.js';

const JWT_SECRET = 'test-secret';

/**
 * Builds an app with a trip owned by `owner`, with `member` also joined, and
 * `outsider` deliberately left out of the trip.
 */
function setup() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name, photo_url) VALUES (?, ?, ?, ?)'
  );
  const ownerId = Number(
    insertUser.run('sub-owner', 'owner@gmail.com', 'Owner', 'owner.jpg').lastInsertRowid
  );
  const memberId = Number(
    insertUser.run('sub-member', 'member@gmail.com', 'Member', 'member.jpg').lastInsertRowid
  );
  const outsiderId = Number(
    insertUser.run('sub-outsider', 'outsider@gmail.com', 'Outsider', null).lastInsertRowid
  );

  const tripId = Number(
    db
      .prepare("INSERT INTO trips (name, owner_id, status) VALUES ('Chiang Mai loop', ?, 'active')")
      .run(ownerId).lastInsertRowid
  );

  const insertMember = db.prepare(
    'INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, ?)'
  );
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

const post = (app, tripId, token, body) =>
  supertest(app)
    .post(`/trips/${tripId}/positions`)
    .set('Authorization', `Bearer ${token}`)
    .send(body);

const get = (app, tripId, token) =>
  supertest(app).get(`/trips/${tripId}/positions`).set('Authorization', `Bearer ${token}`);

const endTrip = (app, tripId, ownerToken) =>
  supertest(app).post(`/trips/${tripId}/end`).set('Authorization', `Bearer ${ownerToken}`).send();

const validFix = { lat: 18.79, lng: 98.98 };

// ─── POST ───────────────────────────────────────────────────────────────────

test('POST stores a rider position and GET reads it back', async () => {
  const { app, tripId, memberToken, memberId } = setup();

  const res = await post(app, tripId, memberToken, {
    lat: 18.79,
    lng: 98.98,
    timestamp: '2026-05-01T08:00:00.000Z',
    accuracy: 12.5,
    speed: 22.4,
    heading: 275.5,
    battery_pct: 84,
  });
  assert.equal(res.status, 200);
  assert.equal(res.body.user_id, memberId);
  assert.equal(res.body.display_name, 'Member');
  assert.equal(res.body.photo_url, 'member.jpg');
  assert.equal(res.body.role, 'member');
  assert.equal(res.body.is_sharing, true);
  assert.equal(res.body.lat, 18.79);
  assert.equal(res.body.lng, 98.98);
  assert.equal(res.body.accuracy, 12.5);
  assert.equal(res.body.speed, 22.4);
  assert.equal(res.body.heading, 275.5);
  assert.equal(res.body.battery_pct, 84);
  assert.equal(res.body.recorded_at, '2026-05-01T08:00:00.000Z');

  const list = await get(app, tripId, memberToken);
  assert.equal(list.status, 200);
  const mine = list.body.find((p) => p.user_id === memberId);
  assert.equal(mine.lat, 18.79);
  assert.equal(mine.recorded_at, '2026-05-01T08:00:00.000Z');
});

test('POST updates the rider in place instead of piling up rows', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await post(app, tripId, memberToken, { ...validFix, timestamp: '2026-05-01T08:00:00.000Z' });
  const second = await post(app, tripId, memberToken, {
    lat: 19.1,
    lng: 99.2,
    timestamp: '2026-05-01T09:00:00.000Z',
  });
  assert.equal(second.status, 200);
  assert.equal(second.body.lat, 19.1);

  const rows = db
    .prepare('SELECT * FROM member_positions WHERE trip_id = ? AND user_id = ?')
    .all(tripId, memberId);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].lat, 19.1);
  assert.equal(rows[0].recorded_at, '2026-05-01T09:00:00.000Z');
});

test('a fix older than the stored one is ignored, and the newer one is returned', async () => {
  const { app, tripId, memberToken } = setup();

  await post(app, tripId, memberToken, {
    lat: 19.1,
    lng: 99.2,
    timestamp: '2026-05-01T09:00:00.000Z',
  });

  // A retry or a flushed backlog delivering an older fix must not rewind the map.
  const stale = await post(app, tripId, memberToken, {
    lat: 18.0,
    lng: 98.0,
    timestamp: '2026-05-01T08:00:00.000Z',
  });
  assert.equal(stale.status, 200);
  assert.equal(stale.body.lat, 19.1, 'the newer fix must survive');
  assert.equal(stale.body.recorded_at, '2026-05-01T09:00:00.000Z');

  // Re-sending the same timestamp is still allowed to overwrite, so a
  // corrected fix for the same instant isn't stuck.
  const sameInstant = await post(app, tripId, memberToken, {
    lat: 19.5,
    lng: 99.5,
    timestamp: '2026-05-01T09:00:00.000Z',
  });
  assert.equal(sameInstant.body.lat, 19.5);
});

test('timestamp is optional and offsets are normalized to UTC', async () => {
  const { app, tripId, memberToken, ownerToken } = setup();

  const before = new Date().toISOString();
  const noTimestamp = await post(app, tripId, memberToken, validFix);
  assert.equal(noTimestamp.status, 200);
  assert.ok(noTimestamp.body.recorded_at >= before, 'defaults to now');

  // Bangkok time, sent with an offset rather than as UTC.
  const withOffset = await post(app, tripId, ownerToken, {
    ...validFix,
    timestamp: '2026-05-01T15:00:00.000+07:00',
  });
  assert.equal(withOffset.body.recorded_at, '2026-05-01T08:00:00.000Z');
});

test('POST rejects malformed input', async () => {
  const { app, db, tripId, memberToken } = setup();
  const bad = async (body, why) => {
    const res = await post(app, tripId, memberToken, body);
    assert.equal(res.status, 400, `expected 400 for ${why}, got ${res.status}`);
  };

  await bad({ lng: 98.9 }, 'missing lat');
  await bad({ lat: 18.7 }, 'missing lng');
  await bad({ ...validFix, lat: 91 }, 'lat above 90');
  await bad({ ...validFix, lat: -91 }, 'lat below -90');
  await bad({ ...validFix, lng: 181 }, 'lng above 180');
  await bad({ ...validFix, lng: -181 }, 'lng below -180');
  await bad({ ...validFix, lat: ' 18.7 ' }, 'lat as string');
  await bad({ ...validFix, lat: null }, 'lat null');

  await bad({ ...validFix, timestamp: 'not-a-date' }, 'unparseable timestamp');
  await bad({ ...validFix, timestamp: 1746086400000 }, 'timestamp as epoch number');
  await bad({ ...validFix, timestamp: null }, 'timestamp null');

  await bad({ ...validFix, accuracy: -1 }, 'negative accuracy');
  await bad({ ...validFix, accuracy: 'high' }, 'accuracy as string');
  await bad({ ...validFix, speed: -1 }, 'negative speed');
  await bad({ ...validFix, heading: 361 }, 'heading above 360');
  await bad({ ...validFix, heading: -1 }, 'negative heading');
  await bad({ ...validFix, battery_pct: 101 }, 'battery above 100');
  await bad({ ...validFix, battery_pct: -1 }, 'negative battery');
  await bad({ ...validFix, battery_pct: 50.5 }, 'fractional battery');

  // Nothing rejected may have been written.
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM member_positions').get().count, 0);

  // Boundary values are valid, so these must NOT be rejected.
  const edge = await post(app, tripId, memberToken, {
    lat: -90,
    lng: 180,
    accuracy: 0,
    speed: 0,
    heading: 360,
    battery_pct: 0,
  });
  assert.equal(edge.status, 200);
  assert.equal(edge.body.heading, 360);
  assert.equal(edge.body.battery_pct, 0);
});

test('validatePositionInput leaves omitted optional fields null', () => {
  const now = new Date('2026-05-01T08:00:00.000Z');
  const { value, error } = validatePositionInput({ lat: 18.7, lng: 98.9 }, now);

  assert.equal(error, undefined);
  assert.deepEqual(value, {
    lat: 18.7,
    lng: 98.9,
    accuracy: null,
    speed: null,
    heading: null,
    battery_pct: null,
    recorded_at: '2026-05-01T08:00:00.000Z',
  });
});

// ─── GET ────────────────────────────────────────────────────────────────────

test('GET returns every member, freshest first, with non-reporters last', async () => {
  const { app, tripId, ownerToken, memberToken, ownerId, memberId } = setup();

  await post(app, tripId, ownerToken, { ...validFix, timestamp: '2026-05-01T08:00:00.000Z' });
  await post(app, tripId, memberToken, {
    lat: 19.1,
    lng: 99.2,
    timestamp: '2026-05-01T09:00:00.000Z',
  });

  const res = await get(app, tripId, ownerToken);
  assert.equal(res.status, 200);
  assert.deepEqual(
    res.body.map((p) => p.user_id),
    [memberId, ownerId],
    'the most recent fix comes first'
  );
  assert.deepEqual(
    res.body.map((p) => p.display_name),
    ['Member', 'Owner']
  );
});

test('a member who has never reported is still listed, with null position fields', async () => {
  const { app, tripId, ownerToken, memberId } = setup();

  await post(app, tripId, ownerToken, validFix);

  const res = await get(app, tripId, ownerToken);
  assert.equal(res.body.length, 2, 'both members are listed');

  const silent = res.body.find((p) => p.user_id === memberId);
  assert.equal(silent.display_name, 'Member');
  assert.equal(silent.role, 'member');
  assert.equal(silent.is_sharing, true);
  assert.equal(silent.lat, null);
  assert.equal(silent.lng, null);
  assert.equal(silent.recorded_at, null);

  // Never-reported riders sort last, behind everyone with a fix.
  assert.equal(res.body[res.body.length - 1].user_id, memberId);
});

test("GET does not leak another trip's positions", async () => {
  const { app, db, tripId, ownerToken, ownerId, memberToken, memberId } = setup();

  const otherTripId = Number(
    db.prepare("INSERT INTO trips (name, owner_id, status) VALUES ('Other', ?, 'active')").run(ownerId)
      .lastInsertRowid
  );
  db.prepare('INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, ?)').run(
    otherTripId,
    ownerId,
    'owner'
  );

  await post(app, otherTripId, ownerToken, { lat: 13.75, lng: 100.5 });
  await post(app, tripId, memberToken, validFix);

  const res = await get(app, tripId, ownerToken);
  const owner = res.body.find((p) => p.user_id === ownerId);
  assert.equal(owner.lat, null, "the owner's fix on the other trip must not show up here");
  assert.equal(res.body.find((p) => p.user_id === memberId).lat, 18.79);
});

// ─── Membership ─────────────────────────────────────────────────────────────

test('a non-member gets 403 on both routes and cannot write', async () => {
  const { app, db, tripId, outsiderToken, outsiderId } = setup();

  const write = await post(app, tripId, outsiderToken, validFix);
  assert.equal(write.status, 403);

  const read = await get(app, tripId, outsiderToken);
  assert.equal(read.status, 403);

  assert.equal(
    db.prepare('SELECT COUNT(*) AS count FROM member_positions WHERE user_id = ?').get(outsiderId)
      .count,
    0
  );
});

test('position routes require authentication', async () => {
  const { app, tripId } = setup();

  assert.equal((await supertest(app).post(`/trips/${tripId}/positions`).send(validFix)).status, 401);
  assert.equal((await supertest(app).get(`/trips/${tripId}/positions`)).status, 401);
});

test('unknown trip returns 404, malformed trip id returns 400', async () => {
  const { app, memberToken } = setup();

  assert.equal((await get(app, 9999, memberToken)).status, 404);
  assert.equal((await get(app, 'abc', memberToken)).status, 400);
  assert.equal((await post(app, 9999, memberToken, validFix)).status, 404);
  assert.equal((await post(app, 'abc', memberToken, validFix)).status, 400);
});

// ─── Ended trips ────────────────────────────────────────────────────────────

test('an ended trip refuses new positions', async () => {
  const { app, db, tripId, ownerToken, memberToken, memberId } = setup();

  const before = await post(app, tripId, memberToken, {
    ...validFix,
    timestamp: '2026-05-01T08:00:00.000Z',
  });
  assert.equal(before.status, 200);

  assert.equal((await endTrip(app, tripId, ownerToken)).status, 200);

  // This is the whole reason positions sit behind requireActiveTrip: a
  // finished ride stops taking location updates from its members.
  const write = await post(app, tripId, memberToken, {
    lat: 19.9,
    lng: 99.9,
    timestamp: '2026-05-01T10:00:00.000Z',
  });
  assert.equal(write.status, 409);
  assert.equal(write.body.error, 'trip has ended');

  // The owner is no more privileged here than anyone else.
  assert.equal((await post(app, tripId, ownerToken, validFix)).status, 409);

  // The rejected writes must not have changed the stored fix.
  const stored = db
    .prepare('SELECT * FROM member_positions WHERE trip_id = ? AND user_id = ?')
    .get(tripId, memberId);
  assert.equal(stored.lat, 18.79);
  assert.equal(stored.recorded_at, '2026-05-01T08:00:00.000Z');
});

test('an ended trip stays readable, so a trip summary can show where everyone finished', async () => {
  const { app, tripId, ownerToken, memberToken, memberId, ownerId } = setup();

  await post(app, tripId, ownerToken, { ...validFix, timestamp: '2026-05-01T08:00:00.000Z' });
  await post(app, tripId, memberToken, {
    lat: 19.1,
    lng: 99.2,
    timestamp: '2026-05-01T09:00:00.000Z',
    battery_pct: 41,
  });

  await endTrip(app, tripId, ownerToken);

  // Reads stay open after the trip ends, matching waypoints.
  for (const [who, token] of [
    ['owner', ownerToken],
    ['member', memberToken],
  ]) {
    const res = await get(app, tripId, token);
    assert.equal(res.status, 200, `${who} should still be able to read positions`);
    assert.deepEqual(
      res.body.map((p) => p.user_id),
      [memberId, ownerId]
    );
  }

  // The final fixes are intact, not blanked out.
  const res = await get(app, tripId, ownerToken);
  const last = res.body.find((p) => p.user_id === memberId);
  assert.equal(last.lat, 19.1);
  assert.equal(last.lng, 99.2);
  assert.equal(last.battery_pct, 41);
  assert.equal(last.recorded_at, '2026-05-01T09:00:00.000Z');
});

test('a non-member of an ended trip still gets 403, on reads and writes alike', async () => {
  const { app, tripId, ownerToken, outsiderToken } = setup();
  await endTrip(app, tripId, ownerToken);

  // Membership is checked before trip status, so an outsider learns nothing
  // about the trip beyond that they are not in it — and ending a trip does
  // not make it public.
  assert.equal((await get(app, tripId, outsiderToken)).status, 403);
  assert.equal((await post(app, tripId, outsiderToken, validFix)).status, 403);
});

test('ending one trip does not close positions on another', async () => {
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

  await endTrip(app, tripId, ownerToken);

  assert.equal((await post(app, otherTripId, ownerToken, validFix)).status, 200);
  assert.equal((await get(app, otherTripId, ownerToken)).status, 200);
});
