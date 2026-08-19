import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { ROLE_SUPERUSER, ROLE_USER, isSuperuser, syncSuperuserRoles } from '../src/auth/roles.js';
import { upsertGoogleUser } from '../src/auth/users.js';

const JWT_SECRET = 'test-secret';
const BOSS = 'boss@gmail.com';

/**
 * A server with three accounts and one trip that only `owner` and `friend`
 * belong to.
 *
 * `boss` is a super user by configuration and a member of nothing — which is
 * the whole point: everything below asks what somebody who belongs to no trip
 * can nonetheless do.
 */
function setup({ superuserEmails = [BOSS] } = {}) {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)'
  );
  const ownerId = Number(insertUser.run('sub-owner', 'owner@gmail.com', 'Owner').lastInsertRowid);
  const friendId = Number(insertUser.run('sub-friend', 'friend@gmail.com', 'Friend').lastInsertRowid);
  const bossId = Number(insertUser.run('sub-boss', BOSS, 'Boss').lastInsertRowid);

  const config = { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'], superuserEmails };
  const app = createApp({
    db,
    config,
    verifyGoogleIdToken: async () => {
      throw new Error('unused in these tests');
    },
  });

  const tokenFor = (id) => signAccessToken(id, JWT_SECRET);
  const agent = supertest(app);
  const as = (id) => ({
    get: (path) => agent.get(path).set('Authorization', `Bearer ${tokenFor(id)}`),
    post: (path) => agent.post(path).set('Authorization', `Bearer ${tokenFor(id)}`),
    patch: (path) => agent.patch(path).set('Authorization', `Bearer ${tokenFor(id)}`),
    delete: (path) => agent.delete(path).set('Authorization', `Bearer ${tokenFor(id)}`),
  });

  return { db, config, ownerId, friendId, bossId, as };
}

async function tripWithFriend(ctx) {
  const created = await ctx.as(ctx.ownerId).post('/trips').send({ name: 'Mae Hong Son loop' });
  const tripId = created.body.id;
  ctx.db
    .prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')")
    .run(tripId, ctx.friendId);
  return tripId;
}

// ---------------------------------------------------------------- the column

test('every account starts ordinary', (t) => {
  const { db, ownerId } = setup({ superuserEmails: [] });
  const owner = db.prepare('SELECT * FROM users WHERE id = ?').get(ownerId);

  assert.equal(owner.role, ROLE_USER);
  assert.equal(isSuperuser(owner), false);
});

test('the schema refuses a role nobody defined', (t) => {
  // The CHECK constraint is what makes the column trustworthy: a typo in a
  // future migration should fail loudly rather than create an account whose
  // role matches nothing and is therefore silently ordinary.
  const { db, ownerId } = setup();

  assert.throws(
    () => db.prepare('UPDATE users SET role = ? WHERE id = ?').run('admin', ownerId),
    /CHECK constraint failed/
  );
});

test('booting promotes the configured accounts', (t) => {
  const { db, bossId } = setup();

  assert.equal(db.prepare('SELECT role FROM users WHERE id = ?').get(bossId).role, ROLE_SUPERUSER);
});

test('the list is matched case-insensitively and untrimmed', (t) => {
  // Google returns whatever capitalisation the account was made with, and a
  // comma-separated environment variable is typed by a person.
  const { db, bossId } = setup({ superuserEmails: ['  BOSS@Gmail.COM '] });

  assert.equal(db.prepare('SELECT role FROM users WHERE id = ?').get(bossId).role, ROLE_SUPERUSER);
});

test('taking an address off the list takes the role with it', (t) => {
  // The point of holding the list in configuration: revoking is deleting a
  // line and restarting, with no database surgery and no forgotten row that
  // keeps a privilege alive.
  const { db, bossId } = setup();
  assert.equal(db.prepare('SELECT role FROM users WHERE id = ?').get(bossId).role, ROLE_SUPERUSER);

  syncSuperuserRoles(db, []);

  assert.equal(db.prepare('SELECT role FROM users WHERE id = ?').get(bossId).role, ROLE_USER);
});

