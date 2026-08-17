import test from 'node:test';
import assert from 'node:assert/strict';
import jwt from 'jsonwebtoken';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { upsertGoogleUser } from '../src/auth/users.js';

const JWT_SECRET = 'test-secret';

function createTestApp({ verifyGoogleIdToken } = {}) {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const config = {
    jwtSecret: JWT_SECRET,
    googleClientIds: ['test-client-id'],
  };

  const fakeVerify =
    verifyGoogleIdToken ||
    (async () => ({
      googleSub: 'google-sub-1',
      email: 'rider@example.com',
      name: 'Rider One',
      picture: 'https://example.com/rider1.jpg',
    }));

  const app = createApp({ db, config, verifyGoogleIdToken: fakeVerify });
  return { app, db, config };
}

async function signIn(app, verifyOverride) {
  const request = supertest(app);
  const res = await request.post('/auth/google').send({ idToken: 'fake-id-token' });
  return res;
}

test('POST /auth/google signs a new user in and GET /me returns that user', async () => {
  const { app } = createTestApp();
  const request = supertest(app);

  const signInRes = await signIn(app);
  assert.equal(signInRes.status, 200);
  assert.ok(signInRes.body.accessToken);
  assert.ok(signInRes.body.refreshToken);
  assert.equal(signInRes.body.user.email, 'rider@example.com');
  assert.equal(signInRes.body.user.display_name, 'Rider One');

  const meRes = await request
    .get('/me')
    .set('Authorization', `Bearer ${signInRes.body.accessToken}`);
  assert.equal(meRes.status, 200);
  assert.equal(meRes.body.display_name, 'Rider One');
});

test('a second Google sign-in never overwrites a display_name/photo_url the user edited', async () => {
  const { app, db } = createTestApp();
  const request = supertest(app);

  const first = await signIn(app);
  const accessToken = first.body.accessToken;

  const patchRes = await request
    .patch('/me')
    .set('Authorization', `Bearer ${accessToken}`)
    .send({ display_name: 'Custom Name' });
  assert.equal(patchRes.status, 200);
  assert.equal(patchRes.body.display_name, 'Custom Name');

  // Sign in again with the (unchanged) Google profile.
  const second = await signIn(app);
  assert.equal(second.status, 200);
  assert.equal(second.body.user.display_name, 'Custom Name');
  assert.equal(second.body.user.photo_url, 'https://example.com/rider1.jpg');

  const row = db.prepare('SELECT * FROM users WHERE google_sub = ?').get('google-sub-1');
  assert.equal(row.display_name, 'Custom Name');
});

test('upsertGoogleUser seeds name/photo on insert but not on later logins', () => {
  const { db } = createTestApp();
  const now = new Date('2026-01-01T00:00:00.000Z');

  const created = upsertGoogleUser(
    db,
    { googleSub: 'sub-x', email: 'a@example.com', name: 'A', picture: 'pic-a' },
    now
  );
  assert.equal(created.display_name, 'A');
  assert.equal(created.photo_url, 'pic-a');

  db.prepare('UPDATE users SET display_name = ?, photo_url = ? WHERE id = ?').run(
    'Edited',
    'edited-pic',
    created.id
  );

  const later = upsertGoogleUser(
    db,
    { googleSub: 'sub-x', email: 'a@example.com', name: 'A (google says)', picture: 'pic-a-2' },
    new Date(now.getTime() + 1000)
  );
  assert.equal(later.display_name, 'Edited');
  assert.equal(later.photo_url, 'edited-pic');
});

test('requireAuth returns 401 with no token, a malformed header, a tampered token, or an expired token', async () => {
  const { app } = createTestApp();
  const request = supertest(app);

  const noAuth = await request.get('/me');
  assert.equal(noAuth.status, 401);

  const malformed = await request.get('/me').set('Authorization', 'NotBearer abc');
  assert.equal(malformed.status, 401);

  const signInRes = await signIn(app);
  const tampered = signInRes.body.accessToken.slice(0, -2) + 'zz';
  const tamperedRes = await request.get('/me').set('Authorization', `Bearer ${tampered}`);
  assert.equal(tamperedRes.status, 401);

  const expiredToken = jwt.sign({ sub: 1 }, JWT_SECRET, { expiresIn: -10 });
  const expiredRes = await request.get('/me').set('Authorization', `Bearer ${expiredToken}`);
  assert.equal(expiredRes.status, 401);

  const wrongSecretToken = jwt.sign({ sub: 1 }, 'wrong-secret', { expiresIn: '1h' });
  const wrongSecretRes = await request
    .get('/me')
    .set('Authorization', `Bearer ${wrongSecretToken}`);
  assert.equal(wrongSecretRes.status, 401);
});

