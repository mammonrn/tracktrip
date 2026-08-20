import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { ACTIVE_TRIP_EXISTS, activeTripFor } from '../src/trips/activeTrip.js';

const JWT_SECRET = 'test-secret';

/**
 * One active trip per rider.
 *
 * The Android app had always assumed it — the phone holds a single
 * `ActiveSharing` and the location service reports to the trip it was started
 * for — and nothing on the server enforced it. A rider on two active trips has
 * a trip on their list that can never receive a position, and no way to tell
 * which one that is.
 *
 * These hold the three doors into a trip, that the refusal says which trip is
 * in the way, and that the database says no even if a door ever forgets to.
 */
function setup() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)'
  );
  const ownerId = Number(insertUser.run('sub-owner', 'owner@gmail.com', 'Owner').lastInsertRowid);
  const friendId = Number(
    insertUser.run('sub-friend', 'friend@gmail.com', 'Friend').lastInsertRowid
  );
  const hostId = Number(insertUser.run('sub-host', 'host@gmail.com', 'Host').lastInsertRowid);

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
    hostId,
    ownerToken: tokenFor(ownerId),
    friendToken: tokenFor(friendId),
    hostToken: tokenFor(hostId),
  };
}

const authed = (app, method, path, token) =>
  supertest(app)[method](path).set('Authorization', `Bearer ${token}`);

const createTrip = (app, token, name) => authed(app, 'post', '/trips', token).send({ name });

const endTrip = (app, token, tripId) =>
  authed(app, 'post', `/trips/${tripId}/end`, token).send();

const invite = (app, token, tripId, email) =>
  authed(app, 'post', `/trips/${tripId}/invites`, token).send({ email });

const joinCode = (app, token, tripId) =>
  authed(app, 'post', `/trips/${tripId}/join-code`, token).send();

// ─── POST /trips ────────────────────────────────────────────────────────────

test('a second trip is refused while the first is still running', async () => {
  const { app, ownerToken } = setup();

  const first = await createTrip(app, ownerToken, 'Chiang Mai loop');
  assert.equal(first.status, 201);

  const second = await createTrip(app, ownerToken, 'Pai run');
  assert.equal(second.status, 409);
  assert.equal(second.body.code, ACTIVE_TRIP_EXISTS);
  // The name is the point of the message: "you already have an active trip"
  // sends a rider to a list of forty to work out which, and the answer is
  // usually a ride they forgot to end rather than one they remember starting.
  assert.match(second.body.error, /Chiang Mai loop/);
  assert.equal(second.body.active_trip.id, first.body.id);
  assert.equal(second.body.active_trip.name, 'Chiang Mai loop');
  assert.equal(second.body.active_trip.role, 'owner');
});

test('ending the first trip lets the next one start', async () => {
  const { app, ownerToken } = setup();

  const first = await createTrip(app, ownerToken, 'Chiang Mai loop');
  assert.equal((await endTrip(app, ownerToken, first.body.id)).status, 200);

  const second = await createTrip(app, ownerToken, 'Pai run');
  assert.equal(second.status, 201, JSON.stringify(second.body));
});

test('a refused create writes nothing at all', async () => {
  const { db, app, ownerId, ownerToken } = setup();

  await createTrip(app, ownerToken, 'Chiang Mai loop');
  await createTrip(app, ownerToken, 'Pai run');

  // Not a trip with no owner membership, and not a name reserved for later:
  // the refusal has to leave the database as it found it.
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM trips').get().n, 1);
  assert.equal(
    db.prepare('SELECT COUNT(*) AS n FROM trip_members WHERE user_id = ?').get(ownerId).n,
    1
  );
});

test('one rider being out does not stop anybody else starting', async () => {
  const { app, ownerToken, hostToken } = setup();

  await createTrip(app, ownerToken, 'Chiang Mai loop');

  assert.equal((await createTrip(app, hostToken, 'Somebody else run')).status, 201);
});

// ─── Accepting an invite ────────────────────────────────────────────────────

