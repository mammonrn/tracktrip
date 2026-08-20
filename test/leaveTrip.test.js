import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { activeTripFor } from '../src/trips/activeTrip.js';

const JWT_SECRET = 'test-secret';

/**
 * A member leaving a trip.
 *
 * One active trip per rider is enforced now, and until this route there was
 * exactly one way off a trip: its owner ending it. A rider who accepted an
 * invitation and whose host went home without pressing End could not start a
 * trip of their own, could not accept another invitation, and had nothing they
 * could do about it. The first group below is that trap, and that the door out
 * of it actually opens.
 */
function setup() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name, role) VALUES (?, ?, ?, ?)'
  );
  const ownerId = Number(
    insertUser.run('sub-owner', 'owner@gmail.com', 'Owner', 'user').lastInsertRowid
  );
  const friendId = Number(
    insertUser.run('sub-friend', 'friend@gmail.com', 'Friend', 'user').lastInsertRowid
  );
  const adminId = Number(
    insertUser.run('sub-admin', 'admin@gmail.com', 'Admin', 'superuser').lastInsertRowid
  );

  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'] },
    verifyGoogleIdToken: async () => {
      throw new Error('unused in these tests');
    },
  });

  const tokenFor = (id) => signAccessToken(id, JWT_SECRET);
  return {
    db,
    app,
    ownerId,
    friendId,
    adminId,
    ownerToken: tokenFor(ownerId),
    friendToken: tokenFor(friendId),
    adminToken: tokenFor(adminId),
  };
}

const authed = (app, method, path, token) =>
  supertest(app)[method](path).set('Authorization', `Bearer ${token}`);

const createTrip = (app, token, name) => authed(app, 'post', '/trips', token).send({ name });
const endTrip = (app, token, tripId) => authed(app, 'post', `/trips/${tripId}/end`, token).send();
const leave = (app, token, tripId) =>
  authed(app, 'delete', `/trips/${tripId}/members/me`, token).send();

/** Owner starts a trip, friend accepts an invitation onto it. */
async function tripWithAFriendOnIt(ctx, name = 'Chiang Mai loop') {
  const trip = await createTrip(ctx.app, ctx.ownerToken, name);
  assert.equal(trip.status, 201, JSON.stringify(trip.body));
  const invited = await authed(ctx.app, 'post', `/trips/${trip.body.id}/invites`, ctx.ownerToken)
    .send({ email: 'friend@gmail.com' });
  assert.equal(invited.status, 201);
  const accepted = await authed(
    ctx.app,
    'post',
    `/invites/${invited.body.id}/accept`,
    ctx.friendToken
  ).send();
  assert.equal(accepted.status, 200, JSON.stringify(accepted.body));
  return { tripId: trip.body.id, inviteId: invited.body.id };
}

// ─── The trap, and the door out ─────────────────────────────────────────────

test('a member stuck on somebody else\'s trip can leave and start their own', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  // The trap: the host has gone home, the trip is still running, and one
  // active trip per rider means the friend cannot start anything.
  const blocked = await createTrip(ctx.app, ctx.friendToken, "Friend's own ride");
  assert.equal(blocked.status, 409);
  assert.equal(blocked.body.code, 'active_trip_exists');

  assert.equal((await leave(ctx.app, ctx.friendToken, tripId)).status, 204);

  const own = await createTrip(ctx.app, ctx.friendToken, "Friend's own ride");
  assert.equal(own.status, 201, JSON.stringify(own.body));
});

test('the guard reads the membership table, so leaving clears it immediately', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  assert.equal(activeTripFor(ctx.db, ctx.friendId).id, tripId);
  await leave(ctx.app, ctx.friendToken, tripId);

  // The trigger and the route guards both ask this: the row is gone, so
  // nothing is holding them. No cache, no denormalised flag to fall out of
  // step — which is why the rule lives on trip_members rather than beside it.
  assert.equal(activeTripFor(ctx.db, ctx.friendId), undefined);
});

test('leaving lets the next invitation be accepted', async () => {
  const ctx = await setup();
  const first = await tripWithAFriendOnIt(ctx, 'Chiang Mai loop');

  // A second host, and an invitation the friend could not take up while stuck.
  const second = await createTrip(ctx.app, ctx.adminToken, 'Pai run');
  const invited = await authed(ctx.app, 'post', `/trips/${second.body.id}/invites`, ctx.adminToken)
    .send({ email: 'friend@gmail.com' });
  const refused = await authed(
    ctx.app,
    'post',
    `/invites/${invited.body.id}/accept`,
    ctx.friendToken
  ).send();
  assert.equal(refused.status, 409);

  await leave(ctx.app, ctx.friendToken, first.tripId);

  const accepted = await authed(
    ctx.app,
    'post',
    `/invites/${invited.body.id}/accept`,
    ctx.friendToken
  ).send();
  assert.equal(accepted.status, 200, JSON.stringify(accepted.body));
});

