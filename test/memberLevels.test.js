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
    'INSERT INTO users (google_sub, email, display_name, total_km) VALUES (?, ?, ?, ?)'
  );
  const addUser = (name, totalKm = 0) =>
    Number(insertUser.run(`sub-${name}`, `${name}@gmail.com`, name, totalKm).lastInsertRowid);

  const addTrip = (name, ownerId, memberIds = []) => {
    const tripId = Number(
      db.prepare('INSERT INTO trips (name, owner_id) VALUES (?, ?)').run(name, ownerId)
        .lastInsertRowid
    );
    const insertMember = db.prepare(
      'INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, ?)'
    );
    insertMember.run(tripId, ownerId, 'owner');
    for (const userId of memberIds) {
      insertMember.run(tripId, userId, 'member');
    }
    return tripId;
  };

  return { db, app, addUser, addTrip, tokenFor: (id) => signAccessToken(id, JWT_SECRET) };
}

const levels = (app, tripId, token) =>
  supertest(app).get(`/trips/${tripId}/member-levels`).set('Authorization', `Bearer ${token}`);

test('returns a level for every member of the trip', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const owner = addUser('owner', 1600);
  const friend = addUser('friend', 0);
  const tripId = addTrip('Mae Hong Son loop', owner, [friend]);

  const res = await levels(app, tripId, tokenFor(owner));

  assert.equal(res.status, 200);
  assert.equal(res.body.length, 2);

  const byUser = Object.fromEntries(res.body.map((row) => [row.user_id, row]));
  assert.equal(byUser[owner].level.name, 'Wanderer');
  assert.equal(byUser[owner].total_km, 1600);
  assert.equal(byUser[owner].next_level.name, 'Voyager');
  assert.equal(byUser[owner].km_to_next, 1900);

  // A rider who has never ridden still has a level — that is why the table
  // starts at zero kilometres.
  assert.equal(byUser[friend].level.name, 'Novice');
  assert.equal(byUser[friend].total_km, 0);
});

test('agrees with GET /me/level for the same rider', async () => {
  // The two routes exist because one is a batch and the other is not; if they
  // ever disagreed, a rider's level would change depending on which screen
  // they were looking at.
  const { app, addUser, addTrip, tokenFor } = setup();
  const rider = addUser('rider', 9001.5);
  const tripId = addTrip('Nan', rider);
  const token = tokenFor(rider);

  const batch = await levels(app, tripId, token);
  const own = await supertest(app).get('/me/level').set('Authorization', `Bearer ${token}`);

  const { user_id: _userId, ...mine } = batch.body.find((row) => row.user_id === rider);
  assert.deepEqual(mine, own.body);
  assert.equal(mine.level.name, 'Road Warrior');
});

test('a rider at the top of the table has nothing left to work towards', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const rider = addUser('legend', 25000);
  const tripId = addTrip('Everywhere', rider);

  const res = await levels(app, tripId, tokenFor(rider));

  assert.equal(res.body[0].level.name, 'Legendary');
  assert.equal(res.body[0].next_level, null);
  assert.equal(res.body[0].km_to_next, null);
});

test('a stranger cannot read a trip roster through it', async () => {
  const { app, addUser, addTrip, tokenFor } = setup();
  const owner = addUser('owner', 100);
  const stranger = addUser('stranger', 100);
  const tripId = addTrip('Private ride', owner);

  const res = await levels(app, tripId, tokenFor(stranger));

  assert.equal(res.status, 403);
});

test('requires a token', async () => {
  const { app, addUser, addTrip } = setup();
  const owner = addUser('owner', 100);
  const tripId = addTrip('Private ride', owner);

  const res = await supertest(app).get(`/trips/${tripId}/member-levels`);

  assert.equal(res.status, 401);
});

test('an ended trip still reports levels', async () => {
  // Levels are a lifetime figure, not a live one — the screen that shows them
  // still opens on a trip that finished last week.
  const { db, app, addUser, addTrip, tokenFor } = setup();
  const rider = addUser('rider', 700);
  const tripId = addTrip('Done', rider);
  db.prepare("UPDATE trips SET status = 'ended' WHERE id = ?").run(tripId);

  const res = await levels(app, tripId, tokenFor(rider));

  assert.equal(res.status, 200);
  assert.equal(res.body[0].level.name, 'Rookie Rider');
});
