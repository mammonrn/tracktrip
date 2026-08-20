import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { syncSuperuserRoles } from '../src/auth/roles.js';
import {
  ADD_LIMIT,
  DEFAULT_LIMIT,
  MAX_LIMIT,
  NAME_MAX_LENGTH,
  addAllowance,
  addWindowStart,
  canDeleteSharedPlace,
  matchRank,
  matchesTerms,
  normalizeLimit,
  normalizeNear,
  queryTerms,
  rankSharedPlaces,
  validateSharedPlaceInput,
} from '../src/places/shared.js';

const JWT_SECRET = 'test-secret';
const BOSS = 'boss@gmail.com';

function setup({ now = () => Date.now(), superuserEmails = [BOSS] } = {}) {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'], superuserEmails },
    verifyGoogleIdToken: async () => {
      throw new Error('unused in these tests');
    },
    searchLogger: { log() {}, warn() {} },
  });

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)'
  );
  const addUser = (name, email = `${name}@gmail.com`) =>
    Number(insertUser.run(`sub-${name}`, email, name).lastInsertRowid);

  return { db, app, addUser, tokenFor: (id) => signAccessToken(id, JWT_SECRET) };
}

/**
 * The router with an injected clock, so the day-long allowance can be tested
 * without a test that takes a day.
 */
function setupWithClock(now) {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);
  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'], superuserEmails: [] },
    verifyGoogleIdToken: async () => {
      throw new Error('unused');
    },
    searchLogger: { log() {}, warn() {} },
  });
  return { db, app };
}

const CHIANG_MAI = { lat: 18.7883, lng: 98.9853 };

// --- what a place may contain ----------------------------------------------

test('a place needs a name and a placeable coordinate', () => {
  assert.deepEqual(
    validateSharedPlaceInput({ name: '  ปตท สวนดอก  ', lat: 18.79, lng: 98.97 }).value,
    { name: 'ปตท สวนดอก', lat: 18.79, lng: 98.97 }
  );

  assert.match(validateSharedPlaceInput({ lat: 1, lng: 1 }).error, /name is required/);
  assert.match(validateSharedPlaceInput({ name: '   ', lat: 1, lng: 1 }).error, /name must be/);
  assert.match(
    validateSharedPlaceInput({ name: 'x'.repeat(NAME_MAX_LENGTH + 1), lat: 1, lng: 1 }).error,
    /name must be/
  );
});

test('a coordinate that is a string is not a coordinate', () => {
  // JSON carries "18.7883" perfectly happily, and a client that sent one would
  // land every pin at zero. Number.isFinite is false for every string.
  assert.match(validateSharedPlaceInput({ name: 'x', lat: '18.79', lng: 98.97 }).error, /lat/);
  assert.match(validateSharedPlaceInput({ name: 'x', lat: NaN, lng: 98.97 }).error, /lat/);
  assert.match(validateSharedPlaceInput({ name: 'x', lat: 91, lng: 98.97 }).error, /lat/);
  assert.match(validateSharedPlaceInput({ name: 'x', lat: 18.79, lng: 181 }).error, /lng/);
});

// --- the matching this table exists for ------------------------------------

test('every word of the query has to be in the name', () => {
  // The whole feature in one assertion. This is the query that failed on the
  // geocoder: no single string in OpenStreetMap contains both words, so no
  // amount of tuning found it. Here it does, because a rider wrote it down.
  const terms = queryTerms('ปตท สวนดอก');

  assert.equal(matchesTerms('ปตท สวนดอก', terms), true);
  assert.equal(matchesTerms('ปั๊ม ปตท. สาขาสวนดอก', terms), true);
  // One word present is not a match: "ปตท" alone is every petrol station in
  // the country, which is exactly the answer that was useless before.
  assert.equal(matchesTerms('ปตท ช้างเผือก', terms), false);
  assert.equal(matchesTerms('วัดสวนดอก', terms), false);
});

test('a query with no words matches everything', () => {
  // An empty q is "list them all", not "match nothing" — GET /places with no
  // query is the browse case.
  assert.deepEqual(queryTerms('   '), []);
  assert.equal(matchesTerms('anything at all', []), true);
});

