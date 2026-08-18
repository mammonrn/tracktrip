import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { validateTripEndpoint, validateTripEndpoints } from '../src/routes/trips.js';

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
const PAI = { lat: 19.3583, lng: 98.4406, label: 'Pai' };

// --- the schema ------------------------------------------------------------

test('migration 0009 adds the six endpoint columns, all nullable', () => {
  const db = new Database(':memory:');
  runMigrations(db, MIGRATIONS_DIR);

  const columns = db.prepare('PRAGMA table_info(trips)').all();
  const byName = Object.fromEntries(columns.map((c) => [c.name, c]));

  for (const name of [
    'origin_lat',
    'origin_lng',
    'origin_label',
    'destination_lat',
    'destination_lng',
    'destination_label',
  ]) {
    assert.ok(byName[name], `trips.${name} should exist`);
    // A trip created from the trip list has no idea where it is going yet.
    assert.equal(byName[name].notnull, 0, `trips.${name} must stay nullable`);
  }
});

test('a trip that predates the migration keeps working, with null endpoints', () => {
  // The upgrade path: rows already in the table get NULLs, and the API has to
  // read them as "not set" rather than falling over.
  const { db, app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const tripId = Number(
    db.prepare('INSERT INTO trips (name, owner_id) VALUES (?, ?)').run('Old trip', owner)
      .lastInsertRowid
  );
  db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'owner')").run(
    tripId,
    owner
  );

  return supertest(app)
    .get('/trips')
    .set('Authorization', `Bearer ${tokenFor(owner)}`)
    .then((res) => {
      assert.equal(res.status, 200);
      assert.equal(res.body[0].origin, null);
      assert.equal(res.body[0].destination, null);
    });
});

// --- validation ------------------------------------------------------------

test('an absent endpoint, a null one and a set one are three different things', () => {
  // Absent means "leave it alone", null means "clear it". Collapsing the two
  // would make it impossible to set a destination without wiping the origin.
  assert.equal(validateTripEndpoint({}, 'origin').value, undefined);
  assert.equal(validateTripEndpoint({ origin: null }, 'origin').value, null);
  assert.deepEqual(validateTripEndpoint({ origin: CHIANG_MAI }, 'origin').value, {
    lat: 18.7883,
    lng: 98.9853,
    label: 'Chiang Mai',
  });
});

test('half a coordinate is refused rather than stored', () => {
  // A pin drawn from latitude-and-no-longitude lands in the Gulf of Guinea.
  assert.match(validateTripEndpoint({ origin: { lat: 18.8 } }, 'origin').error, /lng/);
  assert.match(validateTripEndpoint({ origin: { lng: 98.9 } }, 'origin').error, /lat/);
  assert.match(
    validateTripEndpoint({ origin: { label: 'Somewhere' } }, 'origin').error,
    /lat/
  );
});

test('coordinates outside the world are refused', () => {
  assert.ok(validateTripEndpoint({ origin: { lat: 91, lng: 0 } }, 'origin').error);
  assert.ok(validateTripEndpoint({ origin: { lat: 0, lng: 181 } }, 'origin').error);
  assert.ok(validateTripEndpoint({ origin: { lat: 'x', lng: 0 } }, 'origin').error);
  assert.ok(validateTripEndpoint({ origin: { lat: NaN, lng: 0 } }, 'origin').error);
});

test('a label is optional, trimmed, and blank becomes null', () => {
  // A destination dropped from the map may have no name; an empty string is
  // not a name either.
  assert.equal(validateTripEndpoint({ origin: { lat: 1, lng: 2 } }, 'origin').value.label, null);
  assert.equal(
    validateTripEndpoint({ origin: { lat: 1, lng: 2, label: '  Pai  ' } }, 'origin').value.label,
    'Pai'
  );
  assert.equal(
    validateTripEndpoint({ origin: { lat: 1, lng: 2, label: '   ' } }, 'origin').value.label,
    null
  );
});

test('a label that is not a string, or is absurdly long, is refused', () => {
  assert.ok(validateTripEndpoint({ origin: { lat: 1, lng: 2, label: 7 } }, 'origin').error);
  assert.ok(
    validateTripEndpoint({ origin: { lat: 1, lng: 2, label: 'x'.repeat(121) } }, 'origin').error
  );
});

test('an endpoint that is not an object at all is refused', () => {
  assert.ok(validateTripEndpoint({ origin: 'Chiang Mai' }, 'origin').error);
  assert.ok(validateTripEndpoint({ origin: [18.7, 98.9] }, 'origin').error);
});

test('the two ends are validated independently', () => {
  const result = validateTripEndpoints({ origin: CHIANG_MAI, destination: { lat: 999 } });
  assert.match(result.error, /destination/);
});

// --- creating --------------------------------------------------------------

test('a trip can be created with both ends, and reads them back', async () => {
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');

  const res = await supertest(app)
    .post('/trips')
    .set('Authorization', `Bearer ${tokenFor(owner)}`)
    .send({ name: 'Pai loop', origin: CHIANG_MAI, destination: PAI });

  assert.equal(res.status, 201);
  assert.deepEqual(res.body.origin, { lat: 18.7883, lng: 98.9853, label: 'Chiang Mai' });
  assert.deepEqual(res.body.destination, { lat: 19.3583, lng: 98.4406, label: 'Pai' });
});

test('a trip created without them is still a trip', async () => {
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');

  const res = await supertest(app)
    .post('/trips')
    .set('Authorization', `Bearer ${tokenFor(owner)}`)
    .send({ name: 'Somewhere, eventually' });

  assert.equal(res.status, 201);
  assert.equal(res.body.origin, null);
  assert.equal(res.body.destination, null);
});

