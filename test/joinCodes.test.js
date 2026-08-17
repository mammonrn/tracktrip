import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import {
  CODE_ALPHABET,
  CODE_LENGTH,
  generateJoinCode,
  normalizeJoinCode,
} from '../src/trips/joinCodes.js';

const JWT_SECRET = 'test-secret';

function setup() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)'
  );
  const ownerId = Number(insertUser.run('sub-owner', 'owner@gmail.com', 'Owner').lastInsertRowid);
  const memberId = Number(insertUser.run('sub-member', 'member@gmail.com', 'Member').lastInsertRowid);
  const strangerId = Number(
    insertUser.run('sub-stranger', 'stranger@gmail.com', 'Stranger').lastInsertRowid
  );

  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'] },
    verifyGoogleIdToken: async () => {
      throw new Error('unused in these tests');
    },
  });

  return {
    db,
    app,
    ownerId,
    memberId,
    strangerId,
    ownerToken: signAccessToken(ownerId, JWT_SECRET),
    memberToken: signAccessToken(memberId, JWT_SECRET),
    strangerToken: signAccessToken(strangerId, JWT_SECRET),
  };
}

const authed = (app, method, path, token) =>
  supertest(app)[method](path).set('Authorization', `Bearer ${token}`);

async function tripWithMember(context) {
  const { app, db, ownerToken, memberId } = context;
  const created = await authed(app, 'post', '/trips', ownerToken).send({ name: 'Pai run' });
  db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')").run(
    created.body.id,
    memberId
  );
  return created.body;
}

const expire = (db, code) =>
  db
    .prepare('UPDATE trip_join_codes SET expires_at = ? WHERE code = ?')
    .run(new Date(Date.now() - 1000).toISOString(), code);

// ─── Generating ─────────────────────────────────────────────────────────────

test('generated codes are the documented length and alphabet', () => {
  const seen = new Set();

  for (let i = 0; i < 500; i += 1) {
    const code = generateJoinCode();
    assert.equal(code.length, CODE_LENGTH);
    for (const character of code) {
      assert.ok(CODE_ALPHABET.includes(character), `unexpected character ${character}`);
    }
    seen.add(code);
  }

  // 40 bits of entropy: 500 draws colliding would mean the generator is not
  // drawing from anything like the space it claims to.
  assert.equal(seen.size, 500);
});

test('the alphabet leaves out the characters people misread', () => {
  for (const ambiguous of ['I', 'O', '0', '1']) {
    assert.equal(CODE_ALPHABET.includes(ambiguous), false, `${ambiguous} should not be in use`);
  }
  // 256 / 32 is exact, which is what keeps the byte-to-character step unbiased.
  assert.equal(256 % CODE_ALPHABET.length, 0);
});

test('normalizeJoinCode accepts what a person would type back in', () => {
  assert.equal(normalizeJoinCode('abcdefgh'), 'ABCDEFGH');
  assert.equal(normalizeJoinCode('  ABCD-EFGH  '), 'ABCDEFGH');
  assert.equal(normalizeJoinCode('ABCD EFGH'), 'ABCDEFGH');

  assert.equal(normalizeJoinCode('ABCDEFG'), null); // too short
  assert.equal(normalizeJoinCode('ABCDEFGHI'), null); // too long
  assert.equal(normalizeJoinCode('ABCDEFG0'), null); // not in the alphabet
  assert.equal(normalizeJoinCode(''), null);
  assert.equal(normalizeJoinCode(null), null);
  assert.equal(normalizeJoinCode(12345678), null);
});

// ─── POST /trips/:id/join-code ──────────────────────────────────────────────

test('any member of a running trip can issue a join code', async () => {
  const context = setup();
  const trip = await tripWithMember(context);

  for (const token of [context.ownerToken, context.memberToken]) {
    const res = await authed(context.app, 'post', `/trips/${trip.id}/join-code`, token);
    assert.equal(res.status, 201);
    assert.equal(res.body.trip_id, trip.id);
    assert.equal(res.body.code.length, CODE_LENGTH);
    assert.ok(new Date(res.body.expires_at).getTime() > Date.now());
  }
});

test('a code lapses about fifteen minutes out', async () => {
  const context = setup();
  const trip = await tripWithMember(context);

  const before = Date.now();
  const res = await authed(context.app, 'post', `/trips/${trip.id}/join-code`, context.ownerToken);
  const lifetimeMs = new Date(res.body.expires_at).getTime() - before;

  assert.ok(lifetimeMs > 14 * 60 * 1000, `too short: ${lifetimeMs}ms`);
  assert.ok(lifetimeMs <= 15 * 60 * 1000 + 5000, `too long: ${lifetimeMs}ms`);
});

test('issuing a new code retires the trip\'s previous one', async () => {
  const context = setup();
  const trip = await tripWithMember(context);

  const first = await authed(context.app, 'post', `/trips/${trip.id}/join-code`, context.ownerToken);
  const second = await authed(context.app, 'post', `/trips/${trip.id}/join-code`, context.ownerToken);
  assert.notEqual(first.body.code, second.body.code);

  const withOld = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
    code: first.body.code,
  });
  assert.equal(withOld.status, 410);

  const withNew = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
    code: second.body.code,
  });
  assert.equal(withNew.status, 200);
});

test('a stranger cannot issue a code for a trip they are not on', async () => {
  const context = setup();
  const trip = await tripWithMember(context);

  const res = await authed(
    context.app,
    'post',
    `/trips/${trip.id}/join-code`,
    context.strangerToken
  );
  assert.equal(res.status, 403);
});