test('an invite is refused while the invitee is out on their own trip', async () => {
  const { app, ownerToken, friendToken } = setup();

  const host = await createTrip(app, ownerToken, 'Chiang Mai loop');
  const invited = await invite(app, ownerToken, host.body.id, 'friend@gmail.com');
  assert.equal(invited.status, 201);

  const own = await createTrip(app, friendToken, "Friend's own ride");
  assert.equal(own.status, 201);

  const accept = await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();
  assert.equal(accept.status, 409);
  assert.equal(accept.body.code, ACTIVE_TRIP_EXISTS);
  assert.match(accept.body.error, /Friend's own ride/);
});

test('a refused invite stays pending, so it can be taken up later', async () => {
  const { app, ownerToken, friendToken } = setup();

  const host = await createTrip(app, ownerToken, 'Chiang Mai loop');
  const invited = await invite(app, ownerToken, host.body.id, 'friend@gmail.com');
  const own = await createTrip(app, friendToken, "Friend's own ride");
  await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();

  // Still in the inbox — consuming it would have cost the rider the trip for
  // the sake of a refusal they can undo in one press.
  const pending = await authed(app, 'get', '/invites', friendToken);
  assert.equal(pending.body.length, 1);
  assert.equal(pending.body[0].id, invited.body.id);

  await endTrip(app, friendToken, own.body.id);
  const retry = await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();
  assert.equal(retry.status, 200, JSON.stringify(retry.body));
  assert.equal(retry.body.trip.id, host.body.id);
});

// ─── Redeeming a join code ──────────────────────────────────────────────────

test('a join code is refused while the scanner is out on their own trip', async () => {
  const { app, ownerToken, friendToken } = setup();

  const host = await createTrip(app, ownerToken, 'Chiang Mai loop');
  const code = await joinCode(app, ownerToken, host.body.id);
  await createTrip(app, friendToken, "Friend's own ride");

  const join = await authed(app, 'post', '/trips/join', friendToken).send({
    code: code.body.code,
  });
  assert.equal(join.status, 409);
  assert.equal(join.body.code, ACTIVE_TRIP_EXISTS);
  assert.match(join.body.error, /Friend's own ride/);
});

test('scanning the code for the trip you are already on is still a success', async () => {
  const { app, ownerToken } = setup();

  const trip = await createTrip(app, ownerToken, 'Chiang Mai loop');
  const code = await joinCode(app, ownerToken, trip.body.id);

  // The rider's own active trip is the one being scanned, so the rule must
  // not refuse them for being on it. Two riders scanning the same QR twice
  // should both end up looking at the trip.
  const join = await authed(app, 'post', '/trips/join', ownerToken).send({ code: code.body.code });
  assert.equal(join.status, 200, JSON.stringify(join.body));
  assert.equal(join.body.already_member, true);
});

// ─── What counts ────────────────────────────────────────────────────────────

test('being somebody else\'s member counts as being out', async () => {
  const { app, ownerToken, friendToken } = setup();

  // Joined as a member, not an owner. The rule is about riding, and both
  // roles ride — the same rows GET /trips lists and suggested-invitees counts
  // as having ridden together.
  const host = await createTrip(app, ownerToken, 'Chiang Mai loop');
  const invited = await invite(app, ownerToken, host.body.id, 'friend@gmail.com');
  await authed(app, 'post', `/invites/${invited.body.id}/accept`, friendToken).send();

  const own = await createTrip(app, friendToken, "Friend's own ride");
  assert.equal(own.status, 409);
  assert.equal(own.body.active_trip.role, 'member');
});

test('an ended trip does not count, whoever owned it', async () => {
  const { db, app, ownerId, ownerToken } = setup();

  const first = await createTrip(app, ownerToken, 'Chiang Mai loop');
  await endTrip(app, ownerToken, first.body.id);

  assert.equal(activeTripFor(db, ownerId), undefined);
  assert.equal((await createTrip(app, ownerToken, 'Pai run')).status, 201);
});

// ─── The database backstop ──────────────────────────────────────────────────

test('the database refuses a second active membership even without a guard', async () => {
  const { db, app, ownerId, ownerToken } = setup();

  await createTrip(app, ownerToken, 'Chiang Mai loop');

  // Writing round the routes entirely — which is what a lost race between two
  // requests amounts to, and what a hand-written UPDATE on the server is.
  const otherTripId = Number(
    db
      .prepare("INSERT INTO trips (name, owner_id, status) VALUES ('Sneaked in', ?, 'active')")
      .run(ownerId).lastInsertRowid
  );

  assert.throws(
    () =>
      db
        .prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'owner')")
        .run(otherTripId, ownerId),
    /one active trip per rider/
  );
});

test('the database refuses reviving an ended trip out from under the rule', async () => {
  const { db, app, ownerToken } = setup();

  const first = await createTrip(app, ownerToken, 'Chiang Mai loop');
  await endTrip(app, ownerToken, first.body.id);
  await createTrip(app, ownerToken, 'Pai run');

  // No route does this — POST /trips/:id/end only ever sets 'ended' — but
  // DEPLOY.md documents reaching for sqlite3 on the server, and a constraint
  // that holds only for the paths the application happens to use is a
  // convention rather than a constraint.
  assert.throws(
    () => db.prepare("UPDATE trips SET status = 'active' WHERE id = ?").run(first.body.id),
    /one active trip per rider/
  );
});

test('joining an ended trip is not blocked by the rule', async () => {
  const { db, app, ownerId, ownerToken, hostToken } = setup();

  // Membership of a finished trip is history, not a ride, so it must not be
  // what stops somebody starting one — and adding it must not be refused for
  // a trip that is running.
  const past = await createTrip(app, hostToken, 'Last summer');
  await endTrip(app, hostToken, past.body.id);
  await createTrip(app, ownerToken, 'Chiang Mai loop');

  assert.doesNotThrow(() =>
    db
      .prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')")
      .run(past.body.id, ownerId)
  );
});
