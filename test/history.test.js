import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import {
  HISTORY_DEFAULT_LIMIT,
  HISTORY_MAX_LIMIT,
  validateHistoryQuery,
} from '../src/routes/positions.js';

const JWT_SECRET = 'test-secret';

/**
 * The trail a trip leaves behind.
 *
 * `position_history` and its index have been in the schema since migration
 * 0001, and the cleanup job has been purging it on a schedule ever since —
 * around an empty space, because nothing ever wrote a row. `member_positions`
 * holds one row per rider and is overwritten on every report, so the instant a
 * fix was replaced, where that rider had been was gone.
 */
function setup() {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare('INSERT INTO users (google_sub, email) VALUES (?, ?)');
  const riderId = Number(insertUser.run('sub-rider', 'rider@gmail.com').lastInsertRowid);
  const friendId = Number(insertUser.run('sub-friend', 'friend@gmail.com').lastInsertRowid);

  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test'], superuserEmails: [] },
    verifyGoogleIdToken: async () => {
      throw new Error('unused');
    },
  });

  const request = supertest(app);
  const as = (id) => ({
    get: (path) => request.get(path).set('Authorization', `Bearer ${signAccessToken(id, JWT_SECRET)}`),
    post: (path) =>
      request.post(path).set('Authorization', `Bearer ${signAccessToken(id, JWT_SECRET)}`),
  });

  return { db, riderId, friendId, as };
}

async function ride(ctx) {
  const created = await ctx.as(ctx.riderId).post('/trips').send({ name: 'Trail' });
  ctx.db
    .prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')")
    .run(created.body.id, ctx.friendId);
  return created.body.id;
}

const at = (minutesAgo) => new Date(Date.UTC(2026, 4, 1, 10, 0, 0) + minutesAgo * 60_000).toISOString();

async function report(ctx, tripId, userId, lat, lng, timestamp) {
  return ctx
    .as(userId)
    .post(`/trips/${tripId}/positions`)
    .send({ lat, lng, timestamp });
}

// --------------------------------------------------------------- the writes

test('every accepted fix leaves a breadcrumb', async (t) => {
  const ctx = setup();
  const tripId = await ride(ctx);

  await report(ctx, tripId, ctx.riderId, 18.79, 98.98, at(0));
  await report(ctx, tripId, ctx.riderId, 18.8, 98.99, at(1));
  await report(ctx, tripId, ctx.riderId, 18.81, 99.0, at(2));

  const rows = ctx.db
    .prepare('SELECT lat, lng FROM position_history WHERE trip_id = ? ORDER BY recorded_at')
    .all(tripId);

  assert.equal(rows.length, 3);
  assert.deepEqual(rows.at(-1), { lat: 18.81, lng: 99.0 });
});

test('the current position is still one row however long the trail gets', async (t) => {
  // The two answer different questions and must not be confused: "where is
  // everyone now" is one row per rider, "where have they been" is one per fix.
  const ctx = setup();
  const tripId = await ride(ctx);

  await report(ctx, tripId, ctx.riderId, 18.79, 98.98, at(0));
  await report(ctx, tripId, ctx.riderId, 18.8, 98.99, at(1));

  const current = ctx.db
    .prepare('SELECT COUNT(*) AS n FROM member_positions WHERE trip_id = ?')
    .get(tripId);

  assert.equal(current.n, 1);
});

test('a fix the position row refused leaves no breadcrumb either', async (t) => {
  // A retry, or a phone flushing a backlog after losing signal, can deliver an
  // older fix after a newer one. The position row refuses to move backwards,
  // and the trail has to refuse the same one — otherwise a replayed backlog
  // would scribble over a route that was already right.
  const ctx = setup();
  const tripId = await ride(ctx);

  await report(ctx, tripId, ctx.riderId, 18.8, 98.99, at(5));
  const stale = await report(ctx, tripId, ctx.riderId, 0.1, 0.1, at(1));

  assert.equal(stale.status, 200, 'a stale report is accepted, and quietly ignored');
  const rows = ctx.db.prepare('SELECT lat FROM position_history WHERE trip_id = ?').all(tripId);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].lat, 18.8);
});

test('each rider has their own trail', async (t) => {
  const ctx = setup();
  const tripId = await ride(ctx);

  await report(ctx, tripId, ctx.riderId, 18.79, 98.98, at(0));
  await report(ctx, tripId, ctx.friendId, 18.6, 98.9, at(0));
  await report(ctx, tripId, ctx.riderId, 18.8, 98.99, at(1));

  const mine = await ctx.as(ctx.riderId).get(`/trips/${tripId}/positions/history?user_id=${ctx.riderId}`);

  assert.equal(mine.body.points.length, 2);
  assert.ok(mine.body.points.every((point) => point.user_id === ctx.riderId));
});