test('an ended trip issues no codes', async () => {
  const context = setup();
  const trip = await tripWithMember(context);
  await authed(context.app, 'post', `/trips/${trip.id}/end`, context.ownerToken);

  const res = await authed(context.app, 'post', `/trips/${trip.id}/join-code`, context.ownerToken);
  assert.equal(res.status, 409);
});

// ─── POST /trips/join ───────────────────────────────────────────────────────

test('a valid code puts the scanner on the trip', async () => {
  const context = setup();
  const trip = await tripWithMember(context);
  const { body } = await authed(
    context.app,
    'post',
    `/trips/${trip.id}/join-code`,
    context.ownerToken
  );

  const res = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
    code: body.code,
  });

  assert.equal(res.status, 200);
  assert.equal(res.body.trip.id, trip.id);
  assert.equal(res.body.trip.role, 'member');
  assert.equal(res.body.already_member, false);

  const theirTrips = await authed(context.app, 'get', '/trips', context.strangerToken);
  assert.deepEqual(
    theirTrips.body.map((each) => each.id),
    [trip.id]
  );
});

test('a code works for more than one rider', async () => {
  const context = setup();
  const trip = await tripWithMember(context);
  const { body } = await authed(
    context.app,
    'post',
    `/trips/${trip.id}/join-code`,
    context.ownerToken
  );

  const fourthRiderId = Number(
    context.db
      .prepare('INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)')
      .run('sub-fourth', 'fourth@gmail.com', 'Fourth').lastInsertRowid
  );
  const fourthToken = signAccessToken(fourthRiderId, JWT_SECRET);

  for (const token of [context.strangerToken, fourthToken]) {
    const res = await authed(context.app, 'post', '/trips/join', token).send({ code: body.code });
    assert.equal(res.status, 200);
  }

  const members = context.db
    .prepare('SELECT user_id FROM trip_members WHERE trip_id = ?')
    .all(trip.id);
  assert.equal(members.length, 4);
});

test('scanning the same code twice is a success, not a conflict', async () => {
  const context = setup();
  const trip = await tripWithMember(context);
  const { body } = await authed(
    context.app,
    'post',
    `/trips/${trip.id}/join-code`,
    context.ownerToken
  );

  await authed(context.app, 'post', '/trips/join', context.strangerToken).send({ code: body.code });
  const again = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
    code: body.code,
  });

  assert.equal(again.status, 200);
  assert.equal(again.body.already_member, true);
  assert.equal(
    context.db.prepare('SELECT COUNT(*) AS n FROM trip_members WHERE trip_id = ?').get(trip.id).n,
    3
  );
});

test('an existing member redeeming a code keeps their role', async () => {
  const context = setup();
  const trip = await tripWithMember(context);
  const { body } = await authed(
    context.app,
    'post',
    `/trips/${trip.id}/join-code`,
    context.ownerToken
  );

  const res = await authed(context.app, 'post', '/trips/join', context.ownerToken).send({
    code: body.code,
  });

  assert.equal(res.status, 200);
  assert.equal(res.body.already_member, true);
  assert.equal(res.body.trip.role, 'owner');
});

test('an unknown code is a 404 and an expired one is a 410', async () => {
  const context = setup();
  const trip = await tripWithMember(context);
  const { body } = await authed(
    context.app,
    'post',
    `/trips/${trip.id}/join-code`,
    context.ownerToken
  );

  const unknown = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
    code: 'ZZZZZZZZ',
  });
  assert.equal(unknown.status, 404);

  expire(context.db, body.code);
  const expired = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
    code: body.code,
  });
  assert.equal(expired.status, 410);

  assert.equal(
    context.db.prepare('SELECT COUNT(*) AS n FROM trip_members WHERE trip_id = ?').get(trip.id).n,
    2
  );
});

test('a code for a trip that has since ended is refused', async () => {
  const context = setup();
  const trip = await tripWithMember(context);
  const { body } = await authed(
    context.app,
    'post',
    `/trips/${trip.id}/join-code`,
    context.ownerToken
  );
  await authed(context.app, 'post', `/trips/${trip.id}/end`, context.ownerToken);

  const res = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
    code: body.code,
  });
  assert.equal(res.status, 409);
});

test('a code typed back in with dashes and lower case still works', async () => {
  const context = setup();
  const trip = await tripWithMember(context);
  const { body } = await authed(
    context.app,
    'post',
    `/trips/${trip.id}/join-code`,
    context.ownerToken
  );
  const typed = `${body.code.slice(0, 4)}-${body.code.slice(4)}`.toLowerCase();

  const res = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
    code: typed,
  });
  assert.equal(res.status, 200);
});

test('a malformed code never reaches the lookup', async () => {
  const context = setup();

  for (const code of ['', 'short', undefined, 42]) {
    const res = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
      code,
    });
    assert.equal(res.status, 400, `expected ${JSON.stringify(code)} to be rejected`);
  }
});

test('joining needs a signed-in rider', async () => {
  const context = setup();

  const res = await supertest(context.app).post('/trips/join').send({ code: 'ABCDEFGH' });
  assert.equal(res.status, 401);
});

test('guessing codes runs into the rate limiter', async () => {
  const context = setup();

  const statuses = [];
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const res = await authed(context.app, 'post', '/trips/join', context.strangerToken).send({
      code: 'ZZZZZZZZ',
    });
    statuses.push(res.status);
  }

  assert.equal(statuses[0], 404);
  assert.ok(statuses.includes(429), `expected a 429 among ${statuses.join(', ')}`);
});