// ─── Who may ────────────────────────────────────────────────────────────────

test('the owner cannot leave their own trip', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  const res = await leave(ctx.app, ctx.ownerToken, tripId);
  assert.equal(res.status, 409);
  // A trip with no owner is one nobody can end, invite to or rename — and the
  // owner already has a door, marked /end.
  assert.match(res.body.error, /End the trip instead/);

  assert.equal(
    ctx.db
      .prepare('SELECT COUNT(*) AS n FROM trip_members WHERE trip_id = ? AND user_id = ?')
      .get(tripId, ctx.ownerId).n,
    1
  );
});

test('somebody who is not on the trip cannot leave it', async () => {
  const ctx = await setup();
  const trip = await createTrip(ctx.app, ctx.ownerToken, 'Chiang Mai loop');

  // The super user manages every trip and is on none of them. Managing has
  // never meant riding, and there is nothing here for them to leave.
  const res = await leave(ctx.app, ctx.adminToken, trip.body.id);
  assert.equal(res.status, 403);
});

test('a finished trip cannot be left', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);
  await endTrip(ctx.app, ctx.ownerToken, tripId);

  // Nothing is holding them — the rule counts only active trips — and deleting
  // the membership would take them out of a completed ride's roster and off
  // its podium. That is rewriting history, not leaving.
  assert.equal((await leave(ctx.app, ctx.friendToken, tripId)).status, 409);
  assert.equal((await createTrip(ctx.app, ctx.friendToken, 'Next one')).status, 201);
});

test('leaving twice is a 403, not a second departure', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  assert.equal((await leave(ctx.app, ctx.friendToken, tripId)).status, 204);
  assert.equal((await leave(ctx.app, ctx.friendToken, tripId)).status, 403);
});

// ─── What leaving takes with it ─────────────────────────────────────────────

test('leaving takes the rider off the roster and off the map', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  await authed(ctx.app, 'post', `/trips/${tripId}/positions`, ctx.friendToken).send({
    lat: 18.79,
    lng: 98.98,
  });

  const before = await authed(ctx.app, 'get', `/trips/${tripId}/positions`, ctx.ownerToken);
  assert.ok(before.body.some((p) => p.user_id === ctx.friendId));

  await leave(ctx.app, ctx.friendToken, tripId);

  const after = await authed(ctx.app, 'get', `/trips/${tripId}/positions`, ctx.ownerToken);
  assert.ok(!after.body.some((p) => p.user_id === ctx.friendId));

  // The live row goes with them rather than being orphaned: re-invited next
  // month they would otherwise appear at the petrol station they left from,
  // dated then and drawn now.
  assert.equal(
    ctx.db
      .prepare('SELECT COUNT(*) AS n FROM member_positions WHERE trip_id = ? AND user_id = ?')
      .get(tripId, ctx.friendId).n,
    0
  );
});

test('leaving stops the sharing session, and reporting afterwards is refused', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  await authed(ctx.app, 'post', `/trips/${tripId}/share/start`, ctx.friendToken).send({
    duration_minutes: 240,
  });
  assert.equal(
    ctx.db
      .prepare('SELECT COUNT(*) AS n FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
      .get(tripId, ctx.friendId).n,
    1
  );

  await leave(ctx.app, ctx.friendToken, tripId);

  // Same rule ending a trip follows: a session outliving the membership is a
  // rider believing they are still being tracked.
  assert.equal(
    ctx.db
      .prepare('SELECT COUNT(*) AS n FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
      .get(tripId, ctx.friendId).n,
    0
  );
  const report = await authed(ctx.app, 'post', `/trips/${tripId}/positions`, ctx.friendToken).send({
    lat: 19.1,
    lng: 99.1,
  });
  assert.equal(report.status, 403);
});

test('leaving does not stop anybody else sharing', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  await authed(ctx.app, 'post', `/trips/${tripId}/share/start`, ctx.ownerToken).send({
    duration_minutes: 240,
  });

  await leave(ctx.app, ctx.friendToken, tripId);

  assert.equal(
    ctx.db.prepare('SELECT COUNT(*) AS n FROM sharing_sessions WHERE trip_id = ?').get(tripId).n,
    1
  );
  assert.equal(
    (await authed(ctx.app, 'post', `/trips/${tripId}/positions`, ctx.ownerToken).send({
      lat: 18.8,
      lng: 98.9,
    })).status,
    200
  );
});

