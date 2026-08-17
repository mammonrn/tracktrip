import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';

const JWT_SECRET = 'test-secret';

/**
 * Builds an app with three signed-in users and no trips — the trips here are
 * created through the API, so the tests exercise the same path the app does.
 *
 * `friend` is the one who gets invited; `outsider` never belongs anywhere.
 */
function setup() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)'
  );
  const ownerId = Number(insertUser.run('sub-owner', 'owner@gmail.com', 'Owner').lastInsertRowid);
  const friendId = Number(insertUser.run('sub-friend', 'friend@gmail.com', 'Friend').lastInsertRowid);
  const outsiderId = Number(
    insertUser.run('sub-outsider', 'outsider@gmail.com', 'Outsider').lastInsertRowid
  );

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
    ownerId,
    friendId,
    outsiderId,
    ownerToken: tokenFor(ownerId),
    friendToken: tokenFor(friendId),
    outsiderToken: tokenFor(outsiderId),
  };
}

const authed = (app, method, path, token) =>
  supertest(app)[method](path).set('Authorization', `Bearer ${token}`);

async function createTrip(app, token, name = 'Chiang Mai loop') {
  const res = await authed(app, 'post', '/trips', token).send({ name });
  assert.equal(res.status, 201, `trip creation failed: ${JSON.stringify(res.body)}`);
  return res.body;
}

async function invite(app, token, tripId, email) {
  return authed(app, 'post', `/trips/${tripId}/invites`, token).send({ email });
}

// ─── POST /trips ────────────────────────────────────────────────────────────

test('POST /trips creates a trip with the caller as owner and as a member', async () => {
  const { app, db, ownerToken, ownerId } = setup();

  const res = await authed(app, 'post', '/trips', ownerToken).send({ name: '  Mae Hong Son  ' });
  assert.equal(res.status, 201);
  assert.equal(res.body.name, 'Mae Hong Son'); // trimmed
  assert.equal(res.body.owner_id, ownerId);
  assert.equal(res.body.status, 'active');
  assert.equal(res.body.ended_at, null);
  assert.equal(res.body.role, 'owner');

  // The owner must also be a trip_members row, or membership checks (and so
  // every /trips/:id/* route) would lock the creator out of their own trip.
  const membership = db
    .prepare('SELECT * FROM trip_members WHERE trip_id = ? AND user_id = ?')
    .get(res.body.id, ownerId);
  assert.equal(membership.role, 'owner');
});

test('POST /trips rejects a malformed name', async () => {
  const { app, db, ownerToken } = setup();
  const bad = async (body, why) => {
    const res = await authed(app, 'post', '/trips', ownerToken).send(body);
    assert.equal(res.status, 400, `expected 400 for ${why}, got ${res.status}`);
  };

  await bad({}, 'missing name');
  await bad({ name: '' }, 'empty name');
  await bad({ name: '   ' }, 'whitespace-only name');
  await bad({ name: 'x'.repeat(61) }, 'name over 60 chars');
  await bad({ name: 123 }, 'non-string name');
  await bad({ name: null }, 'null name');

  // The 60-character boundary is valid.
  const edge = await authed(app, 'post', '/trips', ownerToken).send({ name: 'x'.repeat(60) });
  assert.equal(edge.status, 201);

  // None of the rejected bodies may have written a row.
  assert.equal(db.prepare('SELECT COUNT(*) AS count FROM trips').get().count, 1);
});

test('GET /trips returns only the trips the caller belongs to, with their role', async () => {
  const { app, ownerToken, friendToken, outsiderToken } = setup();

  const trip = await createTrip(app, ownerToken);
  const invited = await invite(app, ownerToken, trip.id, 'friend@gmail.com');
  await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();

  const ownerList = await authed(app, 'get', '/trips', ownerToken);
  assert.equal(ownerList.status, 200);
  assert.deepEqual(
    ownerList.body.map((t) => [t.id, t.role]),
    [[trip.id, 'owner']]
  );

  const friendList = await authed(app, 'get', '/trips', friendToken);
  assert.deepEqual(
    friendList.body.map((t) => [t.id, t.role]),
    [[trip.id, 'member']]
  );

  const outsiderList = await authed(app, 'get', '/trips', outsiderToken);
  assert.deepEqual(outsiderList.body, []);
});

