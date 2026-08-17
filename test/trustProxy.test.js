import test from 'node:test';
import assert from 'node:assert/strict';
import supertest from 'supertest';
import Database from 'better-sqlite3';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';

function createTestApp() {
  const db = new Database(':memory:');
  runMigrations(db, MIGRATIONS_DIR);
  const config = { jwtSecret: 'test-secret', googleClientIds: ['test-client-id'] };
  const verifyGoogleIdToken = async () => {
    throw new Error('always invalid, just here to exercise the rate limiter');
  };
  return createApp({ db, config, verifyGoogleIdToken });
}

test('trust proxy = loopback: /auth/* rate limiting tracks real client IPs from X-Forwarded-For, not the proxy', async () => {
  const app = createTestApp();
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
