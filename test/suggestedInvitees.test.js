import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';

const JWT_SECRET = 'test-secret';

function setup() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'] },
    verifyGoogleIdToken: async () => {
      throw new Error('unused in these tests');
    },
  });

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)'
  );
  const addUser = (name, email = `${name}@gmail.com`) =>
    Number(insertUser.run(`sub-${name}`, email, name).lastInsertRowid);

  // Every membership gets a later joined_at than the one before it, so
  // "most recently ridden with" has something deterministic to order by.
  const BASE_TIME = Date.parse('2026-01-01T00:00:00.000Z');
  let joinSequence = 0;
  const nextJoinedAt = () => {
    joinSequence += 1;
    return new Date(BASE_TIME + joinSequence * 60_000).toISOString();
  };

  /** A trip with the given members, joined in the order listed. */
  const addTrip = (name, ownerId, memberIds = []) => {
    const tripId = Number(
      db.prepare('INSERT INTO trips (name, owner_id) VALUES (?, ?)').run(name, ownerId)
        .lastInsertRowid
    );
    const insertMember = db.prepare(
      'INSERT INTO trip_members (trip_id, user_id, role, joined_at) VALUES (?, ?, ?, ?)'
    );
    insertMember.run(tripId, ownerId, 'owner', nextJoinedAt());
    for (const userId of memberIds) {
      insertMember.run(tripId, userId, 'member', nextJoinedAt());
    }
    return tripId;
  };

  return { db, app, addUser, addTrip, tokenFor: (id) => signAccessToken(id, JWT_SECRET) };
}

const suggestions = (app, tripId, token) =>
  supertest(app).get(`/trips/${tripId}/suggested-invitees`).set('Authorization', `Bearer ${token}`);

test('suggests riders from past trips, and not the ones already here', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const me = addUser('me');
  const oldFriend = addUser('oldfriend');
  const alreadyHere = addUser('alreadyhere');

  addTrip('Last year', me, [oldFriend, alreadyHere]);
  const newTrip = addTrip('This year', me, [alreadyHere]);

  const res = await suggestions(app, newTrip, tokenFor(me));

  assert.equal(res.status, 200);
  assert.deepEqual(
    res.body.map((each) => each.email),
    ['oldfriend@gmail.com']
  );
  assert.equal(res.body[0].user_id, oldFriend);
  assert.equal(res.body[0].display_name, 'oldfriend');
});

test('never suggests the rider asking', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const me = addUser('me');
  const friend = addUser('friend');

  addTrip('Last year', me, [friend]);
  const newTrip = addTrip('This year', me);

  const res = await suggestions(app, newTrip, tokenFor(me));

  assert.deepEqual(
    res.body.map((each) => each.user_id),
    [friend]
  );
});

test('someone met on a trip run by somebody else still counts', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const me = addUser('me');
  const host = addUser('host');
  const strangerAtTheStop = addUser('metonce');

  // A trip I was only a member of. The other guest is still someone I have
  // ridden with, which is the whole question this endpoint answers.
  addTrip('Somebody else\'s ride', host, [me, strangerAtTheStop]);
  const mine = addTrip('Mine', me);

  const res = await suggestions(app, mine, tokenFor(me));

  assert.deepEqual(
    res.body.map((each) => each.email).sort(),
    ['host@gmail.com', 'metonce@gmail.com']
  );
});

test('someone I have never ridden with is not suggested', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const me = addUser('me');
  const unrelated = addUser('unrelated');
  const theirFriend = addUser('theirfriend');

  addTrip('Not my trip', unrelated, [theirFriend]);
  const mine = addTrip('Mine', me);

  const res = await suggestions(app, mine, tokenFor(me));

  assert.deepEqual(res.body, []);
});

test('a rider met on several trips is offered once, at their most recent', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const me = addUser('me');
  const often = addUser('often');
  const once = addUser('once');

  addTrip('Ages ago', me, [often]);
  // `once` joined later than `often` did on the first trip, but `often` shows
  // up again after that — so `often` should sort ahead.
  addTrip('A while back', me, [once]);
  addTrip('Recently', me, [often, once]);
  const mine = addTrip('Mine', me);

  const res = await suggestions(app, mine, tokenFor(me));

  assert.equal(res.body.length, 2, JSON.stringify(res.body));
  assert.equal(new Set(res.body.map((each) => each.user_id)).size, 2);
});

test('at most ten are offered, most recently ridden with first', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const me = addUser('me');

  // Fifteen past companions, one trip each, in ascending order of when we
  // last rode together — so friend14 is the most recent.
  const companions = [];
  for (let i = 0; i < 15; i += 1) {
    const friend = addUser(`friend${i}`);
    companions.push(friend);
    addTrip(`Trip ${i}`, me, [friend]);
  }

  const mine = addTrip('Mine', me);
  const res = await suggestions(app, mine, tokenFor(me));

  assert.equal(res.body.length, 10);
  assert.deepEqual(
    res.body.map((each) => each.user_id),
    companions.slice(-10).reverse()
  );
});

test('members cannot see suggestions for a trip they do not own', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const owner = addUser('owner');
  const member = addUser('member');
  const stranger = addUser('stranger');
  const trip = addTrip('Pai run', owner, [member]);

  // Suggestions feed the invite form, which is owner-only — offering them to
  // someone whose tap would 403 would be worse than not offering them.
  assert.equal((await suggestions(app, trip, tokenFor(member))).status, 403);
  assert.equal((await suggestions(app, trip, tokenFor(stranger))).status, 403);
});

test('an ended trip has nothing to suggest', async () => {
  const { app, db, addUser, addTrip, tokenFor } = setup();
  const me = addUser('me');
  const friend = addUser('friend');
  addTrip('Last year', me, [friend]);
  const trip = addTrip('This year', me);
  db.prepare("UPDATE trips SET status = 'ended' WHERE id = ?").run(trip);

  assert.equal((await suggestions(app, trip, tokenFor(me))).status, 409);
});

test('suggestions need a signed-in rider', async () => {
  const { app, addUser, addTrip } = setup();
  const me = addUser('me');
  const trip = addTrip('Mine', me);

  assert.equal((await supertest(app).get(`/trips/${trip}/suggested-invitees`)).status, 401);
});