// ─── POST /trips/:id/invites ────────────────────────────────────────────────

test('the owner can invite by email, and the address is stored normalized', async () => {
  const { app, db, ownerToken, ownerId } = setup();
  const trip = await createTrip(app, ownerToken);

  const res = await invite(app, ownerToken, trip.id, '  Friend@GMail.com ');
  assert.equal(res.status, 201);
  assert.equal(res.body.email, 'friend@gmail.com');
  assert.equal(res.body.trip_id, trip.id);
  assert.equal(res.body.invited_by, ownerId);
  assert.equal(res.body.status, 'pending');
  assert.equal(res.body.accepted_at, null);
  assert.equal(res.body.accepted_by, null);

  const row = db.prepare('SELECT * FROM trip_invites WHERE id = ?').get(res.body.id);
  assert.equal(row.email, 'friend@gmail.com');
});

test('only the owner can invite — a plain member and an outsider both get 403', async () => {
  const { app, db, ownerToken, friendToken, outsiderToken } = setup();
  const trip = await createTrip(app, ownerToken);

  const invited = await invite(app, ownerToken, trip.id, 'friend@gmail.com');
  await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();

  // friend is now a member, but membership is not permission to invite.
  const byMember = await invite(app, friendToken, trip.id, 'someone@gmail.com');
  assert.equal(byMember.status, 403);

  const byOutsider = await invite(app, outsiderToken, trip.id, 'someone@gmail.com');
  assert.equal(byOutsider.status, 403);

  assert.equal(
    db.prepare("SELECT COUNT(*) AS count FROM trip_invites WHERE email = 'someone@gmail.com'").get()
      .count,
    0
  );
});

test('invite rejects a malformed email, the owner themselves, and an existing member', async () => {
  const { app, ownerToken, friendToken } = setup();
  const trip = await createTrip(app, ownerToken);

  const bad = async (email, why) => {
    const res = await invite(app, ownerToken, trip.id, email);
    assert.equal(res.status, 400, `expected 400 for ${why}, got ${res.status}`);
  };
  await bad(undefined, 'missing email');
  await bad('', 'empty email');
  await bad('not-an-email', 'no @');
  await bad('no@domain', 'no dot in domain');
  await bad('two@@gmail.com', 'two @');
  await bad('spaces in@gmail.com', 'spaces');
  await bad(42, 'non-string email');

  // Inviting yourself: you are already the owner.
  const self = await invite(app, ownerToken, trip.id, 'OWNER@gmail.com');
  assert.equal(self.status, 400);

  // Inviting someone who already joined.
  const first = await invite(app, ownerToken, trip.id, 'friend@gmail.com');
  await authed(app, 'post', `/invites/${first.body.id}/accept`, friendToken).send();
  const again = await invite(app, ownerToken, trip.id, 'friend@gmail.com');
  assert.equal(again.status, 409);
});

test('re-inviting a still-pending email returns the same invite instead of a duplicate', async () => {
  const { app, db, ownerToken } = setup();
  const trip = await createTrip(app, ownerToken);

  const first = await invite(app, ownerToken, trip.id, 'friend@gmail.com');
  assert.equal(first.status, 201);

  const second = await invite(app, ownerToken, trip.id, 'FRIEND@gmail.com');
  assert.equal(second.status, 200);
  assert.equal(second.body.id, first.body.id);

  assert.equal(
    db.prepare('SELECT COUNT(*) AS count FROM trip_invites WHERE trip_id = ?').get(trip.id).count,
    1
  );
});

