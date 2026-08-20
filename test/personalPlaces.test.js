import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { syncSuperuserRoles } from '../src/auth/roles.js';
import {
  LABEL_MAX_LENGTH,
  MAX_PER_USER,
  NAME_MAX_LENGTH,
  canAccessPersonalPlace,
  hasRoomForAnother,
  serializePersonalPlace,
  validatePersonalPlaceInput,
} from '../src/places/personal.js';

const JWT_SECRET = 'test-secret';
const BOSS = 'boss@gmail.com';

function setup({ superuserEmails = [BOSS] } = {}) {
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

const HOME = { label: 'บ้าน', name: 'คอนโด ดิ แอสตร้า', lat: 18.789, lng: 98.971 };

// --- what a personal place may contain --------------------------------------

test('a personal place needs a label, a name and a placeable point', () => {
  assert.deepEqual(validatePersonalPlaceInput({ ...HOME }).value, {
    label: 'บ้าน',
    name: 'คอนโด ดิ แอสตร้า',
    lat: 18.789,
    lng: 98.971,
  });

  assert.match(validatePersonalPlaceInput({ ...HOME, label: undefined }).error, /label is required/);
  assert.match(validatePersonalPlaceInput({ ...HOME, label: '   ' }).error, /label must be/);
  assert.match(
    validatePersonalPlaceInput({ ...HOME, label: 'x'.repeat(LABEL_MAX_LENGTH + 1) }).error,
    /label must be/
  );
  assert.match(validatePersonalPlaceInput({ ...HOME, name: undefined }).error, /name is required/);
  assert.match(
    validatePersonalPlaceInput({ ...HOME, name: 'x'.repeat(NAME_MAX_LENGTH + 1) }).error,
    /name must be/
  );
  assert.match(validatePersonalPlaceInput({ ...HOME, lat: '18.79' }).error, /lat/);
  assert.match(validatePersonalPlaceInput({ ...HOME, lng: 181 }).error, /lng/);
});

test('a body cannot name whose list it goes into', () => {
  // user_id is taken from the session and nowhere else. If it were ever read
  // from the body, this is the test that would have caught it.
  const value = validatePersonalPlaceInput({ ...HOME, user_id: 999 }).value;

  assert.deepEqual(Object.keys(value).sort(), ['label', 'lat', 'lng', 'name']);
});

test('the owner is never serialised back', () => {
  // The caller *is* the owner — that is the only way a row reaches them — so
  // the field says nothing, and a field that says nothing ends up in a log.
  const wire = serializePersonalPlace({
    id: 1,
    user_id: 7,
    label: 'บ้าน',
    name: 'home',
    lat: 1,
    lng: 2,
    created_at: 'x',
  });

  assert.equal(wire.user_id, undefined);
  assert.deepEqual(Object.keys(wire).sort(), ['created_at', 'id', 'label', 'lat', 'lng', 'name']);
});

// --- the access rule --------------------------------------------------------

test('a personal place is reachable by its owner and by nobody else', () => {
  const place = { id: 1, user_id: 7 };

  assert.equal(canAccessPersonalPlace({ place, userId: 7 }), true);
  assert.equal(canAccessPersonalPlace({ place, userId: 8 }), false);
  // No super-user branch exists, by design. Somebody administers the shared
  // list; nobody administers a home address.
  assert.equal(canAccessPersonalPlace({ place, userId: 8, isSuperuser: true }), false);
  // A missing row and a row that is not yours are one answer, so the route
  // can give one reply to both.
  assert.equal(canAccessPersonalPlace({ place: undefined, userId: 7 }), false);
  assert.equal(canAccessPersonalPlace({ place, userId: null }), false);
  assert.equal(canAccessPersonalPlace({ place, userId: undefined }), false);
});

test('the list has a ceiling, because the shortcuts are drawn as chips', () => {
  assert.equal(hasRoomForAnother(0), true);
  assert.equal(hasRoomForAnother(MAX_PER_USER - 1), true);
  assert.equal(hasRoomForAnother(MAX_PER_USER), false);
  assert.equal(hasRoomForAnother(MAX_PER_USER + 5), false);
});

// --- the route --------------------------------------------------------------

test('a rider saves a place and reads it back', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = `Bearer ${tokenFor(addUser('anne'))}`;

  const created = await supertest(app).post('/me/places').set('Authorization', token).send(HOME);
  assert.equal(created.status, 201);
  assert.equal(created.body.label, 'บ้าน');
  assert.equal(created.body.name, 'คอนโด ดิ แอสตร้า');
  assert.equal(created.body.user_id, undefined);

  const listed = await supertest(app).get('/me/places').set('Authorization', token);
  assert.equal(listed.status, 200);
  assert.equal(listed.body.results.length, 1);
  assert.deepEqual(listed.body.results[0], created.body);
});