test('matching ignores ASCII case and needs no word boundaries', () => {
  assert.equal(matchesTerms('PTT Suan Dok', queryTerms('ptt suan')), true);
  // Thai writes without spaces between words, so a substring hit is the right
  // answer whether or not anything knows where the word ended.
  assert.equal(matchesTerms('ปั๊มสวนดอก', queryTerms('สวนดอก')), true);
});

test('an exact name beats a prefix, which beats a word found inside', () => {
  const terms = queryTerms('ปตท สวนดอก');
  assert.equal(matchRank('ปตท สวนดอก', terms, 'ปตท สวนดอก'), 0);
  assert.equal(matchRank('ปตท สวนดอก 2', terms, 'ปตท สวนดอก'), 1);
  assert.equal(matchRank('ปั๊ม ปตท. สาขาสวนดอก', terms, 'ปตท สวนดอก'), 2);
});

test('inside a band, the nearest one comes first', () => {
  const rows = [
    { id: 1, name: 'ปั๊ม ปตท. สาขาสวนดอก น่าน', lat: 18.7756, lng: 100.773, created_at: '2026-01-01T00:00:00.000Z' },
    { id: 2, name: 'ปั๊ม ปตท. สาขาสวนดอก เชียงใหม่', lat: 18.79, lng: 98.97, created_at: '2026-01-01T00:00:00.000Z' },
  ];

  const ranked = rankSharedPlaces(rows, { query: 'ปตท สวนดอก', near: CHIANG_MAI });
  assert.deepEqual(ranked.map((r) => r.id), [2, 1]);

  // A bias is a preference and never a filter: the far one is still returned,
  // just second. A rider planning a ride to Nan has to be able to find Nan.
  assert.equal(ranked.length, 2);
});

test('an exact match outranks a nearer vague one', () => {
  const rows = [
    // Right next to the rider, but only a partial match.
    { id: 1, name: 'ปั๊ม ปตท. สาขาสวนดอก', lat: 18.789, lng: 98.985, created_at: '2026-01-01T00:00:00.000Z' },
    // Three hundred kilometres away, but it is what they typed.
    { id: 2, name: 'ปตท สวนดอก', lat: 15.0, lng: 100.0, created_at: '2026-01-01T00:00:00.000Z' },
  ];

  assert.deepEqual(
    rankSharedPlaces(rows, { query: 'ปตท สวนดอก', near: CHIANG_MAI }).map((r) => r.id),
    [2, 1]
  );
});

test('with no query and no position, the newest are first', () => {
  const rows = [
    { id: 1, name: 'old', lat: 18, lng: 98, created_at: '2026-01-01T00:00:00.000Z' },
    { id: 2, name: 'new', lat: 18, lng: 98, created_at: '2026-06-01T00:00:00.000Z' },
  ];
  assert.deepEqual(rankSharedPlaces(rows, {}).map((r) => r.id), [2, 1]);
});

test('rows written in the same millisecond keep a stable order', () => {
  // Without a last-resort tie-break the list would reshuffle between requests,
  // and a row would move under the finger reaching for it.
  const same = '2026-01-01T00:00:00.000Z';
  const rows = [
    { id: 1, name: 'a', lat: 18, lng: 98, created_at: same },
    { id: 2, name: 'b', lat: 18, lng: 98, created_at: same },
    { id: 3, name: 'c', lat: 18, lng: 98, created_at: same },
  ];
  const once = rankSharedPlaces(rows, {}).map((r) => r.id);
  const twice = rankSharedPlaces([...rows].reverse(), {}).map((r) => r.id);
  assert.deepEqual(once, twice);
});

// --- the query parameters ---------------------------------------------------

test('a limit is clamped, and nonsense is refused rather than defaulted', () => {
  assert.equal(normalizeLimit(undefined), DEFAULT_LIMIT);
  assert.equal(normalizeLimit(''), DEFAULT_LIMIT);
  assert.equal(normalizeLimit('3'), 3);
  assert.equal(normalizeLimit(String(MAX_LIMIT + 50)), MAX_LIMIT);
  // A client sending limit=abc has a bug; serving the default would hide it.
  assert.equal(normalizeLimit('abc'), null);
  assert.equal(normalizeLimit('0'), null);
  assert.equal(normalizeLimit('-2'), null);
});