test('a revoked invite is reopened as pending when the owner invites again', async () => {
  const { app, db, ownerToken, friendToken } = setup();
  const trip = await createTrip(app, ownerToken);

  const first = await invite(app, ownerToken, trip.id, 'friend@gmail.com');
  db.prepare("UPDATE trip_invites SET status = 'revoked' WHERE id = ?").run(first.body.id);

  // While revoked it cannot be accepted...
  const blocked = await authed(app, 'post', `/invites/${first.body.id}/accept`, friendToken).send();
  assert.equal(blocked.status, 409);

  // ...and re-inviting brings the same row back rather than tripping the
  // UNIQUE (trip_id, email) constraint.
  const reopened = await invite(app, ownerToken, trip.id, 'friend@gmail.com');
  assert.equal(reopened.status, 200);
  assert.equal(reopened.body.id, first.body.id);
  assert.equal(reopened.body.status, 'pending');

  const accepted = await authed(app, 'post', `/invites/${first.body.id}/accept`, friendToken).send();
  assert.equal(accepted.status, 200);
});

test('invite returns 404 for an unknown trip and 400 for a malformed trip id', async () => {
  const { app, ownerToken } = setup();

  assert.equal((await invite(app, ownerToken, 9999, 'friend@gmail.com')).status, 404);
  assert.equal((await invite(app, ownerToken, 'abc', 'friend@gmail.com')).status, 400);
});

// ─── POST /invites/:id/accept ───────────────────────────────────────────────

test('the invited rider accepts and becomes a member who can use the trip', async () => {
  const { app, db, ownerToken, friendToken, friendId } = setup();
  const trip = await createTrip(app, ownerToken);
  const invited = await invite(app, ownerToken, trip.id, 'friend@gmail.com');

  const res = await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();
  assert.equal(res.status, 200);
  assert.equal(res.body.trip.id, trip.id);
  assert.equal(res.body.trip.role, 'member');
  assert.equal(res.body.invite.status, 'accepted');
  assert.equal(res.body.invite.accepted_by, friendId);
  assert.ok(res.body.invite.accepted_at);

  const membership = db
    .prepare('SELECT * FROM trip_members WHERE trip_id = ? AND user_id = ?')
    .get(trip.id, friendId);
  assert.equal(membership.role, 'member');

  // Membership is what the rest of the trip API keys off, so it should now work.
  const waypoints = await authed(app, 'get', `/trips/${trip.id}/waypoints`, friendToken);
  assert.equal(waypoints.status, 200);
});

test('email matching is case-insensitive on both sides', async () => {
  const { app, db, ownerToken, friendToken, friendId } = setup();
  // Google can hand back whatever casing the account was typed with.
  db.prepare('UPDATE users SET email = ? WHERE id = ?').run('Friend@Gmail.COM', friendId);

  const trip = await createTrip(app, ownerToken);
  const invited = await invite(app, ownerToken, trip.id, 'FRIEND@gmail.com');

  const res = await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();
  assert.equal(res.status, 200);
});

test('only the invited email can accept — anyone else gets 403 and no membership', async () => {
  const { app, db, ownerToken, outsiderToken, outsiderId } = setup();
  const trip = await createTrip(app, ownerToken);
  const invited = await invite(app, ownerToken, trip.id, 'friend@gmail.com');

  const res = await authed(app, 'post', `/invites/${invited.body.id}/accept`, outsiderToken).send();
  assert.equal(res.status, 403);

  assert.equal(
    db
      .prepare('SELECT COUNT(*) AS count FROM trip_members WHERE trip_id = ? AND user_id = ?')
      .get(trip.id, outsiderId).count,
    0
  );
  assert.equal(
    db.prepare('SELECT status FROM trip_invites WHERE id = ?').get(invited.body.id).status,
    'pending'
  );
});

test('accepting twice returns 409 the second time', async () => {
  const { app, ownerToken, friendToken } = setup();
  const trip = await createTrip(app, ownerToken);
  const invited = await invite(app, ownerToken, trip.id, 'friend@gmail.com');

  const first = await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();
  assert.equal(first.status, 200);

  const second = await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();
  assert.equal(second.status, 409);
});

