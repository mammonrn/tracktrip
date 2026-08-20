import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { validateWaypointPatch } from '../src/routes/waypoints.js';

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

function patch(app, tripId, token, waypointId, body) {
  return supertest(app)
    .patch(`/trips/${tripId}/waypoints/${waypointId}`)
    .set('Authorization', `Bearer ${token}`)
    .send(body);
}

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


// --- changing a waypoint that already exists --------------------------------
//
// Until this existed a route could only be appended to, so the order stops
// were arranged in lived on the phone and nowhere else: leaving the trip and
// coming back showed a list with no stops in it. Ordering has to be writable
// for the list to be the route rather than a sketch of it.

test('a patch says what changed, and a patch that changes nothing is refused', () => {
  assert.deepEqual(validateWaypointPatch({ order_index: 3 }).value, { order_index: 3 });
  assert.deepEqual(validateWaypointPatch({ name: '  Fuel  ' }).value, { name: 'Fuel' });
  assert.deepEqual(validateWaypointPatch({ name: 'Fuel', order_index: 1 }).value, {
    name: 'Fuel',
    order_index: 1,
  });

  // Absent on a create is an error; absent on a patch is "leave it". A body
  // with nothing in it is a request somebody meant to fill in.
  assert.match(validateWaypointPatch({}).error, /name or order_index is required/);
  assert.match(validateWaypointPatch(undefined).error, /name or order_index is required/);
  // Fields that are not patchable do not make a body meaningful.
  assert.match(validateWaypointPatch({ lat: 18.79 }).error, /name or order_index is required/);
});

test('a patch will not take a bad name or a bad order', () => {
  assert.match(validateWaypointPatch({ name: '   ' }).error, /name must be/);
  assert.match(validateWaypointPatch({ name: 'x'.repeat(61) }).error, /name must be/);
  assert.match(validateWaypointPatch({ order_index: -1 }).error, /order_index must be/);
  assert.match(validateWaypointPatch({ order_index: 1.5 }).error, /order_index must be/);
  assert.match(validateWaypointPatch({ order_index: '2' }).error, /order_index must be/);
});

test('the trip owner re-orders a stop', async () => {
  const { app, tripId, ownerToken, memberToken } = setup();

  const first = await post(app, tripId, memberToken, { ...validPlanned, name: 'A', order_index: 0 });
  const second = await post(app, tripId, memberToken, { ...validPlanned, name: 'B', order_index: 1 });

  const moved = await patch(app, tripId, ownerToken, first.body.id, { order_index: 1 });
  assert.equal(moved.status, 200);
  assert.equal(moved.body.order_index, 1);

  await patch(app, tripId, ownerToken, second.body.id, { order_index: 0 });

  const list = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${ownerToken}`);

  // The GET orders by order_index, so the swap is what a reopened route list
  // will read back.
  assert.deepEqual(list.body.planned.map((w) => w.name), ['B', 'A']);
});

test('a member cannot re-order, even their own stop', async () => {
  // Stricter than delete, on purpose: re-ordering one stop renumbers the ones
  // around it, so an author-only rule would let the first write succeed and
  // the second come back 403 — leaving the route half-renumbered with no way
  // for the rider to tell.
  const { app, tripId, memberToken } = setup();
  const mine = await post(app, tripId, memberToken, validPlanned);

  const refused = await patch(app, tripId, memberToken, mine.body.id, { order_index: 2 });
  assert.equal(refused.status, 403);

  const list = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${memberToken}`);
  assert.equal(list.body.planned[0].order_index, 0);
});

test('somebody who is not on the trip cannot patch its waypoints', async () => {
  const { app, tripId, memberToken, outsiderToken } = setup();
  const created = await post(app, tripId, memberToken, validPlanned);

  const refused = await patch(app, tripId, outsiderToken, created.body.id, { order_index: 1 });
  // The membership guard runs before the ownership one, so this is a 403 from
  // requireTripMembership rather than from the route.
  assert.equal(refused.status, 403);
});

test('a waypoint from another trip is not found, not forbidden', async () => {
  const { db, app, tripId, ownerId, ownerToken } = setup();
  const otherTrip = Number(
    db
      .prepare("INSERT INTO trips (name, owner_id, status) VALUES ('Elsewhere', ?, 'active')")
      .run(ownerId).lastInsertRowid
  );
  const stray = Number(
    db
      .prepare(
        `INSERT INTO trip_waypoints (trip_id, name, lat, lng, type, order_index, added_by)
         VALUES (?, 'Stray', 18, 98, 'planned', 0, ?)`
      )
      .run(otherTrip, ownerId).lastInsertRowid
  );

  const refused = await patch(app, tripId, ownerToken, stray, { order_index: 1 });
  assert.equal(refused.status, 404);
});

test('ordering is refused on a live waypoint', async () => {
  const { app, tripId, ownerToken, memberToken } = setup();
  const live = await post(app, tripId, memberToken, {
    name: 'Photo stop',
    lat: 18.79,
    lng: 98.98,
    type: 'live',
  });

  // Live waypoints are chronological and their order_index is NULL. A number
  // there would be read by nothing and imply an order that does not exist.
  const refused = await patch(app, tripId, ownerToken, live.body.id, { order_index: 0 });
  assert.equal(refused.status, 400);
  assert.match(refused.body.error, /planned/);

  // Renaming one is still fine.
  const renamed = await patch(app, tripId, ownerToken, live.body.id, { name: 'Viewpoint' });
  assert.equal(renamed.status, 200);
  assert.equal(renamed.body.name, 'Viewpoint');
  assert.equal(renamed.body.order_index, null);
});

test('a bad waypoint id is refused before anything is read', async () => {
  const { app, tripId, ownerToken } = setup();
  assert.equal((await patch(app, tripId, ownerToken, 'nope', { order_index: 1 })).status, 400);
  assert.equal((await patch(app, tripId, ownerToken, 0, { order_index: 1 })).status, 400);
  assert.equal((await patch(app, tripId, ownerToken, 999999, { order_index: 1 })).status, 404);
});

test('two stops may share an order index while a route is being rewritten', async () => {
  // A client rewriting a whole route sends one PATCH per moved stop, and any
  // intermediate state collides with itself under a unique index. The ordering
  // is a sort key; ties fall back to id, exactly as the GET says.
  const { app, tripId, ownerToken, memberToken } = setup();
  const first = await post(app, tripId, memberToken, { ...validPlanned, name: 'A', order_index: 0 });
  await post(app, tripId, memberToken, { ...validPlanned, name: 'B', order_index: 1 });

  const collided = await patch(app, tripId, ownerToken, first.body.id, { order_index: 1 });
  assert.equal(collided.status, 200);

  const list = await supertest(app)
    .get(`/trips/${tripId}/waypoints`)
    .set('Authorization', `Bearer ${ownerToken}`);
  assert.equal(list.body.planned.length, 2);
});