test('the trip itself is untouched — it keeps running, with its route', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  const waypoint = await authed(ctx.app, 'post', `/trips/${tripId}/waypoints`, ctx.friendToken)
    .send({ name: 'Coffee', lat: 18.8, lng: 98.9, type: 'live' });
  assert.equal(waypoint.status, 201, JSON.stringify(waypoint.body));

  await leave(ctx.app, ctx.friendToken, tripId);

  // A route belongs to the trip, not to whoever added a stop to it — the stop
  // stays, and so does the `added_by` pointing at a rider who has gone.
  const stops = await authed(ctx.app, 'get', `/trips/${tripId}/waypoints`, ctx.ownerToken);
  assert.equal(stops.body.live.length, 1);
  assert.equal(stops.body.live[0].name, 'Coffee');
  assert.equal(stops.body.live[0].added_by, ctx.friendId);

  const trip = (await authed(ctx.app, 'get', '/trips', ctx.ownerToken)).body.find(
    (t) => t.id === tripId
  );
  assert.equal(trip.status, 'active');
});

test('the ride that happened stays in the history', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  await authed(ctx.app, 'post', `/trips/${tripId}/positions`, ctx.friendToken).send({
    lat: 18.79,
    lng: 98.98,
  });

  await leave(ctx.app, ctx.friendToken, tripId);

  // Leaving afterwards is not grounds to rewrite where everybody went, and
  // cleanup-history.js already ages this out on its own schedule.
  assert.ok(
    ctx.db
      .prepare('SELECT COUNT(*) AS n FROM position_history WHERE trip_id = ? AND user_id = ?')
      .get(tripId, ctx.friendId).n > 0
  );
});

// ─── Coming back ────────────────────────────────────────────────────────────

test('a rider who left can be invited back', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);
  await leave(ctx.app, ctx.friendToken, tripId);

  // Left `accepted`, UNIQUE (trip_id, email) would mean this answers "that
  // invite was already accepted" for ever — a door out that cannot be walked
  // back through is half a door. Leaving puts the invite back to `revoked`,
  // which POST /trips/:id/invites already knows how to reopen.
  const again = await authed(ctx.app, 'post', `/trips/${tripId}/invites`, ctx.ownerToken).send({
    email: 'friend@gmail.com',
  });
  assert.equal(again.status, 200, JSON.stringify(again.body));
  assert.equal(again.body.status, 'pending');

  const accepted = await authed(
    ctx.app,
    'post',
    `/invites/${again.body.id}/accept`,
    ctx.friendToken
  ).send();
  assert.equal(accepted.status, 200, JSON.stringify(accepted.body));
  assert.equal(
    ctx.db
      .prepare('SELECT role FROM trip_members WHERE trip_id = ? AND user_id = ?')
      .get(tripId, ctx.friendId).role,
    'member'
  );
});

test('leaving keeps the ride in what the two have ridden together', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);
  await leave(ctx.app, ctx.friendToken, tripId);

  // The whole reason leaving is a soft delete. "Ridden with before" joins
  // trip_members to itself, and the row survives with `left_at` stamped — so
  // the ride the two of them were on still counts, which it should: it
  // happened. Somebody getting off afterwards does not unmake it.
  //
  // And they are offered *again* rather than being excluded as somebody
  // already here, because the exclusion asks the other question and takes
  // `left_at IS NULL`.
  const suggestions = await authed(
    ctx.app,
    'get',
    `/trips/${tripId}/suggested-invitees`,
    ctx.ownerToken
  );
  assert.equal(suggestions.status, 200);
  assert.deepEqual(
    suggestions.body.map((s) => s.email),
    ['friend@gmail.com']
  );
  assert.equal(suggestions.body[0].trips_together, 1);
});

// ─── Left, but not forgotten: history without access ────────────────────────

test('a rider who left keeps no access to anything the trip holds', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);
  await authed(ctx.app, 'post', `/trips/${tripId}/waypoints`, ctx.friendToken).send({
    name: 'Coffee',
    lat: 18.8,
    lng: 98.9,
    type: 'live',
  });

  await leave(ctx.app, ctx.friendToken, tripId);

  // The row is still in trip_members — that is what keeps the ride in their
  // shared history — and it must never be read as access. Every gate, because
  // one of them getting it wrong is a departed rider still watching the group
  // move.
  for (const [method, path] of [
    ['get', `/trips/${tripId}/positions`],
    ['get', `/trips/${tripId}/waypoints`],
    ['get', `/trips/${tripId}/positions/history`],
    ['get', `/trips/${tripId}/member-levels`],
    ['post', `/trips/${tripId}/positions`],
    ['post', `/trips/${tripId}/share/start`],
    ['post', `/trips/${tripId}/share/stop`],
  ]) {
    const res = await authed(ctx.app, method, path, ctx.friendToken).send(
      method === 'post' ? { lat: 18.79, lng: 98.98 } : undefined
    );
    assert.equal(res.status, 403, `${method.toUpperCase()} ${path} should be refused`);
  }
});