test('ANOTHER RIDER SEES NOTHING — the whole point of this table', async () => {
  const { app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const ben = addUser('ben');

  await supertest(app)
    .post('/me/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`)
    .send(HOME);

  const bensList = await supertest(app)
    .get('/me/places')
    .set('Authorization', `Bearer ${tokenFor(ben)}`);

  assert.equal(bensList.status, 200);
  assert.deepEqual(bensList.body.results, []);
});

test('a super user sees nothing either', async () => {
  const { db, app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const boss = addUser('boss', BOSS);
  syncSuperuserRoles(db, [BOSS]);

  const created = await supertest(app)
    .post('/me/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`)
    .send(HOME);

  // Reading the whole system is what a super user is for. A home address is
  // the one thing it is deliberately not for.
  const bossList = await supertest(app)
    .get('/me/places')
    .set('Authorization', `Bearer ${tokenFor(boss)}`);
  assert.deepEqual(bossList.body.results, []);

  const bossDelete = await supertest(app)
    .delete(`/me/places/${created.body.id}`)
    .set('Authorization', `Bearer ${tokenFor(boss)}`);
  assert.equal(bossDelete.status, 404);

  // And it is still there for its owner.
  const stillThere = await supertest(app)
    .get('/me/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`);
  assert.equal(stillThere.body.results.length, 1);
});

test("deleting somebody else's place says 'not there', not 'not yours'", async () => {
  const { app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const ben = addUser('ben');

  const created = await supertest(app)
    .post('/me/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`)
    .send(HOME);

  const refused = await supertest(app)
    .delete(`/me/places/${created.body.id}`)
    .set('Authorization', `Bearer ${tokenFor(ben)}`);

  // 404 and not 403 — the opposite of the shared list, on purpose. A 403 would
  // confirm the row exists and belongs to somebody, which is precisely the
  // thing that must not leak here.
  assert.equal(refused.status, 404);

  const missing = await supertest(app)
    .delete('/me/places/999999')
    .set('Authorization', `Bearer ${tokenFor(ben)}`);
  // Indistinguishable from the above, which is the point.
  assert.equal(missing.status, refused.status);
  assert.deepEqual(missing.body, refused.body);

  // Anne's place survived Ben's attempt.
  const annesList = await supertest(app)
    .get('/me/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`);
  assert.equal(annesList.body.results.length, 1);
});

test('a rider deletes their own place', async () => {
  const { app, addUser, tokenFor } = setup();
  const token = `Bearer ${tokenFor(addUser('anne'))}`;

  const created = await supertest(app).post('/me/places').set('Authorization', token).send(HOME);
  const removed = await supertest(app)
    .delete(`/me/places/${created.body.id}`)
    .set('Authorization', token);

  assert.equal(removed.status, 204);
  const listed = await supertest(app).get('/me/places').set('Authorization', token);
  assert.deepEqual(listed.body.results, []);
});

test('none of it is open to the internet', async () => {
  const { app } = setup();
  assert.equal((await supertest(app).get('/me/places')).status, 401);
  assert.equal((await supertest(app).post('/me/places').send(HOME)).status, 401);
  assert.equal((await supertest(app).delete('/me/places/1')).status, 401);
});

test('a rider cannot write into somebody else’s list by asking to', async () => {
  const { app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const ben = addUser('ben');

  // Ben posts with Anne's id in the body. It must land in Ben's list.
  await supertest(app)
    .post('/me/places')
    .set('Authorization', `Bearer ${tokenFor(ben)}`)
    .send({ ...HOME, user_id: anne });

  const annesList = await supertest(app)
    .get('/me/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`);
  const bensList = await supertest(app)
    .get('/me/places')
    .set('Authorization', `Bearer ${tokenFor(ben)}`);

  assert.deepEqual(annesList.body.results, []);
  assert.equal(bensList.body.results.length, 1);
});

test('the list is capped, and says so rather than making the rider wait', async () => {
  const { db, app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const token = `Bearer ${tokenFor(anne)}`;

  const insert = db.prepare(
    'INSERT INTO personal_places (user_id, label, name, lat, lng) VALUES (?, ?, ?, ?, ?)'
  );
  for (let i = 0; i < MAX_PER_USER; i += 1) insert.run(anne, `l${i}`, `n${i}`, 18, 98);

  const refused = await supertest(app).post('/me/places').set('Authorization', token).send(HOME);
  // 409, not 429: waiting changes nothing. Something has to come off the list.
  assert.equal(refused.status, 409);
  assert.match(refused.body.error, new RegExp(String(MAX_PER_USER)));
});

test("one rider's full list does not block another's first place", async () => {
  const { db, app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const ben = addUser('ben');

  const insert = db.prepare(
    'INSERT INTO personal_places (user_id, label, name, lat, lng) VALUES (?, ?, ?, ?, ?)'
  );
  for (let i = 0; i < MAX_PER_USER; i += 1) insert.run(anne, `l${i}`, `n${i}`, 18, 98);

  const ok = await supertest(app)
    .post('/me/places')
    .set('Authorization', `Bearer ${tokenFor(ben)}`)
    .send(HOME);
  assert.equal(ok.status, 201);
});

test('deleting the account takes its private places with it', async () => {
  const { db, app, addUser, tokenFor } = setup();
  const anne = addUser('anne');

  await supertest(app)
    .post('/me/places')
    .set('Authorization', `Bearer ${tokenFor(anne)}`)
    .send(HOME);

  db.prepare('DELETE FROM users WHERE id = ?').run(anne);

  // ON DELETE CASCADE, the opposite of shared_places. Nobody else was using
  // this row, and an account being deleted is exactly when a note about where
  // somebody lives should stop existing.
  const left = db.prepare('SELECT COUNT(*) AS n FROM personal_places').get().n;
  assert.equal(left, 0);
});

test('the two lists never mix', async () => {
  const { app, addUser, tokenFor } = setup();
  const anne = addUser('anne');
  const ben = addUser('ben');
  const annesToken = `Bearer ${tokenFor(anne)}`;

  await supertest(app).post('/me/places').set('Authorization', annesToken).send(HOME);
  await supertest(app)
    .post('/places')
    .set('Authorization', annesToken)
    .send({ name: 'ปตท สวนดอก', lat: 18.789, lng: 98.971 });

  // The shared search must never surface a private row, whoever is asking —
  // including its own owner, who would otherwise see their home address
  // labelled as something the whole group can find.
  const sharedForAnne = await supertest(app)
    .get('/places?q=' + encodeURIComponent('คอนโด'))
    .set('Authorization', annesToken);
  assert.deepEqual(sharedForAnne.body.results, []);

  const sharedForBen = await supertest(app)
    .get('/places')
    .set('Authorization', `Bearer ${tokenFor(ben)}`);
  assert.deepEqual(sharedForBen.body.results.map((r) => r.name), ['ปตท สวนดอก']);

  // And the private list holds only the private one.
  const privateForAnne = await supertest(app).get('/me/places').set('Authorization', annesToken);
  assert.deepEqual(privateForAnne.body.results.map((r) => r.name), ['คอนโด ดิ แอสตร้า']);
});


// --- the rule, enforced against the source itself ---------------------------

test('every query that touches personal_places is scoped to one rider', () => {
  // A property test over the source, because the failure mode this guards is
  // not something a request-level test can reach: it is a *future* query,
  // written by somebody who did not read personal.js, that leaves the owner
  // out. That query would pass every test in this file — it would simply
  // return more rows than it should — and the only symptom would be a stranger
  // seeing where somebody lives.
  //
  // So the assertion is about the code: any statement mentioning the table
  // also has to mention `user_id = ?`. Blunt, and deliberately hard to
  // satisfy accidentally.
  const here = path.dirname(fileURLToPath(import.meta.url));
  const src = path.join(here, '..', 'src');

  const files = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        // Migrations are DDL — they create the table rather than reading it.
        if (entry.name !== 'migrations') walk(full);
      } else if (entry.name.endsWith('.js')) {
        files.push(full);
      }
    }
  };
  walk(src);

  const offenders = [];
  for (const file of files) {
    const text = fs.readFileSync(file, 'utf8');
    if (!text.includes('personal_places')) continue;

    // Every SQL string in the file that names the table.
    const statements = text.match(/`[^`]*personal_places[^`]*`|'[^']*personal_places[^']*'/g) ?? [];
    for (const statement of statements) {
      // An INSERT scopes by column rather than by filter: the owner is written
      // into the row, from the session, and there is no existing row to
      // filter. Everything else — SELECT, UPDATE, DELETE — reaches rows that
      // already exist and must say whose.
      const scoped = /^\s*insert\s+into\s+personal_places\s*\([^)]*\buser_id\b/i.test(
        statement.slice(1, -1)
      )
        ? true
        : /user_id\s*=\s*\?/.test(statement);

      if (!scoped) {
        offenders.push(`${path.relative(src, file)}: ${statement.replace(/\s+/g, ' ').trim()}`);
      }
    }
  }

  assert.deepEqual(offenders, [], `unscoped personal_places query:\n${offenders.join('\n')}`);

  // The sweep has to have found something, or a rename of the table would
  // turn this test into one that passes by looking at nothing.
  assert.ok(
    files.some((f) => fs.readFileSync(f, 'utf8').includes('personal_places')),
    'no source file mentions personal_places — has the table been renamed?'
  );
});

test('personal_places is never joined to anything', () => {
  // A join is how a scoped query stops being scoped without looking like it
  // changed: `WHERE user_id = ?` still reads correctly while the join beside
  // it widens what "it" means. There is nothing this table needs from another
  // one — the owner is the caller and already known — so the safest rule is
  // that there is no join at all.
  const here = path.dirname(fileURLToPath(import.meta.url));
  const routes = fs.readFileSync(path.join(here, '..', 'src', 'routes', 'places.js'), 'utf8');

  const statements = routes.match(/`[^`]*personal_places[^`]*`|'[^']*personal_places[^']*'/g) ?? [];
  const joined = statements.filter((s) => /\bjoin\b/i.test(s));

  assert.deepEqual(joined, []);
  // And the file does contain joins, so this test is checking something real
  // rather than passing because nothing in it joins anything.
  assert.match(routes, /LEFT JOIN users/);
});