test('accept returns 404 for an unknown invite and 400 for a malformed invite id', async () => {
  const { app, friendToken } = setup();

  assert.equal((await authed(app, 'post', '/invites/9999/accept', friendToken).send()).status, 404);
  assert.equal((await authed(app, 'post', '/invites/abc/accept', friendToken).send()).status, 400);
  assert.equal((await authed(app, 'post', '/invites/0/accept', friendToken).send()).status, 400);
});

test('GET /invites shows the caller only their own pending invites', async () => {
  const { app, db, ownerToken, friendToken, outsiderToken } = setup();
  const trip = await createTrip(app, ownerToken, 'Pai run');
  const invited = await invite(app, ownerToken, trip.id, 'friend@gmail.com');

  const pending = await authed(app, 'get', '/invites', friendToken);
  assert.equal(pending.status, 200);
  assert.equal(pending.body.length, 1);
  assert.equal(pending.body[0].id, invited.body.id);
  assert.equal(pending.body[0].trip_name, 'Pai run');

  // Not addressed to the outsider.
  assert.deepEqual((await authed(app, 'get', '/invites', outsiderToken)).body, []);

  // Accepting takes it out of the list.
  await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();
  assert.deepEqual((await authed(app, 'get', '/invites', friendToken)).body, []);

  // So does the trip ending, for an invite that was never accepted.
  const second = await createTrip(app, ownerToken, 'Doi Inthanon');
  await invite(app, ownerToken, second.id, 'friend@gmail.com');
  assert.equal((await authed(app, 'get', '/invites', friendToken)).body.length, 1);
  db.prepare("UPDATE trips SET status = 'ended' WHERE id = ?").run(second.id);
  assert.deepEqual((await authed(app, 'get', '/invites', friendToken)).body, []);
});

// ─── POST /trips/:id/end ────────────────────────────────────────────────────

test('the owner ends the trip, which stamps status and ended_at', async () => {
  const { app, db, ownerToken } = setup();
  const trip = await createTrip(app, ownerToken);

  const res = await authed(app, 'post', `/trips/${trip.id}/end`, ownerToken).send();
  assert.equal(res.status, 200);
  assert.equal(res.body.status, 'ended');
  assert.ok(res.body.ended_at);

  const row = db.prepare('SELECT * FROM trips WHERE id = ?').get(trip.id);
  assert.equal(row.status, 'ended');
  assert.ok(row.ended_at);
});

test('only the owner can end a trip, and ending it twice returns 409', async () => {
  const { app, db, ownerToken, friendToken, outsiderToken } = setup();
  const trip = await createTrip(app, ownerToken);
  const invited = await invite(app, ownerToken, trip.id, 'friend@gmail.com');
  await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();

  assert.equal((await authed(app, 'post', `/trips/${trip.id}/end`, friendToken).send()).status, 403);
  assert.equal(
    (await authed(app, 'post', `/trips/${trip.id}/end`, outsiderToken).send()).status,
    403
  );
  assert.equal(db.prepare('SELECT status FROM trips WHERE id = ?').get(trip.id).status, 'active');

  assert.equal((await authed(app, 'post', `/trips/${trip.id}/end`, ownerToken).send()).status, 200);
  assert.equal((await authed(app, 'post', `/trips/${trip.id}/end`, ownerToken).send()).status, 409);
});

test('end returns 404 for an unknown trip and 400 for a malformed trip id', async () => {
  const { app, ownerToken } = setup();

  assert.equal((await authed(app, 'post', '/trips/9999/end', ownerToken).send()).status, 404);
  assert.equal((await authed(app, 'post', '/trips/abc/end', ownerToken).send()).status, 400);
});

