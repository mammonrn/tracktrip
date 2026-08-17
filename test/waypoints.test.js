import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';

const JWT_SECRET = 'test-secret';

/**
 * Builds an app with a trip owned by `owner`, with `member` also joined, and
 * `outsider` deliberately left out of the trip.
 */
function setup() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare('INSERT INTO users (google_sub, display_name) VALUES (?, ?)');
  const ownerId = Number(insertUser.run('sub-owner', 'Owner').lastInsertRowid);
  const memberId = Number(insertUser.run('sub-member', 'Member').lastInsertRowid);
  const outsiderId = Number(insertUser.run('sub-outsider', 'Outsider').lastInsertRowid);

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

function post(app, tripId, token, body) {
  return supertest(app)
    .post(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${token}`)
    .send(body);
}

const validPlanned = { name: 'Gas stop', lat: 18.79, lng: 98.98, type: 'planned', order_index: 0 };

test('POST creates a planned waypoint and GET returns it', async () => {
  const { app, tripId, memberToken, memberId } = setup();

  const created = await post(app, tripId, memberToken, validPlanned);
  assert.equal(created.status, 201);
  assert.equal(created.body.name, 'Gas stop');
  assert.equal(created.body.type, 'planned');
  assert.equal(created.body.order_index, 0);
  assert.equal(created.body.added_by, memberId);

  const list = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(list.status, 200);
  assert.equal(list.body.planned.length, 1);
  assert.equal(list.body.live.length, 0);
});

test('a live waypoint stores a null order_index and lands in the live group', async () => {
  const { app, tripId, memberToken } = setup();

  const created = await post(app, tripId, memberToken, {
    name: 'Nice viewpoint',
    lat: 18.8,
    lng: 98.9,
    type: 'live',
  });
  assert.equal(created.status, 201);
  assert.equal(created.body.order_index, null);

  const list = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(list.body.live.length, 1);
  assert.equal(list.body.planned.length, 0);
});

test('planned waypoints come back ordered by order_index, not insertion order', async () => {
  const { app, tripId, memberToken } = setup();

  // Inserted deliberately out of order.
  await post(app, tripId, memberToken, { ...validPlanned, name: 'Third', order_index: 2 });
  await post(app, tripId, memberToken, { ...validPlanned, name: 'First', order_index: 0 });
  await post(app, tripId, memberToken, { ...validPlanned, name: 'Second', order_index: 1 });

  const list = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.deepEqual(
    list.body.planned.map((w) => w.name),
    ['First', 'Second', 'Third']
  );
});

test('live waypoints come back in chronological order', async () => {
  const { app, db, tripId, memberToken, memberId } = setup();

  // created_at defaults to now for all three, which can collide within the
  // same millisecond, so write explicit timestamps out of order.
  const insert = db.prepare(
    `INSERT INTO trip_waypoints (trip_id, name, lat, lng, type, order_index, added_by, created_at)
     VALUES (?, ?, 18.8, 98.9, 'live', NULL, ?, ?)`
  );
  insert.run(tripId, 'Later', memberId, '2026-05-01T12:00:00.000Z');
  insert.run(tripId, 'Earliest', memberId, '2026-05-01T08:00:00.000Z');
  insert.run(tripId, 'Middle', memberId, '2026-05-01T10:00:00.000Z');

  const list = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.deepEqual(
    list.body.live.map((w) => w.name),
    ['Earliest', 'Middle', 'Later']
  );
});

test('POST rejects malformed input', async () => {
  const { app, tripId, memberToken } = setup();
  const bad = async (body, why) => {
    const res = await post(app, tripId, memberToken, body);
    assert.equal(res.status, 400, `expected 400 for ${why}, got ${res.status}`);
  };

  await bad({ ...validPlanned, name: '' }, 'empty name');
  await bad({ ...validPlanned, name: '   ' }, 'whitespace-only name');
  await bad({ ...validPlanned, name: 'x'.repeat(61) }, 'name over 60 chars');
  await bad({ ...validPlanned, name: 123 }, 'non-string name');
  await bad({ lat: 18.7, lng: 98.9, type: 'planned', order_index: 0 }, 'missing name');

  await bad({ ...validPlanned, lat: 91 }, 'lat above 90');
  await bad({ ...validPlanned, lat: -91 }, 'lat below -90');
  await bad({ ...validPlanned, lng: 181 }, 'lng above 180');
  await bad({ ...validPlanned, lng: -181 }, 'lng below -180');
  await bad({ ...validPlanned, lat: ' 18.7 ' }, 'lat as string');
  await bad({ ...validPlanned, lat: null }, 'lat null');

  await bad({ ...validPlanned, type: 'bogus' }, 'unknown type');
  await bad({ ...validPlanned, type: undefined }, 'missing type');

  // Boundary values are valid, so these must NOT be rejected.
  const edge = await post(app, tripId, memberToken, {
    ...validPlanned,
    lat: -90,
    lng: 180,
    name: 'x'.repeat(60),
  });
  assert.equal(edge.status, 201);
});

test('order_index rules: required for planned, forbidden for live', async () => {
  const { app, tripId, memberToken } = setup();

  const plannedNoIndex = await post(app, tripId, memberToken, {
    name: 'Planned',
    lat: 18.7,
    lng: 98.9,
    type: 'planned',
  });
  assert.equal(plannedNoIndex.status, 400);

  const plannedBadIndex = await post(app, tripId, memberToken, {
    ...validPlanned,
    order_index: 1.5,
  });
  assert.equal(plannedBadIndex.status, 400);

  const plannedNegativeIndex = await post(app, tripId, memberToken, {
    ...validPlanned,
    order_index: -1,
  });
  assert.equal(plannedNegativeIndex.status, 400);

  const liveWithIndex = await post(app, tripId, memberToken, {
    name: 'Live',
    lat: 18.7,
    lng: 98.9,
    type: 'live',
    order_index: 0,
  });
  assert.equal(liveWithIndex.status, 400);

  // Explicit null still counts as sending the field.
  const liveWithNullIndex = await post(app, tripId, memberToken, {
    name: 'Live',
    lat: 18.7,
    lng: 98.9,
    type: 'live',
    order_index: null,
  });
  assert.equal(liveWithNullIndex.status, 400);
});

test('a non-member gets 403 on every waypoint route, and cannot see or change data', async () => {
  const { app, tripId, memberToken, outsiderToken } = setup();

  const created = await post(app, tripId, memberToken, validPlanned);
  const waypointId = created.body.id;

  const outsiderPost = await post(app, tripId, outsiderToken, validPlanned);
  assert.equal(outsiderPost.status, 403);

  const outsiderGet = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${outsiderToken}`);
  assert.equal(outsiderGet.status, 403);

  const outsiderDelete = await supertest(app)
    .delete(`/trips/${tripId}/waypoints/${waypointId}`)
    .set('Authorization', `Bearer ${outsiderToken}`);
  assert.equal(outsiderDelete.status, 403);

  // The rejected write must not have created anything.
  const list = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(list.body.planned.length, 1);
});

