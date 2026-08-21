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

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)'
  );
  const riderId = Number(insertUser.run('sub-rider', 'rider@gmail.com', 'Rider').lastInsertRowid);
  const otherId = Number(insertUser.run('sub-other', 'other@gmail.com', 'Other').lastInsertRowid);

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
    riderId,
    otherId,
    riderToken: signAccessToken(riderId, JWT_SECRET),
    otherToken: signAccessToken(otherId, JWT_SECRET),
  };
}

const patchMe = (app, token) =>
  supertest(app).patch('/me').set('Authorization', `Bearer ${token}`);

const getMe = (app, token) => supertest(app).get('/me').set('Authorization', `Bearer ${token}`);

// ─── The new fields ─────────────────────────────────────────────────────────

test('PATCH /me stores every optional profile field', async () => {
  const { app, riderToken } = setup();

  const res = await patchMe(app, riderToken).send({
    first_name: '  Poom  ',
    last_name: 'Sukjai',
    username: 'poom.rides',
    phone: '081-234-5678',
    birth_date: '1994-03-17',
  });

  assert.equal(res.status, 200);
  assert.equal(res.body.first_name, 'Poom'); // trimmed
  assert.equal(res.body.last_name, 'Sukjai');
  assert.equal(res.body.username, 'poom.rides');
  assert.equal(res.body.phone, '081-234-5678');
  assert.equal(res.body.birth_date, '1994-03-17');

  const reread = await getMe(app, riderToken);
  assert.equal(reread.body.username, 'poom.rides');
});

test('GET /me reports untouched profile fields as null, not missing', async () => {
  const { app, riderToken } = setup();

  const res = await getMe(app, riderToken);

  assert.equal(res.status, 200);
  for (const field of ['first_name', 'last_name', 'username', 'phone', 'birth_date']) {
    assert.ok(field in res.body, `${field} should be present`);
    assert.equal(res.body[field], null);
  }
});

test('PATCH /me touches only the fields it is sent', async () => {
  const { app, riderToken } = setup();

  await patchMe(app, riderToken).send({ first_name: 'Poom', phone: '0812345678' });
  const res = await patchMe(app, riderToken).send({ last_name: 'Sukjai' });

  assert.equal(res.status, 200);
  assert.equal(res.body.first_name, 'Poom');
  assert.equal(res.body.phone, '0812345678');
  assert.equal(res.body.last_name, 'Sukjai');
  // display_name was never sent, so sign-in's value survives.
  assert.equal(res.body.display_name, 'Rider');
});

test('PATCH /me clears an optional field sent as null or empty', async () => {
  const { app, riderToken } = setup();

  await patchMe(app, riderToken).send({ phone: '0812345678', username: 'poom' });

  const cleared = await patchMe(app, riderToken).send({ phone: null, username: '  ' });
  assert.equal(cleared.status, 200);
  assert.equal(cleared.body.phone, null);
  assert.equal(cleared.body.username, null);
});

test('PATCH /me refuses to clear display_name', async () => {
  const { app, riderToken } = setup();

  assert.equal((await patchMe(app, riderToken).send({ display_name: null })).status, 400);
  assert.equal((await patchMe(app, riderToken).send({ display_name: '   ' })).status, 400);
  assert.equal((await getMe(app, riderToken)).body.display_name, 'Rider');
});

test('PATCH /me ignores fields it does not recognise', async () => {
  const { app, riderToken } = setup();

  const res = await patchMe(app, riderToken).send({ first_name: 'Poom', total_km: 99999 });

  assert.equal(res.status, 200);
  assert.equal(res.body.first_name, 'Poom');
  assert.equal(res.body.total_km, 0);
});

// ─── Usernames ──────────────────────────────────────────────────────────────

test('a username already held by someone else is a 409', async () => {
  const { app, riderToken, otherToken } = setup();

  assert.equal((await patchMe(app, otherToken).send({ username: 'poom' })).status, 200);

  const clash = await patchMe(app, riderToken).send({ username: 'poom' });
  assert.equal(clash.status, 409);

  // Case is not a way around it — "Poom" and "poom" would be one rider
  // impersonating another.
  const casedClash = await patchMe(app, riderToken).send({ username: 'PoOm' });
  assert.equal(casedClash.status, 409);
});

