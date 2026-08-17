import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { validatePositionInput, POSITION_RATE_LIMIT } from '../src/routes/positions.js';

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

test('both position routes carry the username the app prefers to show', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();
  db.prepare('UPDATE users SET username = ? WHERE id = ?').run('speedy', memberId);

  const posted = await post(app, tripId, memberToken, { lat: 18.79, lng: 98.98 });
  assert.equal(posted.body.username, 'speedy');
  assert.equal(posted.body.display_name, 'Member');

  const list = await get(app, tripId, memberToken);
  const mine = list.body.find((p) => p.user_id === memberId);
  assert.equal(mine.username, 'speedy');

  // Present and null for a rider who has not chosen one, so a client can tell
  // "not set" from "this build doesn't send it".
  const other = list.body.find((p) => p.user_id !== memberId);
  assert.ok('username' in other);
  assert.equal(other.username, null);
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

test('validatePositionInput rejects every malformed field', () => {
  // Exercised directly rather than over HTTP: the full matrix is more
  // requests than the rate limit allows in one window, and the wiring
  // between the two is covered by the test below.
  const bad = (body, why) => {
    const { error, value } = validatePositionInput(body);
    assert.ok(error, `expected a rejection for ${why}`);
    assert.equal(value, undefined, `${why} must not produce a value`);
  };

  bad({ lng: 98.9 }, 'missing lat');
  bad({ lat: 18.7 }, 'missing lng');
  bad({ ...validFix, lat: 91 }, 'lat above 90');
  bad({ ...validFix, lat: -91 }, 'lat below -90');
  bad({ ...validFix, lng: 181 }, 'lng above 180');
  bad({ ...validFix, lng: -181 }, 'lng below -180');
  bad({ ...validFix, lat: ' 18.7 ' }, 'lat as string');
  bad({ ...validFix, lat: null }, 'lat null');
  bad(undefined, 'no body at all');

  bad({ ...validFix, timestamp: 'not-a-date' }, 'unparseable timestamp');
  bad({ ...validFix, timestamp: 1746086400000 }, 'timestamp as epoch number');
  bad({ ...validFix, timestamp: null }, 'timestamp null');

  bad({ ...validFix, accuracy: -1 }, 'negative accuracy');
  bad({ ...validFix, accuracy: 'high' }, 'accuracy as string');
  bad({ ...validFix, speed: -1 }, 'negative speed');
  bad({ ...validFix, heading: 361 }, 'heading above 360');
  bad({ ...validFix, heading: -1 }, 'negative heading');
  bad({ ...validFix, battery_pct: 101 }, 'battery above 100');
  bad({ ...validFix, battery_pct: -1 }, 'negative battery');
  bad({ ...validFix, battery_pct: 50.5 }, 'fractional battery');

  // Boundary values are valid, so these must NOT be rejected.
  const edge = validatePositionInput({
    lat: -90,
    lng: 180,
    accuracy: 0,
    speed: 0,
    heading: 360,
    battery_pct: 0,
  });
  assert.equal(edge.error, undefined);
  assert.equal(edge.value.heading, 360);
  assert.equal(edge.value.battery_pct, 0);
});

test('POST answers 400 for malformed input and writes nothing', async () => {
  const { app, db, tripId, memberToken } = setup();

  for (const [body, why] of [
    [{ lng: 98.9 }, 'missing lat'],
    [{ ...validFix, lat: 91 }, 'lat out of range'],
    [{ ...validFix, timestamp: 'not-a-date' }, 'unparseable timestamp'],
    [{ ...validFix, battery_pct: 101 }, 'battery out of range'],
  ]) {
    const res = await post(app, tripId, memberToken, body);
    assert.equal(res.status, 400, `expected 400 for ${why}, got ${res.status}`);
    assert.ok(res.body.error, 'and an error message the client can show');
  }

  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM member_positions').get().count, 0);

  // A valid fix on the same route still goes through.
  const edge = await post(app, tripId, memberToken, { lat: -90, lng: 180, heading: 360 });
  assert.equal(edge.status, 200);
  assert.equal(edge.body.heading, 360);
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

// ─── Lifetime kilometres ────────────────────────────────────────────────────

/** A fix `hours` after 08:00, `latDegrees` north of the starting point. */
const leg = (latDegrees, hours) => ({
  lat: 18.79 + latDegrees,
  lng: 98.98,
  timestamp: new Date(Date.parse('2026-05-01T08:00:00.000Z') + hours * 3600 * 1000).toISOString(),
});

const totalKmOf = (db, userId) =>
  db.prepare('SELECT total_km FROM users WHERE id = ?').get(userId).total_km;

test('riding adds to the rider lifetime total, one leg at a time', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  // The first fix has nothing to measure from.
  await post(app, tripId, memberToken, leg(0, 0));
  assert.equal(totalKmOf(db, memberId), 0);

  // ~11 km north over 15 minutes (~44 km/h).
  await post(app, tripId, memberToken, leg(0.1, 0.25));
  const afterFirstLeg = totalKmOf(db, memberId);
  assert.ok(afterFirstLeg > 10 && afterFirstLeg < 12, `got ${afterFirstLeg}`);

  // Another leg of the same length accumulates rather than replacing.
  await post(app, tripId, memberToken, leg(0.2, 0.5));
  const afterSecondLeg = totalKmOf(db, memberId);
  assert.ok(
    Math.abs(afterSecondLeg - afterFirstLeg * 2) < 0.01,
    `expected roughly double, got ${afterSecondLeg}`
  );

  // And it shows up on the profile without any further work.
  const level = await supertest(app).get('/me/level').set('Authorization', `Bearer ${memberToken}`);
  assert.equal(level.body.total_km, Number(afterSecondLeg.toFixed(2)));
  assert.equal(level.body.level.name, 'Novice');
});

test('a GPS jump too fast to be real does not inflate the total', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await post(app, tripId, memberToken, leg(0, 0));
  // ~11 km in one second — a reacquired signal landing in the wrong place.
  await post(app, tripId, memberToken, leg(0.1, 1 / 3600));
  assert.equal(totalKmOf(db, memberId), 0, 'the jump must not be credited');

  // The fix is still stored, though: the map should show where the rider
  // actually is, even when the leg that got them there is not believable.
  const list = await get(app, tripId, memberToken);
  assert.ok(Math.abs(list.body.find((p) => p.user_id === memberId).lat - 18.89) < 0.001);

  // Riding on from there, plausibly, counts again from the new position.
  await post(app, tripId, memberToken, leg(0.2, 0.5));
  const after = totalKmOf(db, memberId);
  assert.ok(after > 10 && after < 12, `got ${after}`);
});

test('drift while parked does not creep the total upwards', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await post(app, tripId, memberToken, leg(0, 0));

  // As many drifting fixes as one rate-limit window allows, each a couple of
  // metres from the last. (distance.test.js runs a hundred of them straight
  // through the rule, where no limit applies.)
  for (let i = 1; i <= POSITION_RATE_LIMIT.max - 1; i += 1) {
    const res = await post(app, tripId, memberToken, {
      lat: 18.79 + i * 0.000018,
      lng: 98.98,
      timestamp: new Date(Date.parse('2026-05-01T08:00:00.000Z') + i * 10_000).toISOString(),
    });
    assert.equal(res.status, 200);
  }

  assert.equal(totalKmOf(db, memberId), 0, 'a parked bike rides nowhere');
});