test('the trip drops out of the leaver\'s own list', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);

  const before = await authed(ctx.app, 'get', '/trips', ctx.friendToken);
  assert.deepEqual(before.body.map((t) => t.id), [tripId]);

  await leave(ctx.app, ctx.friendToken, tripId);

  // A row that survives is not a trip they are on. If this listed it, every
  // screen in the app would offer a trip whose every read answers 403.
  assert.deepEqual((await authed(ctx.app, 'get', '/trips', ctx.friendToken)).body, []);
});

test('the row is stamped rather than deleted', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);
  await leave(ctx.app, ctx.friendToken, tripId);

  const row = ctx.db
    .prepare('SELECT * FROM trip_members WHERE trip_id = ? AND user_id = ?')
    .get(tripId, ctx.friendId);
  assert.ok(row, 'the membership row survives, so the ride stays in their history');
  assert.ok(row.left_at, 'stamped with when they got off');
  assert.equal(row.role, 'member');
});

// ─── Coming back to a trip you left ─────────────────────────────────────────

test('rejoining by invitation revives the row rather than failing silently', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);
  await leave(ctx.app, ctx.friendToken, tripId);

  const again = await authed(ctx.app, 'post', `/trips/${tripId}/invites`, ctx.ownerToken).send({
    email: 'friend@gmail.com',
  });
  const accepted = await authed(
    ctx.app,
    'post',
    `/invites/${again.body.id}/accept`,
    ctx.friendToken
  ).send();
  assert.equal(accepted.status, 200, JSON.stringify(accepted.body));

  // The primary key is (trip_id, user_id), so the insert conflicts with their
  // own dormant row. `DO NOTHING` would have marked the invite accepted and
  // put nobody on the trip — a success that joined no one.
  const row = ctx.db
    .prepare('SELECT * FROM trip_members WHERE trip_id = ? AND user_id = ?')
    .get(tripId, ctx.friendId);
  assert.equal(row.left_at, null);
  assert.equal((await authed(ctx.app, 'get', `/trips/${tripId}/positions`, ctx.friendToken)).status, 200);
});

test('rejoining by join code revives the row too', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);
  await leave(ctx.app, ctx.friendToken, tripId);

  const code = await authed(ctx.app, 'post', `/trips/${tripId}/join-code`, ctx.ownerToken).send();
  const join = await authed(ctx.app, 'post', '/trips/join', ctx.friendToken).send({
    code: code.body.code,
  });

  assert.equal(join.status, 200, JSON.stringify(join.body));
  // Not "already_member": they had left, so this is a join, not a no-op.
  assert.equal(join.body.already_member, false);
  assert.equal(
    ctx.db
      .prepare('SELECT left_at FROM trip_members WHERE trip_id = ? AND user_id = ?')
      .get(tripId, ctx.friendId).left_at,
    null
  );
});

test('rejoining is refused while they are out on another trip', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);
  await leave(ctx.app, ctx.friendToken, tripId);
  await createTrip(ctx.app, ctx.friendToken, "Friend's own ride");

  const again = await authed(ctx.app, 'post', `/trips/${tripId}/invites`, ctx.ownerToken).send({
    email: 'friend@gmail.com',
  });
  const accepted = await authed(
    ctx.app,
    'post',
    `/invites/${again.body.id}/accept`,
    ctx.friendToken
  ).send();
  assert.equal(accepted.status, 409);
  assert.equal(accepted.body.code, 'active_trip_exists');
});

test('the database refuses a revived membership even without a guard', async () => {
  const ctx = await setup();
  const { tripId } = await tripWithAFriendOnIt(ctx);
  await leave(ctx.app, ctx.friendToken, tripId);
  await createTrip(ctx.app, ctx.friendToken, "Friend's own ride");

  // Coming back is an UPDATE, which the BEFORE INSERT trigger from 0013 never
  // saw. Writing round the routes is what a lost race amounts to, and what a
  // hand-written UPDATE on the server is.
  assert.throws(
    () =>
      ctx.db
        .prepare('UPDATE trip_members SET left_at = NULL WHERE trip_id = ? AND user_id = ?')
        .run(tripId, ctx.friendId),
    /one active trip per rider/
  );
});