test('keeping your own username is not a clash with yourself', async () => {
  const { app, riderToken } = setup();

  await patchMe(app, riderToken).send({ username: 'poom' });
  const again = await patchMe(app, riderToken).send({ username: 'poom', first_name: 'Poom' });

  assert.equal(again.status, 200);
  assert.equal(again.body.username, 'poom');
});

test('usernames are checked for length and shape', async () => {
  const { app, riderToken } = setup();
  const rejected = ['ab', 'x'.repeat(21), 'has space', 'has@symbol', '.leading', 'trailing.', 'do..uble'];

  for (const username of rejected) {
    const res = await patchMe(app, riderToken).send({ username });
    assert.equal(res.status, 400, `expected ${JSON.stringify(username)} to be rejected`);
  }

  for (const username of ['abc', 'poom_rides', 'poom.rides', 'x'.repeat(20)]) {
    const res = await patchMe(app, riderToken).send({ username });
    assert.equal(res.status, 200, `expected ${JSON.stringify(username)} to be accepted`);
  }
});

// ─── Thai usernames ─────────────────────────────────────────────────────────
//
// See src/users/username.js. The short of it: Thai has no case, so the old
// lower(username) index did nothing for it — and Thai's collision problem is
// one lower() was never going to reach, because two different sequences of
// code points can draw exactly the same glyphs.

test('a Thai username is accepted, and stored as it was typed', async () => {
  const { app, riderToken } = setup();

  for (const username of ['กรงกราง', 'ก็อง', 'น้ำ', 'poom_ไทย', 'ไทย.rider']) {
    const res = await patchMe(app, riderToken).send({ username });
    assert.equal(res.status, 200, `expected ${username} to be accepted`);
    assert.equal(res.body.username, username);
  }
});

test('every ASCII username that worked before still works', async () => {
  // Backward compatibility is the whole risk of this change: the column has
  // rows in it already, and every one of them is ASCII.
  const { app, riderToken } = setup();

  for (const username of ['abc', 'poom', 'poom_rides', 'poom.rides', 'Poom99', 'x'.repeat(20)]) {
    const res = await patchMe(app, riderToken).send({ username });
    assert.equal(res.status, 200, `expected ${username} to be accepted`);
    assert.equal(res.body.username, username);
  }
});

test('two spellings of one Thai word are one username', async () => {
  const { app, riderToken, otherToken } = setup();

  // สำ is 0E2A 0E33. สํา is 0E2A 0E4D 0E32. Identical ink, different bytes,
  // and NFC leaves them apart — SARA AM has no canonical decomposition.
  assert.notEqual('สำ' + 'คัญ', ('สํา' + 'คัญ').normalize('NFC'));

  assert.equal((await patchMe(app, otherToken).send({ username: 'สำคัญ' })).status, 200);

  const clash = await patchMe(app, riderToken).send({ username: 'สํ' + 'าคัญ' });
  assert.equal(clash.status, 409);
  assert.match(clash.body.error, /already taken/);
});

test('marks typed in the other order are one username', async () => {
  const { app, riderToken, otherToken } = setup();

  // The vowel and the tone, stored the other way round. NFC will not reorder
  // them either: canonical ordering sorts by combining class, and SARA II's
  // is 0, so it never moves.
  const vowelFirst = 'ก' + '\u0E35' + '\u0E48' + 'ยว';
  const toneFirst = 'ก' + '\u0E48' + '\u0E35' + 'ยว';
  assert.notEqual(vowelFirst.normalize('NFC'), toneFirst.normalize('NFC'));

  assert.equal((await patchMe(app, otherToken).send({ username: vowelFirst })).status, 200);
  assert.equal((await patchMe(app, riderToken).send({ username: toneFirst })).status, 409);
});

test('Thai words that merely look alike stay different usernames', async () => {
  // The fold must not become "close enough". กอง and ก้อง are different words.
  const { app, riderToken, otherToken } = setup();

  assert.equal((await patchMe(app, otherToken).send({ username: 'กอง' })).status, 200);
  assert.equal((await patchMe(app, riderToken).send({ username: 'ก้อง' })).status, 200);
});

