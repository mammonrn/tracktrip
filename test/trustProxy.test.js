import test from 'node:test';
import assert from 'node:assert/strict';
import supertest from 'supertest';
import Database from 'better-sqlite3';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';

function createTestApp() {
  const db = new Database(':memory:');
  runMigrations(db, MIGRATIONS_DIR);
  const config = { jwtSecret: 'test-secret', googleClientIds: ['test-client-id'] };
  const verifyGoogleIdToken = async () => {
    throw new Error('always invalid, just here to exercise the rate limiter');
  };
  return { app: createApp({ db, config, verifyGoogleIdToken }), db };
}

test('trust proxy = loopback: /auth/* rate limiting tracks real client IPs from X-Forwarded-For, not the proxy', async () => {
  const { app } = createTestApp();
  const request = supertest(app);
  const hit = (ip) => request.post('/auth/google').set('X-Forwarded-For', ip).send({ idToken: 'x' });

  // Exhaust the 20 req/min limit for one client IP.
  for (let i = 0; i < 20; i += 1) {
    const res = await hit('203.0.113.10');
    assert.notEqual(res.status, 429, `request ${i + 1} from client A should not be rate limited yet`);
  }
  const limited = await hit('203.0.113.10');
  assert.equal(limited.status, 429, 'the 21st request from client A should be rate limited');

  // A different client IP (still arriving via the same loopback nginx
  // connection) must not be lumped in with client A's usage.
  const otherClient = await hit('203.0.113.20');
  assert.notEqual(
    otherClient.status,
    429,
    'a different client IP must not share client A\'s rate limit bucket'
  );
});

test('the sign-in rate limit is scoped to /auth and does not throttle the rest of the API', async () => {
  const { app, db } = createTestApp();
  const userId = Number(
    db.prepare("INSERT INTO users (google_sub, email) VALUES ('sub-poller', 'poller@gmail.com')").run()
      .lastInsertRowid
  );
  const token = signAccessToken(userId, 'test-secret');
  const ip = '203.0.113.30';

  // Burn the whole sign-in budget for this client.
  for (let i = 0; i < 21; i += 1) {
    await supertest(app).post('/auth/google').set('X-Forwarded-For', ip).send({ idToken: 'x' });
  }
  const signIn = await supertest(app)
    .post('/auth/google')
    .set('X-Forwarded-For', ip)
    .send({ idToken: 'x' });
  assert.equal(signIn.status, 429, 'sign-in is still limited');

  // The rest of the API must not share that budget: a client polling
  // positions every few seconds would otherwise lock itself out in under a
  // minute, and the limiter is mounted app-wide ahead of those routes.
  for (let i = 0; i < 40; i += 1) {
    const res = await supertest(app)
      .get('/me')
      .set('X-Forwarded-For', ip)
      .set('Authorization', `Bearer ${token}`);
    assert.equal(res.status, 200, `request ${i + 1} to /me should not be rate limited`);
  }
});