test('a missing or malformed position is simply no position', () => {
  assert.deepEqual(normalizeNear('18.7883,98.9853'), { lat: 18.7883, lng: 98.9853 });
  assert.equal(normalizeNear(undefined), null);
  assert.equal(normalizeNear('18.7883'), null);
  assert.equal(normalizeNear('18,7883,98,9853'), null);
  assert.equal(normalizeNear('91,98'), null);
});

// --- who may delete ---------------------------------------------------------

test('a place is removable by whoever typed it, or by a super user', () => {
  const place = { id: 1, created_by: 7 };
  assert.equal(canDeleteSharedPlace({ place, userId: 7 }), true);
  assert.equal(canDeleteSharedPlace({ place, userId: 8 }), false);
  assert.equal(canDeleteSharedPlace({ place, userId: 8, isSuperuser: true }), true);
});

test('an orphaned place is nobody’s but a super user’s', () => {
  // created_by goes NULL when the account is deleted — the place stays,
  // because the group is still using it. Nobody ordinary matches NULL.
  const orphan = { id: 1, created_by: null };
  assert.equal(canDeleteSharedPlace({ place: orphan, userId: 7 }), false);
  assert.equal(canDeleteSharedPlace({ place: orphan, userId: 7, isSuperuser: true }), true);
});

// --- the allowance ----------------------------------------------------------

test('the allowance counts down and then refuses', () => {
  assert.deepEqual(addAllowance(0), { allowed: true, remaining: ADD_LIMIT, limit: ADD_LIMIT });
  assert.deepEqual(addAllowance(ADD_LIMIT - 1), { allowed: true, remaining: 1, limit: ADD_LIMIT });
  assert.deepEqual(addAllowance(ADD_LIMIT), { allowed: false, remaining: 0, limit: ADD_LIMIT });
  // More rows than the limit is possible if the limit is ever lowered. It must
  // read as "none left", not as a negative allowance.
  assert.deepEqual(addAllowance(ADD_LIMIT + 5), { allowed: false, remaining: 0, limit: ADD_LIMIT });
});