test('POST /auth/google rejects a Google ID token that fails verification', async () => {
  const { app } = createTestApp({
    verifyGoogleIdToken: async () => {
      throw new Error('bad token');
    },
  });
  const request = supertest(app);

  const res = await request.post('/auth/google').send({ idToken: 'not-a-real-token' });
  assert.equal(res.status, 401);
});

test('POST /auth/refresh rotates the token: new pair works, and can itself be rotated again', async () => {
  const { app } = createTestApp();
  const request = supertest(app);

  const signInRes = await signIn(app);
  const oldRefreshToken = signInRes.body.refreshToken;

  const refreshRes = await request.post('/auth/refresh').send({ refreshToken: oldRefreshToken });
  assert.equal(refreshRes.status, 200);
  assert.ok(refreshRes.body.accessToken);
  assert.ok(refreshRes.body.refreshToken);
  assert.notEqual(refreshRes.body.refreshToken, oldRefreshToken);

  // The freshly-rotated token is itself valid and can be rotated further —
  // rotation isn't a one-shot capability.
  const rotateAgain = await request
    .post('/auth/refresh')
    .send({ refreshToken: refreshRes.body.refreshToken });
  assert.equal(rotateAgain.status, 200);
  assert.notEqual(rotateAgain.body.refreshToken, refreshRes.body.refreshToken);
});

test('reusing an already-rotated refresh token revokes every token for that user', async () => {
  const { app } = createTestApp();
  const request = supertest(app);

  const signInRes = await signIn(app);
  const originalRefreshToken = signInRes.body.refreshToken;

  const firstRotate = await request
    .post('/auth/refresh')
    .send({ refreshToken: originalRefreshToken });
  assert.equal(firstRotate.status, 200);
  const rotatedToken = firstRotate.body.refreshToken;

  // Reusing the now-revoked original token is treated as theft.
  const reuse = await request
    .post('/auth/refresh')
    .send({ refreshToken: originalRefreshToken });
  assert.equal(reuse.status, 401);

  // Even the legitimately-rotated token must now be revoked too.
  const rotatedNowRevoked = await request
    .post('/auth/refresh')
    .send({ refreshToken: rotatedToken });
  assert.equal(rotatedNowRevoked.status, 401);
});

test('POST /auth/logout revokes the refresh token', async () => {
  const { app } = createTestApp();
  const request = supertest(app);

  const signInRes = await signIn(app);
  const refreshToken = signInRes.body.refreshToken;

  const logoutRes = await request.post('/auth/logout').send({ refreshToken });
  assert.equal(logoutRes.status, 204);

  const afterLogout = await request.post('/auth/refresh').send({ refreshToken });
  assert.equal(afterLogout.status, 401);
});

test('an unknown refresh token is rejected', async () => {
  const { app } = createTestApp();
  const request = supertest(app);

  const res = await request.post('/auth/refresh').send({ refreshToken: 'a'.repeat(64) });
  assert.equal(res.status, 401);
});

test('PATCH /me validates display_name', async () => {
  const { app } = createTestApp();
  const request = supertest(app);

  const signInRes = await signIn(app);
  const accessToken = signInRes.body.accessToken;
  const authed = () => request.patch('/me').set('Authorization', `Bearer ${accessToken}`);

  const empty = await authed().send({ display_name: '' });
  assert.equal(empty.status, 400);

  const onlyWhitespace = await authed().send({ display_name: '   ' });
  assert.equal(onlyWhitespace.status, 400);

  const tooLong = await authed().send({ display_name: 'x'.repeat(41) });
  assert.equal(tooLong.status, 400);

  const notAString = await authed().send({ display_name: 123 });
  assert.equal(notAString.status, 400);

  const missing = await authed().send({});
  assert.equal(missing.status, 400);

  const trimmed = await authed().send({ display_name: '  Valid Name  ' });
  assert.equal(trimmed.status, 200);
  assert.equal(trimmed.body.display_name, 'Valid Name');

  const maxLength = await authed().send({ display_name: 'x'.repeat(40) });
  assert.equal(maxLength.status, 200);
});