test('an account with no email is left alone in both directions', (t) => {
  // It can never match a list of addresses, and it must not be demoted by one
  // either: a deploy that lost its config would otherwise strip a role that
  // nothing left could restore.
  const { db } = setup();
  const id = Number(
    db
      .prepare("INSERT INTO users (google_sub, email, role) VALUES ('sub-ghost', NULL, ?)")
      .run(ROLE_SUPERUSER).lastInsertRowid
  );

  syncSuperuserRoles(db, [BOSS]);

  assert.equal(db.prepare('SELECT role FROM users WHERE id = ?').get(id).role, ROLE_SUPERUSER);
});

test('signing in promotes an account that did not exist at boot', (t) => {
  // The case that matters on a fresh deploy: the first super user's account
  // is created by their first sign-in, long after the server started.
  const { db, config } = setup();

  const user = upsertGoogleUser(
    db,
    { googleSub: 'sub-new-boss', email: 'BOSS@gmail.com', name: 'Boss II' },
    new Date(),
    ['boss@gmail.com', 'someone-else@gmail.com']
  );

  assert.equal(user.role, ROLE_SUPERUSER);
  assert.ok(config.superuserEmails.includes(BOSS));
});

// ------------------------------------------------------------ what it allows

test('a super user reads a trip they do not belong to', async (t) => {
  const ctx = setup();
  const tripId = await tripWithFriend(ctx);

  const res = await ctx.as(ctx.bossId).get(`/trips/${tripId}/positions`);

  assert.equal(res.status, 200);
  assert.equal(res.body.length, 2);
});

test('an ordinary outsider still cannot', async (t) => {
  // The rule this widens has to keep working for everybody else, or the
  // feature is a hole rather than a role.
  const ctx = setup();
  const tripId = await tripWithFriend(ctx);

  const res = await ctx.as(ctx.friendId).get(`/trips/${tripId}/positions`);
  assert.equal(res.status, 200, 'a member reads their own trip');

  const stranger = Number(
    ctx.db
      .prepare("INSERT INTO users (google_sub, email) VALUES ('sub-stranger', 's@gmail.com')")
      .run().lastInsertRowid
  );
  const refused = await ctx.as(stranger).get(`/trips/${tripId}/positions`);

  assert.equal(refused.status, 403);
  assert.equal(refused.body.error, 'not a member of this trip');
});

test('a super user does owner-only things without being an owner', async (t) => {
  // Ending a ride whose owner has gone home with their phone off, or fixing a
  // destination somebody typed wrong.
  const ctx = setup();
  const tripId = await tripWithFriend(ctx);

  const patched = await ctx
    .as(ctx.bossId)
    .patch(`/trips/${tripId}`)
    .send({ destination: { lat: 19.3, lng: 97.97, label: 'Mae Hong Son' } });
  assert.equal(patched.status, 200);
  assert.equal(patched.body.destination.label, 'Mae Hong Son');

  const ended = await ctx.as(ctx.bossId).post(`/trips/${tripId}/end`);
  assert.equal(ended.status, 200);
  assert.equal(ended.body.status, 'ended');

  // And the trip still belongs to the person it belonged to.
  const row = ctx.db.prepare('SELECT owner_id FROM trips WHERE id = ?').get(tripId);
  assert.equal(row.owner_id, ctx.ownerId);
});

test('reading a trip does not make a super user a member of it', async (t) => {
  // The line the whole design turns on. `req.membership` is null for them
  // rather than a synthesised row, so nothing downstream can put them in a
  // roster, in a position list, or in the distance credited to real riders.
  const ctx = setup();
  const tripId = await tripWithFriend(ctx);

  await ctx.as(ctx.bossId).get(`/trips/${tripId}/positions`);

  const members = ctx.db
    .prepare('SELECT user_id FROM trip_members WHERE trip_id = ?')
    .all(tripId)
    .map((row) => row.user_id);

  assert.deepEqual(members.sort(), [ctx.ownerId, ctx.friendId].sort());
});

