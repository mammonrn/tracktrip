import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { PositionHub } from '../src/ws/hub.js';
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
function setup({ withHub = false, superuserEmails = [] } = {}) {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const insertUser = db.prepare('INSERT INTO users (google_sub, email) VALUES (?, ?)');
  const riderId = Number(insertUser.run('sub-rider', 'rider@gmail.com').lastInsertRowid);
  const friendId = Number(insertUser.run('sub-friend', 'friend@gmail.com').lastInsertRowid);

  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test'], superuserEmails },
    hub: withHub ? new PositionHub() : undefined,
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

// ------------------------------------------- lifetime distance, end to end

/**
 * Whether a ride still credits the rider who rode it.
 *
 * Asked as a whole-server question rather than a unit one because the doubt
 * was about the *route*, not the arithmetic: `POST /positions` grew two new
 * guards and a hub call when the WebSocket landed, and the concern was that
 * one of them had quietly stopped the distance being added. `countableDistanceKm`
 * has its own tests and always passed; what nobody had asserted was that a
 * sequence of ordinary reports still reaches it.
 */
test('a ride credits the rider, with a socket attached and without', async (t) => {
  for (const withHub of [false, true]) {
    const ctx = setup({ withHub });
    const tripId = await ride(ctx);

    // Eight reports, 45 seconds apart — the app's own cadence — each about
    // 550 m further north. Roughly 3.9 km of riding.
    for (let i = 0; i < 8; i += 1) {
      const res = await report(ctx, tripId, ctx.riderId, 18.79 + i * 0.005, 98.98, at(i * 0.75));
      assert.equal(res.status, 200, `report ${i} (hub: ${withHub})`);
    }

    const stored = ctx.db.prepare('SELECT total_km FROM users WHERE id = ?').get(ctx.riderId);
    assert.ok(stored.total_km > 3.5, `credited ${stored.total_km} km (hub: ${withHub})`);
    assert.ok(stored.total_km < 4.5, `credited ${stored.total_km} km (hub: ${withHub})`);

    // And the account reports it, which is what the profile screen reads.
    const me = await ctx.as(ctx.riderId).get('/me');
    assert.equal(me.body.total_km, Math.round(stored.total_km * 100) / 100);
  }
});

test('a super user riding their own trip is credited like anyone else', async (t) => {
  // The role widens what somebody may *read*; it must not change what their
  // own riding is worth. The account under test is a super user because the
  // one that reported this was.
  const ctx = setup({ superuserEmails: ['rider@gmail.com'] });
  const tripId = await ride(ctx);

  await report(ctx, tripId, ctx.riderId, 18.79, 98.98, at(0));
  await report(ctx, tripId, ctx.riderId, 18.8, 98.98, at(0.75));

  const stored = ctx.db.prepare('SELECT role, total_km FROM users WHERE id = ?').get(ctx.riderId);
  assert.equal(stored.role, 'superuser');
  assert.ok(stored.total_km > 1, `credited ${stored.total_km} km`);
});

test('the first report of a ride credits nothing, and the second credits the gap', async (t) => {
  // There is nothing to measure from on the first fix. This is why a rider
  // who reports once and stops sees 0 km — correctly.
  const ctx = setup();
  const tripId = await ride(ctx);

  await report(ctx, tripId, ctx.riderId, 18.79, 98.98, at(0));
  assert.equal(ctx.db.prepare('SELECT total_km FROM users WHERE id = ?').get(ctx.riderId).total_km, 0);

  await report(ctx, tripId, ctx.riderId, 18.8, 98.98, at(0.75));
  assert.ok(ctx.db.prepare('SELECT total_km FROM users WHERE id = ?').get(ctx.riderId).total_km > 1);
});