test('a stale fix credits nothing, matching the position it fails to store', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  await post(app, tripId, memberToken, leg(0, 0));
  await post(app, tripId, memberToken, leg(0.1, 0.25));
  const afterRiding = totalKmOf(db, memberId);
  assert.ok(afterRiding > 10);

  // A backlog flush delivering an older fix: rejected by the upsert, so it
  // must not be paid for either.
  const stale = await post(app, tripId, memberToken, leg(0, 0.1));
  assert.equal(stale.status, 200);
  assert.equal(totalKmOf(db, memberId), afterRiding);

  // Nor does re-sending the fix already stored, which would otherwise be a
  // free way to buy distance by replaying the same leg.
  await post(app, tripId, memberToken, leg(0.1, 0.25));
  assert.equal(totalKmOf(db, memberId), afterRiding);
});

test('each rider is credited only for their own riding', async () => {
  const { app, db, tripId, ownerToken, memberToken, ownerId, memberId } = setup();

  await post(app, tripId, ownerToken, leg(0, 0));
  await post(app, tripId, memberToken, leg(0, 0));
  await post(app, tripId, ownerToken, leg(0.1, 0.25));

  assert.ok(totalKmOf(db, ownerId) > 10, 'the owner rode');
  assert.equal(totalKmOf(db, memberId), 0, 'the member did not');
});