test('only one end may be set', async () => {
  // Setting off from a known place with no plan is an ordinary way to ride.
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');

  const res = await supertest(app)
    .post('/trips')
    .set('Authorization', `Bearer ${tokenFor(owner)}`)
    .send({ name: 'North', origin: CHIANG_MAI });

  assert.equal(res.status, 201);
  assert.ok(res.body.origin);
  assert.equal(res.body.destination, null);
});

test('a bad endpoint fails the whole create rather than being dropped', async () => {
  const { db, app, addUser, tokenFor } = setup();
  const owner = addUser('owner');

  const res = await supertest(app)
    .post('/trips')
    .set('Authorization', `Bearer ${tokenFor(owner)}`)
    .send({ name: 'Bad', destination: { lat: 18.8, lng: 400 } });

  assert.equal(res.status, 400);
  // Nothing half-created: a rejected request must not leave a trip behind.
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM trips').get().n, 0);
});

// --- editing ---------------------------------------------------------------

async function createTrip(app, token, body = { name: 'Trip' }) {
  const res = await supertest(app).post('/trips').set('Authorization', `Bearer ${token}`).send(body);
  return res.body;
}

test('endpoints can be filled in after the trip was created', async () => {
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const token = tokenFor(owner);
  const trip = await createTrip(app, token);

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ origin: CHIANG_MAI, destination: PAI });

  assert.equal(res.status, 200);
  assert.equal(res.body.origin.label, 'Chiang Mai');
  assert.equal(res.body.destination.label, 'Pai');
  assert.equal(res.body.role, 'owner');
});

test('a patch touches only the field it names', async () => {
  // The reason this is a PATCH: setting a destination must not wipe an origin.
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const token = tokenFor(owner);
  const trip = await createTrip(app, token, { name: 'Trip', origin: CHIANG_MAI });

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ destination: PAI });

  assert.equal(res.body.origin.label, 'Chiang Mai');
  assert.equal(res.body.destination.label, 'Pai');
});

test('sending null clears an endpoint', async () => {
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const token = tokenFor(owner);
  const trip = await createTrip(app, token, {
    name: 'Trip',
    origin: CHIANG_MAI,
    destination: PAI,
  });

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ destination: null });

  assert.equal(res.body.origin.label, 'Chiang Mai');
  assert.equal(res.body.destination, null);
});

test('an empty patch is a no-op, not an error', async () => {
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const token = tokenFor(owner);
  const trip = await createTrip(app, token, { name: 'Trip', origin: CHIANG_MAI });

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({});

  assert.equal(res.status, 200);
  assert.equal(res.body.origin.label, 'Chiang Mai');
});

test('a member who is not the owner cannot set them', async () => {
  const { db, app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const member = addUser('member');
  const trip = await createTrip(app, tokenFor(owner));
  db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')").run(
    trip.id,
    member
  );

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${tokenFor(member)}`)
    .send({ destination: PAI });

  assert.equal(res.status, 403);
});

test('a stranger gets 403, an unknown trip 404, and no token 401', async () => {
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const stranger = addUser('stranger');
  const trip = await createTrip(app, tokenFor(owner));

  const forbidden = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${tokenFor(stranger)}`)
    .send({ destination: PAI });
  assert.equal(forbidden.status, 403);

  const missing = await supertest(app)
    .patch('/trips/9999')
    .set('Authorization', `Bearer ${tokenFor(owner)}`)
    .send({ destination: PAI });
  assert.equal(missing.status, 404);

  const anonymous = await supertest(app).patch(`/trips/${trip.id}`).send({ destination: PAI });
  assert.equal(anonymous.status, 401);
});

test('an ended trip can still have its endpoints corrected', async () => {
  // Filling in where a ride actually finished is something people do
  // afterwards; nothing about it writes a position.
  const { db, app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const token = tokenFor(owner);
  const trip = await createTrip(app, token);
  db.prepare("UPDATE trips SET status = 'ended' WHERE id = ?").run(trip.id);

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ destination: PAI });

  assert.equal(res.status, 200);
  assert.equal(res.body.destination.label, 'Pai');
});

test('a bad patch changes nothing', async () => {
  const { app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const token = tokenFor(owner);
  const trip = await createTrip(app, token, { name: 'Trip', origin: CHIANG_MAI });

  const res = await supertest(app)
    .patch(`/trips/${trip.id}`)
    .set('Authorization', `Bearer ${token}`)
    .send({ origin: { lat: 18.8, lng: 'east' } });

  assert.equal(res.status, 400);

  const after = await supertest(app).get('/trips').set('Authorization', `Bearer ${token}`);
  assert.equal(after.body[0].origin.label, 'Chiang Mai');
});

test('endpoints are visible to every member, not just the owner', async () => {
  // The map draws them for the whole group, so an ordinary member has to be
  // able to read them.
  const { db, app, addUser, tokenFor } = setup();
  const owner = addUser('owner');
  const member = addUser('member');
  const trip = await createTrip(app, tokenFor(owner), {
    name: 'Trip',
    origin: CHIANG_MAI,
    destination: PAI,
  });
  db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')").run(
    trip.id,
    member
  );

  const res = await supertest(app)
    .get('/trips')
    .set('Authorization', `Bearer ${tokenFor(member)}`);

  assert.equal(res.body[0].destination.label, 'Pai');
  assert.equal(res.body[0].role, 'member');
});
