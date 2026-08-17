import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { LEVELS, progressFor } from '../src/users/levels.js';

const JWT_SECRET = 'test-secret';

function setup(totalKm = 0) {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const userId = Number(
    db
      .prepare('INSERT INTO users (google_sub, email, display_name, total_km) VALUES (?, ?, ?, ?)')
      .run('sub-rider', 'rider@gmail.com', 'Rider', totalKm).lastInsertRowid
  );

  const config = { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'] };
  const app = createApp({
    db,
    config,
    verifyGoogleIdToken: async () => {
      throw new Error('unused in these tests');
    },
  });

  return { db, app, userId, token: signAccessToken(userId, JWT_SECRET) };
}

const getLevel = (app, token) =>
  supertest(app).get('/me/level').set('Authorization', `Bearer ${token}`);

// ─── The table ──────────────────────────────────────────────────────────────

test('the level table starts at zero and only ever climbs', () => {
  assert.equal(LEVELS[0].min_km, 0, 'every rider must have a level from their first ride');

  for (let i = 1; i < LEVELS.length; i += 1) {
    assert.ok(
      LEVELS[i].min_km > LEVELS[i - 1].min_km,
      `${LEVELS[i].name} must sit above ${LEVELS[i - 1].name}`
    );
  }

  assert.deepEqual(
    LEVELS.map((l) => [l.name, l.min_km]),
    [
      ['Novice', 0],
      ['Rookie Rider', 500],
      ['Wanderer', 1500],
      ['Voyager', 3500],
      ['Trailblazer', 6000],
      ['Road Warrior', 9000],
      ['Road King', 12000],
      ['Legendary', 20000],
    ]
  );
});

test('progressFor lands on the right level either side of every boundary', () => {
  for (let i = 1; i < LEVELS.length; i += 1) {
    const boundary = LEVELS[i].min_km;

    // Exactly on the threshold: promoted.
    const on = progressFor(boundary);
    assert.equal(on.level.name, LEVELS[i].name, `${boundary} km should reach ${LEVELS[i].name}`);
    assert.equal(on.km_to_next, LEVELS[i + 1] ? LEVELS[i + 1].min_km - boundary : null);

    // One kilometre short: still the level below, with 1 km to go.
    const under = progressFor(boundary - 1);
    assert.equal(under.level.name, LEVELS[i - 1].name);
    assert.equal(under.next_level.name, LEVELS[i].name);
    assert.equal(under.km_to_next, 1);
  }
});

test('progressFor reports the top of the table as having nothing left to chase', () => {
  const top = LEVELS[LEVELS.length - 1];

  for (const km of [top.min_km, top.min_km + 50000]) {
    const progress = progressFor(km);
    assert.equal(progress.level.name, 'Legendary');
    assert.equal(progress.next_level, null);
    assert.equal(progress.km_to_next, null, 'null, not 0 — there is no next level');
  }
});

test('progressFor rounds to 10 m and treats junk totals as zero', () => {
  const progress = progressFor(1234.5678);
  assert.equal(progress.total_km, 1234.57);
  assert.equal(progress.level.name, 'Rookie Rider');
  assert.equal(progress.km_to_next, 265.43);
  // The two rounded numbers agree with the boundary between them.
  assert.equal(progress.total_km + progress.km_to_next, progress.next_level.min_km);

  for (const junk of [0, -5, NaN, undefined, null]) {
    const zero = progressFor(junk);
    assert.equal(zero.total_km, 0, `${junk} should read as 0 km`);
    assert.equal(zero.level.name, 'Novice');
  }
});

test('mutating a returned level does not corrupt the table', () => {
  const progress = progressFor(0);
  progress.level.name = 'Hacked';
  progress.next_level.min_km = 1;

  assert.equal(LEVELS[0].name, 'Novice');
  assert.equal(LEVELS[1].min_km, 500);
  assert.equal(progressFor(0).level.name, 'Novice');
});

// ─── The endpoint ───────────────────────────────────────────────────────────

test('GET /me/level reports a new rider as Novice with the first level in sight', async () => {
  const { app, token } = setup();

  const res = await getLevel(app, token);
  assert.equal(res.status, 200);
  assert.deepEqual(res.body, {
    total_km: 0,
    level: { name: 'Novice', min_km: 0 },
    next_level: { name: 'Rookie Rider', min_km: 500 },
    km_to_next: 500,
  });
});

test('GET /me/level reflects the rider\'s stored mileage', async () => {
  const { app, token } = setup(6000.4);

  const res = await getLevel(app, token);
  assert.equal(res.body.total_km, 6000.4);
  assert.equal(res.body.level.name, 'Trailblazer');
  assert.equal(res.body.next_level.name, 'Road Warrior');
  assert.equal(res.body.km_to_next, 2999.6);
});

test('GET /me/level requires authentication', async () => {
  const { app } = setup();

  assert.equal((await supertest(app).get('/me/level')).status, 401);
  assert.equal((await supertest(app).get('/me/level').set('Authorization', 'NotBearer x')).status, 401);
  assert.equal(
    (await supertest(app).get('/me/level').set('Authorization', `Bearer ${signAccessToken(9999, JWT_SECRET)}`))
      .status,
    401
  );
});

test('GET /me carries total_km, so the profile screen needs one call for the basics', async () => {
  const { app, token } = setup(742.25);

  const res = await supertest(app).get('/me').set('Authorization', `Bearer ${token}`);
  assert.equal(res.status, 200);
  assert.equal(res.body.total_km, 742.25);
  assert.equal(res.body.display_name, 'Rider');
});

test('every response rounds total_km the same way, however precise the stored value', async () => {
  const { db, app, token, userId } = setup();
  // What accumulating hundreds of haversine legs actually leaves behind.
  db.prepare('UPDATE users SET total_km = ? WHERE id = ?').run(589.0071664158835, userId);

  const me = await supertest(app).get('/me').set('Authorization', `Bearer ${token}`);
  const level = await getLevel(app, token);
  assert.equal(me.body.total_km, 589.01, 'no 13-decimal number reaches the profile screen');
  assert.equal(level.body.total_km, 589.01);
  assert.equal(me.body.total_km, level.body.total_km, 'the two must never disagree');

  // Full precision is kept in the database, so small additions don't drift.
  assert.equal(db.prepare('SELECT total_km FROM users WHERE id = ?').get(userId).total_km, 589.0071664158835);
});

test('a brand new user starts at zero kilometres', async () => {
  const { db, app } = setup();

  // Straight through the sign-in path rather than seeded, so the column
  // default is what is being checked.
  const fresh = Number(
    db.prepare("INSERT INTO users (google_sub, email) VALUES ('sub-new', 'new@gmail.com')").run()
      .lastInsertRowid
  );
  const res = await getLevel(app, signAccessToken(fresh, JWT_SECRET));
  assert.equal(res.body.total_km, 0);
  assert.equal(res.body.level.name, 'Novice');
});