test('homoglyph scripts are still refused, which is what ASCII-only was doing', async () => {
  // The old pattern blocked these by accident, and \p{Script=Latin} would have
  // let the first two straight back in — U+FF30 and U+0131 are both Latin by
  // script. Script is a statement about writing systems, not about what looks
  // like what.
  const { app, riderToken } = setup();

  const refused = [
    'Ｐｏｏｍ', // fullwidth Latin
    'ıdent', // U+0131 dotless i
    'pооm', // Cyrillic о
    'Ροom', // Greek Rho
    'poom๑', // Thai digit: read aloud the same as poom1
    'poom๏', // Thai punctuation
    'ｐoom', // one fullwidth letter among ASCII
  ];

  for (const username of refused) {
    const res = await patchMe(app, riderToken).send({ username });
    assert.equal(res.status, 400, `expected ${JSON.stringify(username)} to be refused`);
  }
});

test('everything that was refused before is still refused', async () => {
  const { app, riderToken } = setup();
  const refused = [
    'ab',
    'x'.repeat(21),
    'has space',
    'has@symbol',
    '.leading',
    'trailing.',
    'do..uble',
    'poom-rides',
    'poom😀',
    "' OR 1=1 --",
    '<script>x</script>',
    'poom\u200bx', // zero-width space
    '\u0E48กอง', // opens with a combining mark, which renders as a dotted circle
  ];

  for (const username of refused) {
    const res = await patchMe(app, riderToken).send({ username });
    assert.equal(res.status, 400, `expected ${JSON.stringify(username)} to be refused`);
  }
});

test('the stored key is what the index is on, and it is not lower()', async () => {
  const { app, db, riderToken, riderId } = setup();

  await patchMe(app, riderToken).send({ username: 'สํ' + 'าคัญ' });
  const row = db.prepare('SELECT username, username_key FROM users WHERE id = ?').get(riderId);

  // Stored as typed; folded for comparison. SQLite's own lower() would have
  // left the Thai exactly as it found it.
  assert.equal(row.username, 'สํ' + 'าคัญ');
  assert.equal(row.username_key, 'สำคัญ');
  assert.notEqual(row.username_key, row.username);
});

test('clearing a username clears its key, so the name is free again', async () => {
  const { app, db, riderToken, otherToken, riderId } = setup();

  await patchMe(app, riderToken).send({ username: 'สำคัญ' });
  assert.equal((await patchMe(app, otherToken).send({ username: 'สำคัญ' })).status, 409);

  const cleared = await patchMe(app, riderToken).send({ username: '  ' });
  assert.equal(cleared.status, 200);
  assert.equal(cleared.body.username, null);
  assert.equal(db.prepare('SELECT username_key FROM users WHERE id = ?').get(riderId).username_key, null);

  // A NULL key is ignored by the unique index, so several riders can have no
  // username at once — and the name somebody released can be taken.
  assert.equal((await patchMe(app, otherToken).send({ username: 'สำคัญ' })).status, 200);
});

// ─── Phone and birth date ───────────────────────────────────────────────────

test('phone numbers are checked loosely but not blindly', async () => {
  const { app, riderToken } = setup();

  for (const phone of ['081-234-5678', '+66 81 234 5678', '0812345678', '+66812345678']) {
    const res = await patchMe(app, riderToken).send({ phone });
    assert.equal(res.status, 200, `expected ${phone} to be accepted`);
  }

  for (const phone of ['12345', 'not a phone', '+', '0812345678901234567890', 'abc-defg']) {
    const res = await patchMe(app, riderToken).send({ phone });
    assert.equal(res.status, 400, `expected ${JSON.stringify(phone)} to be rejected`);
  }
});

test('birth dates must be real dates in the past', async () => {
  const { app, riderToken } = setup();

  assert.equal((await patchMe(app, riderToken).send({ birth_date: '1994-03-17' })).status, 200);

  const nextYear = new Date();
  nextYear.setUTCFullYear(nextYear.getUTCFullYear() + 1);
  const rejected = [
    '17/03/1994', // wrong format
    '1994-3-7', // unpadded
    '2025-02-30', // not a real day, and the one a regex alone lets through
    '1800-01-01', // beyond a human lifetime
    nextYear.toISOString().slice(0, 10),
  ];

  for (const birthDate of rejected) {
    const res = await patchMe(app, riderToken).send({ birth_date: birthDate });
    assert.equal(res.status, 400, `expected ${birthDate} to be rejected`);
  }
});

test('PATCH /me needs a signed-in rider', async () => {
  const { app } = setup();

  assert.equal((await supertest(app).patch('/me').send({ first_name: 'Poom' })).status, 401);
});