test('distance is measured within a trip, not across two of them', async () => {
  const { app, db, tripId, ownerToken, ownerId } = setup();

  const otherTripId = Number(
    db.prepare("INSERT INTO trips (name, owner_id, status) VALUES ('Bangkok', ?, 'active')").run(ownerId)
      .lastInsertRowid
  );
  db.prepare('INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, ?)').run(
    otherTripId,
    ownerId,
    'owner'
  );

  await post(app, tripId, ownerToken, leg(0, 0));

  // First fix on a different trip, 700 km away. Measuring it against the
  // Chiang Mai trip's fix would credit a ride that never happened.
  const bangkok = await supertest(app)
    .post(`/trips/${otherTripId}/positions`)
    .set('Authorization', `Bearer ${ownerToken}`)
    .send({ lat: 13.75, lng: 100.5, timestamp: '2026-05-01T18:00:00.000Z' });
  assert.equal(bangkok.status, 200);

  assert.equal(totalKmOf(db, ownerId), 0);
});

// ─── Rate limiting ──────────────────────────────────────────────────────────

test('position reports are capped per minute, as a safety net against runaway clients', async () => {
  const { app, tripId, memberToken } = setup();

  for (let i = 1; i <= POSITION_RATE_LIMIT.max; i += 1) {
    const res = await post(app, tripId, memberToken, leg(i * 0.1, i * 0.25));
    assert.equal(res.status, 200, `report ${i} should be within the limit`);
  }

  const capped = await post(app, tripId, memberToken, validFix);
  assert.equal(capped.status, 429);
  assert.deepEqual(capped.body, { error: 'too many position updates' });
});

test('the cap is per rider, so riders behind one carrier NAT do not throttle each other', async () => {
  const { app, tripId, memberToken, ownerToken } = setup();
  const sharedIp = '203.0.113.50';

  const report = (token, i) =>
    supertest(app)
      .post(`/trips/${tripId}/positions`)
      .set('Authorization', `Bearer ${token}`)
      .set('X-Forwarded-For', sharedIp)
      .send(leg(i * 0.1, i * 0.25));

  // One rider burns their whole budget...
  for (let i = 1; i <= POSITION_RATE_LIMIT.max; i += 1) {
    assert.equal((await report(memberToken, i)).status, 200);
  }
  assert.equal((await report(memberToken, 99)).status, 429);

  // ...and their riding partner, on the same IP, is unaffected.
  for (let i = 1; i <= POSITION_RATE_LIMIT.max; i += 1) {
    assert.equal(
      (await report(ownerToken, i)).status,
      200,
      `the owner's report ${i} must not be charged to the member`
    );
  }
});

test('a rider at their reporting cap can still read, and use the rest of the API', async () => {
  const { app, tripId, memberToken } = setup();

  for (let i = 1; i <= POSITION_RATE_LIMIT.max; i += 1) {
    await post(app, tripId, memberToken, leg(i * 0.1, i * 0.25));
  }
  assert.equal((await post(app, tripId, memberToken, validFix)).status, 429);

  // The limiter is attached to POST /positions alone. Nothing else may share
  // its budget — the map screen especially, which polls the read side.
  for (let i = 1; i <= POSITION_RATE_LIMIT.max * 2; i += 1) {
    assert.equal((await get(app, tripId, memberToken)).status, 200, `read ${i} must not be capped`);
  }

  const authed = (path) => supertest(app).get(path).set('Authorization', `Bearer ${memberToken}`);
  assert.equal((await authed('/me')).status, 200);
  assert.equal((await authed('/me/level')).status, 200);
  assert.equal((await authed('/trips')).status, 200);
  assert.equal((await authed('/invites')).status, 200);
  assert.equal((await authed(`/trips/${tripId}/waypoints`)).status, 200);
  assert.equal(
    (
      await supertest(app)
        .post(`/trips/${tripId}/waypoints`)
        .set('Authorization', `Bearer ${memberToken}`)
        .send({ name: 'Still working', lat: 18.8, lng: 98.9, type: 'live' })
    ).status,
    201
  );
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

  // Nor credited any distance — no ride, no kilometres.
  assert.equal(db.prepare('SELECT total_km FROM users WHERE id = ?').get(memberId).total_km, 0);
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