test('a super user cannot report a position on somebody else\'s trip', async (t) => {
  // Managing a trip is not riding on it. This route goes through
  // requireSharingAllowed, which has no bypass: a position from someone who
  // is not on the trip would be a phantom rider on everybody's map.
  const ctx = setup();
  const tripId = await tripWithFriend(ctx);

  const res = await ctx
    .as(ctx.bossId)
    .post(`/trips/${tripId}/positions`)
    .send({ lat: 18.79, lng: 98.98, timestamp: new Date().toISOString() });

  assert.equal(res.status, 403);
});

test('a super user cannot start a sharing session on a trip they are not on', async (t) => {
  // A sharing session is a rider saying where *they* are. Managing a trip
  // does not put them on it, and a session with no membership behind it would
  // be state nothing can ever read.
  const ctx = setup();
  const tripId = await tripWithFriend(ctx);

  const res = await ctx
    .as(ctx.bossId)
    .post(`/trips/${tripId}/share/start`)
    .send({ duration_minutes: 60 });

  assert.equal(res.status, 403);
  const sessions = ctx.db
    .prepare('SELECT COUNT(*) AS n FROM sharing_sessions WHERE trip_id = ?')
    .get(tripId);
  assert.equal(sessions.n, 0);
});

test('a super user who is genuinely on a trip takes part like anyone else', async (t) => {
  // The bypass must not cut both ways: being promoted cannot stop somebody
  // riding on their own trips.
  const ctx = setup();
  const own = await ctx.as(ctx.bossId).post('/trips').send({ name: 'Boss ride' });

  const started = await ctx
    .as(ctx.bossId)
    .post(`/trips/${own.body.id}/share/start`)
    .send({ duration_minutes: 60 });
  assert.equal(started.status, 200);

  const reported = await ctx
    .as(ctx.bossId)
    .post(`/trips/${own.body.id}/positions`)
    .send({ lat: 18.79, lng: 98.98, timestamp: new Date().toISOString() });
  assert.equal(reported.status, 200);
});

// ------------------------------------------------------------- listing trips

test('a super user sees only their own trips unless they ask', async (t) => {
  // Being promoted must not make their own app worse: they open it to see the
  // ride they are on, like everybody else.
  const ctx = setup();
  await tripWithFriend(ctx);

  const res = await ctx.as(ctx.bossId).get('/trips');

  assert.equal(res.status, 200);
  assert.deepEqual(res.body, []);
});

test('a super user asking for all of them gets all of them', async (t) => {
  const ctx = setup();
  const tripId = await tripWithFriend(ctx);

  const res = await ctx.as(ctx.bossId).get('/trips?all=true');

  assert.equal(res.status, 200);
  assert.equal(res.body.length, 1);
  assert.equal(res.body[0].id, tripId);
  // Not 'owner', which would be a lie about a real column, and not null,
  // which every client would have to special-case.
  assert.equal(res.body[0].role, ROLE_SUPERUSER);
});

test('a trip a super user is genuinely on keeps its real role', async (t) => {
  const ctx = setup();
  const own = await ctx.as(ctx.bossId).post('/trips').send({ name: 'My own ride' });

  const res = await ctx.as(ctx.bossId).get('/trips?all=true');
  const mine = res.body.find((trip) => trip.id === own.body.id);

  assert.equal(mine.role, 'owner');
});

test('an ordinary rider asking for all of them gets their own', async (t) => {
  // A request for more, answered with everything they are allowed to see —
  // rather than a 403 that tells them the parameter exists.
  const ctx = setup();
  await tripWithFriend(ctx);

  const res = await ctx.as(ctx.friendId).get('/trips?all=true');

  assert.equal(res.status, 200);
  assert.equal(res.body.length, 1);
  assert.equal(res.body[0].role, 'member');
});

// ------------------------------------------------------------------ /me

test('the account says what it is', async (t) => {
  const ctx = setup();

  const boss = await ctx.as(ctx.bossId).get('/me');
  const owner = await ctx.as(ctx.ownerId).get('/me');

  assert.equal(boss.body.role, ROLE_SUPERUSER);
  assert.equal(owner.body.role, ROLE_USER);
});