test('an ended trip stops taking new member updates but stays readable', async () => {
  const { app, db, ownerToken, friendToken, friendId } = setup();
  const trip = await createTrip(app, ownerToken);
  const invited = await invite(app, ownerToken, trip.id, 'friend@gmail.com');
  await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();

  // A live drop from a member lands while the trip is running.
  const drop = { name: 'Viewpoint', lat: 18.8, lng: 98.9, type: 'live' };
  const beforeEnd = await authed(app, 'post', `/trips/${trip.id}/waypoints`, friendToken).send(drop);
  assert.equal(beforeEnd.status, 201);

  await authed(app, 'post', `/trips/${trip.id}/end`, ownerToken).send();

  // After the trip ends, members can no longer post location updates...
  const afterEnd = await authed(app, 'post', `/trips/${trip.id}/waypoints`, friendToken).send(drop);
  assert.equal(afterEnd.status, 409);
  assert.equal(afterEnd.body.error, 'trip has ended');

  // ...nor can the owner, and neither of them can delete history.
  const ownerDrop = await authed(app, 'post', `/trips/${trip.id}/waypoints`, ownerToken).send(drop);
  assert.equal(ownerDrop.status, 409);
  const del = await authed(
    app,
    'delete',
    `/trips/${trip.id}/waypoints/${beforeEnd.body.id}`,
    ownerToken
  ).send();
  assert.equal(del.status, 409);

  // Nothing new was written and nothing was removed.
  assert.equal(
    db.prepare('SELECT COUNT(*) AS count FROM trip_waypoints WHERE trip_id = ?').get(trip.id).count,
    1
  );

  // Reading the finished trip still works for its members.
  const list = await authed(app, 'get', `/trips/${trip.id}/waypoints`, friendToken);
  assert.equal(list.status, 200);
  assert.equal(list.body.live.length, 1);
  const trips = await authed(app, 'get', '/trips', friendToken);
  assert.equal(trips.body[0].status, 'ended');
  assert.equal(trips.body[0].role, 'member');
  assert.ok(friendId);
});

test('an ended trip takes no new invites, and a pending one can no longer be accepted', async () => {
  const { app, ownerToken, friendToken, outsiderToken } = setup();
  const trip = await createTrip(app, ownerToken);
  const pending = await invite(app, ownerToken, trip.id, 'friend@gmail.com');

  await authed(app, 'post', `/trips/${trip.id}/end`, ownerToken).send();

  const late = await invite(app, ownerToken, trip.id, 'outsider@gmail.com');
  assert.equal(late.status, 409);
  assert.equal(late.body.error, 'trip has ended');

  const lateAccept = await authed(app, 'post', `/invites/${pending.body.id}/accept`, friendToken)
    .send();
  assert.equal(lateAccept.status, 409);

  // And the would-be joiners really are still outside the trip.
  assert.equal((await authed(app, 'get', `/trips/${trip.id}/waypoints`, friendToken)).status, 403);
  assert.equal((await authed(app, 'get', `/trips/${trip.id}/waypoints`, outsiderToken)).status, 403);
});

// ─── Auth ───────────────────────────────────────────────────────────────────

test('every trip and invite route requires authentication', async () => {
  const { app, ownerToken } = setup();
  const trip = await createTrip(app, ownerToken);
  const request = supertest(app);

  assert.equal((await request.post('/trips').send({ name: 'Nope' })).status, 401);
  assert.equal((await request.get('/trips')).status, 401);
  assert.equal(
    (await request.post(`/trips/${trip.id}/invites`).send({ email: 'friend@gmail.com' })).status,
    401
  );
  assert.equal((await request.post(`/trips/${trip.id}/end`)).status, 401);
  assert.equal((await request.post('/invites/1/accept')).status, 401);
  assert.equal((await request.get('/invites')).status, 401);

  // A well-formed token for a user that no longer exists is still rejected.
  const ghostToken = signAccessToken(9999, JWT_SECRET);
  assert.equal((await supertest(app).get('/trips').set('Authorization', `Bearer ${ghostToken}`)).status, 401);
});