test('waypoint routes require authentication', async () => {
  const { app, tripId } = setup();

  assert.equal((await supertest(app).get(`/trips/${tripId}/waypoints`)).status, 401);
  assert.equal((await supertest(app).post(`/trips/${tripId}/waypoints`).send(validPlanned)).status, 401);
  assert.equal((await supertest(app).delete(`/trips/${tripId}/waypoints/1`)).status, 401);
});

test('DELETE: the member who added a waypoint can delete it', async () => {
  const { app, tripId, memberToken } = setup();

  const created = await post(app, tripId, memberToken, validPlanned);
  const res = await supertest(app)
    .delete(`/trips/${tripId}/waypoints/${created.body.id}`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(res.status, 204);

  const list = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(list.body.planned.length, 0);
});

test("DELETE: the trip owner can delete another member's waypoint", async () => {
  const { app, tripId, memberToken, ownerToken } = setup();

  const created = await post(app, tripId, memberToken, validPlanned);
  const res = await supertest(app)
    .delete(`/trips/${tripId}/waypoints/${created.body.id}`)
    .set('Authorization', `Bearer ${ownerToken}`);
  assert.equal(res.status, 204);
});

test("DELETE: a plain member cannot delete someone else's waypoint", async () => {
  const { app, db, tripId, memberToken, ownerToken, ownerId } = setup();

  // A waypoint added by the owner...
  const created = await post(app, tripId, ownerToken, validPlanned);
  assert.equal(created.body.added_by, ownerId);

  // ...cannot be deleted by a non-owner member who didn't add it.
  const res = await supertest(app)
    .delete(`/trips/${tripId}/waypoints/${created.body.id}`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(res.status, 403);

  const stillThere = db
    .prepare('SELECT COUNT(*) AS count FROM trip_waypoints WHERE id = ?')
    .get(created.body.id).count;
  assert.equal(stillThere, 1);
});

test('DELETE: 404 for an unknown waypoint, or one belonging to another trip', async () => {
  const { app, db, tripId, memberToken, memberId, ownerId } = setup();

  const missing = await supertest(app)
    .delete(`/trips/${tripId}/waypoints/9999`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(missing.status, 404);

  // A second trip the member also belongs to, with its own waypoint.
  const otherTripId = Number(
    db.prepare("INSERT INTO trips (name, owner_id, status) VALUES ('Other', ?, 'active')").run(ownerId)
      .lastInsertRowid
  );
  db.prepare('INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, ?)').run(
    otherTripId,
    memberId,
    'member'
  );
  const otherWaypointId = Number(
    db
      .prepare(
        `INSERT INTO trip_waypoints (trip_id, name, lat, lng, type, order_index, added_by)
         VALUES (?, 'Elsewhere', 18.8, 98.9, 'live', NULL, ?)`
      )
      .run(otherTripId, memberId).lastInsertRowid
  );

  // Deleting it through the wrong trip's URL must not work.
  const crossTrip = await supertest(app)
    .delete(`/trips/${tripId}/waypoints/${otherWaypointId}`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(crossTrip.status, 404);

  const stillThere = db
    .prepare('SELECT COUNT(*) AS count FROM trip_waypoints WHERE id = ?')
    .get(otherWaypointId).count;
  assert.equal(stillThere, 1);
});

test('unknown trip returns 404, malformed trip id returns 400', async () => {
  const { app, memberToken } = setup();

  const unknown = await supertest(app)
    .get('/trips/9999/waypoints')
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(unknown.status, 404);

  const malformed = await supertest(app)
    .get('/trips/abc/waypoints')
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(malformed.status, 400);
});