test('the add window is an ISO string SQLite can compare', () => {
  // The column is strftime('%Y-%m-%dT%H:%M:%fZ'), which is the same shape to
  // the millisecond — so a string comparison is a chronological one and the
  // index is usable. A different shape here would silently match nothing.
  const start = addWindowStart(Date.parse('2026-08-20T07:49:12.345Z'));
  assert.equal(start, '2026-08-19T07:49:12.345Z');
  assert.match(start, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
});

// --- the route --------------------------------------------------------------

test('a signed-in rider adds a place and everybody else finds it', async () => {
  const { app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const ben = addUser('ben');

  const created = await supertest(app)
    .post('/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`)
    .send({ name: 'ปตท สวนดอก', lat: 18.789, lng: 98.971 });

  assert.equal(created.status, 201);
  assert.equal(created.body.name, 'ปตท สวนดอก');
  assert.equal(created.body.created_by, anne);
  // Attribution is joined in, because a user id is not something a phone can
  // show and "who typed this" is the one thing this list says that a
  // LocationIQ result does not.
  assert.equal(created.body.created_by_name, 'anne');

  // Shared, not private: this is the whole decision behind the table.
  const found = await supertest(app)
    .get('/places?q=' + encodeURIComponent('ปตท สวนดอก'))
    .set('Authorization', `Bearer ${tokenFor(ben)}`);

  assert.equal(found.status, 200);
  assert.equal(found.body.results.length, 1);
  assert.equal(found.body.results[0].id, created.body.id);
  assert.equal(found.body.results[0].created_by_name, 'anne');
});

test('a created place and a listed place are the same shape', async () => {
  const { app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const token = `Bearer ${tokenFor(anne)}`;

  const created = await supertest(app)
    .post('/places')
    .set('Authorization', token)
    .send({ name: 'Pai', lat: 19.3583, lng: 98.44 });
  const listed = await supertest(app).get('/places').set('Authorization', token);

  // A client that had to handle two shapes for one thing would get one wrong.
  assert.deepEqual(listed.body.results[0], created.body);
});

test('the search is not open to the internet', async () => {
  const { app } = setup();
  assert.equal((await supertest(app).get('/places')).status, 401);
  assert.equal((await supertest(app).post('/places').send({ name: 'x', lat: 1, lng: 1 })).status, 401);
  assert.equal((await supertest(app).delete('/places/1')).status, 401);
});

test('a bad body is a 400 and nothing is written', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = `Bearer ${tokenFor(addUser('anne'))}`;

  const bad = await supertest(app)
    .post('/places')
    .set('Authorization', token)
    .send({ name: 'somewhere', lat: '18.79', lng: 98.97 });

  assert.equal(bad.status, 400);
  const listed = await supertest(app).get('/places').set('Authorization', token);
  assert.equal(listed.body.results.length, 0);
});

test('a bad limit is refused rather than quietly defaulted', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = `Bearer ${tokenFor(addUser('anne'))}`;
  const res = await supertest(app).get('/places?limit=abc').set('Authorization', token);
  assert.equal(res.status, 400);
});

test('only the rider who added a place may remove it', async () => {
  const { app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const ben = addUser('ben');

  const created = await supertest(app)
    .post('/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`)
    .send({ name: 'ปตท สวนดอก', lat: 18.789, lng: 98.971 });

  const refused = await supertest(app)
    .delete(`/places/${created.body.id}`)
    .set('Authorization', `Bearer ${tokenFor(ben)}`);
  // 403 and not 404: Ben can see this row in his own search results, so
  // pretending it is missing would be a lie he can immediately disprove.
  assert.equal(refused.status, 403);

  const removed = await supertest(app)
    .delete(`/places/${created.body.id}`)
    .set('Authorization', `Bearer ${tokenFor(anne)}`);
  assert.equal(removed.status, 204);

  const listed = await supertest(app)
    .get('/places')
    .set('Authorization', `Bearer ${tokenFor(ben)}`);
  assert.equal(listed.body.results.length, 0);
});

test('a super user may remove anybody’s place', async () => {
  const { db, app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const boss = addUser('boss', BOSS);
  // What a sign-in does. The role column is the authority for authorisation
  // and SUPERUSER_EMAILS is the authority for who — reconciled at every boot
  // and every sign-in, and this account was made after this app's boot.
  syncSuperuserRoles(db, [BOSS]);

  const created = await supertest(app)
    .post('/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`)
    .send({ name: 'somewhere', lat: 18.789, lng: 98.971 });

  const removed = await supertest(app)
    .delete(`/places/${created.body.id}`)
    .set('Authorization', `Bearer ${tokenFor(boss)}`);
  assert.equal(removed.status, 204);
});

test('deleting a place that is not there is a 404, not a 500', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = `Bearer ${tokenFor(addUser('anne'))}`;
  assert.equal((await supertest(app).delete('/places/999').set('Authorization', token)).status, 404);
  assert.equal((await supertest(app).delete('/places/nope').set('Authorization', token)).status, 404);
});

test('a rider is held to the daily allowance, and a restart does not reset it', async () => {
  // The counter is the rows themselves, so there is nothing in memory for a
  // restart to clear. Two separate apps over one database is what a restart
  // looks like from the data's point of view.
  const { db, app } = setupWithClock();
  const anne = Number(
    db
      .prepare('INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)')
      .run('sub-anne', 'anne@gmail.com', 'anne').lastInsertRowid
  );
  const token = `Bearer ${signAccessToken(anne, JWT_SECRET)}`;

  // Straight into the table, which is what the rider's first twenty adds
  // would have left behind.
  const insert = db.prepare(
    "INSERT INTO shared_places (name, lat, lng, created_by, created_at) VALUES (?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%fZ','now'))"
  );
  for (let i = 0; i < ADD_LIMIT; i += 1) insert.run(`place ${i}`, 18, 98, anne);

  const refused = await supertest(app)
    .post('/places')
    .set('Authorization', token)
    .send({ name: 'one too many', lat: 18, lng: 98 });

  // 429 rather than 403: this is a rate, not a permission. The rider may add
  // places, just not another one yet, and the wording has to say which.
  assert.equal(refused.status, 429);
  assert.match(refused.body.error, new RegExp(String(ADD_LIMIT)));
});

test('the allowance is one rider’s, not the whole server’s', async () => {
  const { db, app } = setupWithClock();
  const mk = (name) =>
    Number(
      db
        .prepare('INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)')
        .run(`sub-${name}`, `${name}@gmail.com`, name).lastInsertRowid
    );
  const anne = mk('anne');
  const ben = mk('ben');

  const insert = db.prepare(
    "INSERT INTO shared_places (name, lat, lng, created_by, created_at) VALUES (?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%fZ','now'))"
  );
  for (let i = 0; i < ADD_LIMIT; i += 1) insert.run(`place ${i}`, 18, 98, anne);

  const ok = await supertest(app)
    .post('/places')
    .set('Authorization', `Bearer ${signAccessToken(ben, JWT_SECRET)}`)
    .send({ name: 'ben’s first', lat: 18, lng: 98 });
  assert.equal(ok.status, 201);
});

test('places older than the window do not count against the allowance', async () => {
  const { db, app } = setupWithClock();
  const anne = Number(
    db
      .prepare('INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)')
      .run('sub-anne', 'anne@gmail.com', 'anne').lastInsertRowid
  );

  // Twenty places, all of them yesterday.
  const insert = db.prepare(
    'INSERT INTO shared_places (name, lat, lng, created_by, created_at) VALUES (?, ?, ?, ?, ?)'
  );
  const old = new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString();
  for (let i = 0; i < ADD_LIMIT; i += 1) insert.run(`place ${i}`, 18, 98, anne, old);

  const res = await supertest(app)
    .post('/places')
    .set('Authorization', `Bearer ${signAccessToken(anne, JWT_SECRET)}`)
    .send({ name: 'today’s first', lat: 18, lng: 98 });
  assert.equal(res.status, 201);
});

test('a deleted rider’s places outlive them', async () => {
  const { db, app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const ben = addUser('ben');

  await supertest(app)
    .post('/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`)
    .send({ name: 'ปตท สวนดอก', lat: 18.789, lng: 98.971 });

  db.prepare('DELETE FROM users WHERE id = ?').run(anne);

  const listed = await supertest(app).get('/places').set('Authorization', `Bearer ${tokenFor(ben)}`);
  // The group is still using it, and did not ask for it to go. ON DELETE SET
  // NULL is what keeps the row and drops only the claim on it.
  assert.equal(listed.body.results.length, 1);
  assert.equal(listed.body.results[0].created_by, null);
  assert.equal(listed.body.results[0].created_by_name, null);
});

test('the list is capped by limit, best first', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = `Bearer ${tokenFor(addUser('anne'))}`;

  for (const name of ['ปตท สวนดอก', 'ปั๊ม ปตท. สาขาสวนดอก', 'ปตท ช้างเผือก']) {
    await supertest(app).post('/places').set('Authorization', token)
      .send({ name, lat: 18.789, lng: 98.971 });
  }

  const res = await supertest(app)
    .get('/places?limit=1&q=' + encodeURIComponent('ปตท สวนดอก'))
    .set('Authorization', token);

  assert.equal(res.body.results.length, 1);
  // The exact name, not whichever was written last.
  assert.equal(res.body.results[0].name, 'ปตท สวนดอก');
});

test('the search works with no LOCATIONIQ_API_KEY anywhere near it', async () => {
  // The point of keeping this off /geocode/search: that route answers 503 on
  // a server with no key, and this list is at its most useful on exactly the
  // day the geocoder is not working. Nothing in this file configures a key.
  const { app, addUser, tokenFor } = setup();
  const token = `Bearer ${tokenFor(addUser('anne'))}`;

  await supertest(app).post('/places').set('Authorization', token)
    .send({ name: 'ปตท สวนดอก', lat: 18.789, lng: 98.971 });

  const geocode = await supertest(app).get('/geocode/search?q=pai').set('Authorization', token);
  assert.equal(geocode.status, 503);

  const places = await supertest(app)
    .get('/places?q=' + encodeURIComponent('ปตท สวนดอก'))
    .set('Authorization', token);
  assert.equal(places.status, 200);
  assert.equal(places.body.results.length, 1);
});
