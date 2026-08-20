import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { validateTripName, validateTripRename } from '../src/routes/trips.js';

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
  const addUser = (name) =>
    Number(insertUser.run(`sub-${name}`, `${name}@gmail.com`, name).lastInsertRowid);

  return { db, app, addUser, tokenFor: (id) => signAccessToken(id, JWT_SECRET) };
}

const CHIANG_MAI = { lat: 18.7883, lng: 98.9853, label: 'Chiang Mai' };

async function createTrip(app, token, name) {
  const res = await supertest(app)
    .post('/trips')
    .set('Authorization', `Bearer ${token}`)
    .send({ name });
  assert.equal(res.status, 201);
  return res.body;
}

// --- the rule itself -------------------------------------------------------

test('the name rule is the same object for create and rename', () => {
  // The point of extracting it: a limit that only agrees by coincidence is a
  // limit that will stop agreeing.
  assert.equal(validateTripName('  Pai loop  ').value, 'Pai loop');
  assert.equal(validateTripName('x'.repeat(60)).value, 'x'.repeat(60));
  assert.ok(validateTripName('x'.repeat(61)).error);
  assert.ok(validateTripName('   ').error);
  assert.ok(validateTripName(undefined).error);
  assert.ok(validateTripName(42).error);
});

test('a PATCH body without a name leaves the name alone', () => {
  assert.deepEqual(validateTripRename({}), { value: undefined });
  assert.deepEqual(validateTripRename({ origin: null }), { value: undefined });
  assert.deepEqual(validateTripRename(undefined), { value: undefined });
});

test('a name cannot be cleared, unlike an endpoint', () => {
  // The one place the rename deliberately parts company with origin and
  // destination: a nameless trip is a row nobody can pick out of a list.
  const cleared = validateTripRename({ name: null });

  assert.ok(cleared.error);
  assert.equal(cleared.value, undefined);
});

// --- the route -------------------------------------------------------------

test('the owner renames a trip and the new name comes back', async () => {
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const token = tokenFor(owner);
  const trip = await createTrip(app, token, 'Trip 3');

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ name: '  Mae Hong Son loop  ' });

  assert.equal(res.status, 200);
  assert.equal(res.body.name, 'Mae Hong Son loop');
  assert.equal(res.body.id, trip.id);
  assert.equal(res.body.role, 'owner');

  // And it stuck, rather than only being echoed back.
  const listed = await supertest(app).get('/trips').set('Authorization', `Bearer ${token}`);
  assert.equal(listed.body.find((t) => t.id === trip.id).name, 'Mae Hong Son loop');
});

test('a rename leaves the endpoints alone', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = tokenFor(addUser('owner'));
  const trip = await createTrip(app, token, 'Trip 3');

  await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ origin: CHIANG_MAI });

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ name: 'Pai run' });

  assert.equal(res.status, 200);
  assert.equal(res.body.name, 'Pai run');
  assert.equal(res.body.origin.label, 'Chiang Mai');
});

test('a name and an endpoint land in the same PATCH', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = tokenFor(addUser('owner'));
  const trip = await createTrip(app, token, 'Trip 3');

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ name: 'Pai run', destination: CHIANG_MAI });

  assert.equal(res.status, 200);
  assert.equal(res.body.name, 'Pai run');
  assert.equal(res.body.destination.label, 'Chiang Mai');
});

test('a member cannot rename somebody else’s trip', async () => {
  const { db, app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const member = addUser('member');
  const trip = await createTrip(app, tokenFor(owner), 'Trip 3');
  db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')").run(
    trip.id,
    member
  );

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${tokenFor(member)}`)
    .send({ name: 'mine now' });

  assert.equal(res.status, 403);

  const listed = await supertest(app).get('/trips').set('Authorization', `Bearer ${tokenFor(owner)}`);
  assert.equal(listed.body.find((t) => t.id === trip.id).name, 'Trip 3');
});

test('a rename is refused when it would leave the trip nameless', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = tokenFor(addUser('owner'));
  const trip = await createTrip(app, token, 'Trip 3');

  for (const body of [{ name: '' }, { name: '   ' }, { name: null }, { name: 7 }]) {
    const res = await supertest(app)
      .patch(`/trips/${trip.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send(body);

    assert.equal(res.status, 400, `expected 400 for ${JSON.stringify(body)}`);
  }

  const listed = await supertest(app).get('/trips').set('Authorization', `Bearer ${token}`);
  assert.equal(listed.body.find((t) => t.id === trip.id).name, 'Trip 3');
});

test('a rename is refused past 60 characters, the same as a create', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = tokenFor(addUser('owner'));
  const trip = await createTrip(app, token, 'Trip 3');

  const tooLong = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ name: 'x'.repeat(61) });
  assert.equal(tooLong.status, 400);

  const justFits = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ name: 'x'.repeat(60) });
  assert.equal(justFits.status, 200);
});

test('an ended trip can still be renamed', async () => {
  // Naming a ride afterwards is exactly when people do it — the same reason
  // the endpoints stayed patchable on a finished trip.
  const { app, addUser, tokenFor } = setup();
  const token = tokenFor(addUser('owner'));
  const trip = await createTrip(app, token, 'Trip 3');

  await supertest(app).post(`/trips/${trip.id}/end`).set('Authorization', `Bearer ${token}`);

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ name: 'The one where it rained' });

  assert.equal(res.status, 200);
  assert.equal(res.body.name, 'The one where it rained');
  assert.equal(res.body.status, 'ended');
});