// ---------------------------------------------------------------- the reads

test('the trail comes back oldest first, which is the order a line is drawn', async (t) => {
  const ctx = setup();
  const tripId = await ride(ctx);

  await report(ctx, tripId, ctx.riderId, 18.79, 98.98, at(0));
  await report(ctx, tripId, ctx.riderId, 18.8, 98.99, at(1));
  await report(ctx, tripId, ctx.riderId, 18.81, 99.0, at(2));

  const res = await ctx.as(ctx.friendId).get(`/trips/${tripId}/positions/history`);

  assert.equal(res.status, 200);
  assert.deepEqual(
    res.body.points.map((point) => point.lat),
    [18.79, 18.8, 18.81]
  );
  assert.equal(res.body.truncated, false);
});

test('`since` fetches only what has been added', async (t) => {
  // What makes the endpoint usable while riding: read the trail once, then
  // ask for the tail rather than the whole thing every time.
  const ctx = setup();
  const tripId = await ride(ctx);

  await report(ctx, tripId, ctx.riderId, 18.79, 98.98, at(0));
  await report(ctx, tripId, ctx.riderId, 18.8, 98.99, at(5));
  await report(ctx, tripId, ctx.riderId, 18.81, 99.0, at(10));

  const res = await ctx
    .as(ctx.riderId)
    .get(`/trips/${tripId}/positions/history?since=${encodeURIComponent(at(5))}`);

  assert.deepEqual(
    res.body.points.map((point) => point.lat),
    [18.81]
  );
});

test('a truncated answer says so', async (t) => {
  // A map that drew a line stopping in the middle of a road, with no way to
  // tell that from the end of the ride, would look like a bug.
  const ctx = setup();
  const tripId = await ride(ctx);

  for (let i = 0; i < 4; i += 1) {
    await report(ctx, tripId, ctx.riderId, 18.79 + i / 100, 98.98, at(i));
  }

  const res = await ctx.as(ctx.riderId).get(`/trips/${tripId}/positions/history?limit=2`);

  assert.equal(res.body.points.length, 2);
  assert.equal(res.body.truncated, true);
});

test('the trail outlives the trip', async (t) => {
  // Looking at where a group went is most of the point of having gone. Reads
  // stay open on an ended trip, like every other read on this router.
  const ctx = setup();
  const tripId = await ride(ctx);
  await report(ctx, tripId, ctx.riderId, 18.79, 98.98, at(0));
  await ctx.as(ctx.riderId).post(`/trips/${tripId}/end`);

  const res = await ctx.as(ctx.friendId).get(`/trips/${tripId}/positions/history`);

  assert.equal(res.status, 200);
  assert.equal(res.body.points.length, 1);
});

test('a stranger cannot read a trail', async (t) => {
  const ctx = setup();
  const tripId = await ride(ctx);
  const strangerId = Number(
    ctx.db
      .prepare("INSERT INTO users (google_sub, email) VALUES ('sub-x', 'x@gmail.com')")
      .run().lastInsertRowid
  );

  const res = await ctx.as(strangerId).get(`/trips/${tripId}/positions/history`);

  assert.equal(res.status, 403);
});

// ------------------------------------------------------------ the query bounds

test('the query parameters are bounded, and say what is wrong', (t) => {
  assert.ok(validateHistoryQuery({ user_id: 'me' }).error);
  assert.ok(validateHistoryQuery({ user_id: '0' }).error);
  assert.ok(validateHistoryQuery({ since: 'last tuesday' }).error);
  assert.ok(validateHistoryQuery({ limit: '-1' }).error);
  assert.ok(validateHistoryQuery({ limit: 'lots' }).error);
});

test('an unasked-for limit is the default, and an outrageous one is clamped', (t) => {
  // Clamped rather than refused: a client asking for more than the server will
  // give is not making a mistake, it is asking for everything.
  assert.equal(validateHistoryQuery({}).value.limit, HISTORY_DEFAULT_LIMIT);
  assert.equal(validateHistoryQuery({ limit: '999999' }).value.limit, HISTORY_MAX_LIMIT);
});

test('`since` is normalised to UTC, like every stored timestamp', (t) => {
  // The query compares strings; an offset left in would compare as text and
  // silently match the wrong rows.
  assert.equal(
    validateHistoryQuery({ since: '2026-05-01T17:30:00+07:00' }).value.since,
    '2026-05-01T10:30:00.000Z'
  );
});

test('a bad query is a 400, not an empty trail', async (t) => {
  const ctx = setup();
  const tripId = await ride(ctx);

  const res = await ctx.as(ctx.riderId).get(`/trips/${tripId}/positions/history?limit=0`);

  assert.equal(res.status, 400);
  assert.match(res.body.error, /limit/);
});
